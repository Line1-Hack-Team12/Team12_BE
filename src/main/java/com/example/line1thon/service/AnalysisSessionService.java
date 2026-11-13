package com.example.line1thon.service;

import com.example.line1thon.dto.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.entity.UploadedFile;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import com.example.line1thon.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisSessionService {

    private final AnalysisSessionRepository sessionRepository;
    private final UploadedFileRepository fileRepository;
    private final TransactionRepository transactionRepository;
    private final AiExtractClient aiExtractClient;
    private final AiServiceClient aiServiceClient;

    private static final Set<String> BANK_FILE_EXTS = Set.of("xlsx", "xlsm");
    private static final int CLASSIFY_BATCH = 500;
    private static final String CHARGE_FLAG = "charge_as_expense";
    private static final List<String> PAY_COMPANIES = List.of("네이버파이낸셜", "네이버페이", "카카오페이", "토스페이", "페이코");

    @Transactional
    public Long createSessionWithFiles(Integer monthsCount, String lastMonth, String region, Integer payday,
                                       AnalysisSession.Persona persona, Boolean aiConsent,
                                       List<String> slotMonths, List<MultipartFile> files,
                                       String ownerName, String filePassword) {
        if (aiConsent == null || !aiConsent) {
            throw new IllegalArgumentException("외부 AI 전송에 동의해야 분석을 시작할 수 있습니다.");
        }

        // 1. 세션 생성
        AnalysisSession session = AnalysisSession.builder()
                .monthsCount(monthsCount)
                .lastMonth(lastMonth)
                .region(region)
                .payday(payday)
                .persona(persona)
                .build();
        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);
        AnalysisSession savedSession = sessionRepository.save(session);

        // 2. 업로드 파일 정보 저장
        List<UploadedFile> uploadedFiles = new ArrayList<>();
        if (files != null && slotMonths != null) {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String slotMonth = i < slotMonths.size() ? slotMonths.get(i) : lastMonth;

                UploadedFile uploadedFile = UploadedFile.builder()
                        .session(savedSession)
                        .slotMonth(slotMonth)
                        .originalFileName(file.getOriginalFilename())
                        .build();

                uploadedFiles.add(fileRepository.save(uploadedFile));
            }
        }

        // 3. 실제 FastAPI AI 추출기 호출 및 결과 저장
        if (!uploadedFiles.isEmpty()) {
            callAiExtraction(savedSession, uploadedFiles, files, ownerName, filePassword);
        }

        return savedSession.getId();
    }

    /**
     * 업로드 파일을 AI 서비스로 보내 거래 행을 저장한다.
     * - 캡처 이미지: 슬롯 달별로 묶어 POST /extract
     * - 은행 거래내역 파일(xlsx): 파일마다 POST /parse (세션의 슬롯 달만)
     * 그 뒤 새 행의 가맹점을 POST /classify 로 분류해 저장하고, 간편결제 충전 되돌림 규칙을 적용한다.
     */
    private void callAiExtraction(AnalysisSession session, List<UploadedFile> uploadedFiles, List<MultipartFile> files,
                                  String ownerName, String filePassword) {
        boolean hasPartialFailure = false;
        List<Transaction> saved = new ArrayList<>();

        // 1. 은행 파일은 바로 /parse, 캡처는 슬롯 달별로 묶는다
        Map<String, List<Integer>> slotGroupMap = new LinkedHashMap<>();
        for (int i = 0; i < uploadedFiles.size(); i++) {
            UploadedFile uf = uploadedFiles.get(i);
            MultipartFile file = files.get(i);
            if (!isBankFile(file)) {
                slotGroupMap.computeIfAbsent(uf.getSlotMonth(), k -> new ArrayList<>()).add(i);
                continue;
            }
            try {
                F03ExtractResponse response = aiExtractClient.requestParse(
                        file, String.valueOf(uf.getId()), ownerName, filePassword, sessionMonths(session));
                int succeededMonths = 0;
                if (response != null && response.getAssets() != null) {
                    for (F03ExtractResponse.AssetDto asset : response.getAssets()) {
                        if ("succeeded".equals(asset.getStatus())) {
                            succeededMonths++;
                            saveRows(session, uf, asset.getRows(), saved);
                        }
                    }
                }
                if (succeededMonths > 0) {
                    uf.markSuccess();
                } else {
                    hasPartialFailure = true;
                    uf.markFailed("파일에 선택한 달의 거래가 없어요.");
                }
            } catch (AiExtractClient.AiRejectedException e) {
                // 암호 오류(422), 지원하지 않는 파일(415) 등: AI 서비스의 한글 사유를 그대로 보여준다
                hasPartialFailure = true;
                uf.markFailed(e.getMessage());
            } catch (Exception e) {
                log.error("파일 {} AI 파싱 중 예외 발생", uf.getId(), e);
                hasPartialFailure = true;
                uf.markFailed("AI 추출 서버 연동 중 오류가 발생했습니다.");
            }
        }

        // 2. 캡처: 슬롯 달별 /extract
        for (Map.Entry<String, List<Integer>> entry : slotGroupMap.entrySet()) {
            String slotMonth = entry.getKey();
            List<Integer> indices = entry.getValue();

            List<String> assetIds = new ArrayList<>();
            List<MultipartFile> slotFiles = new ArrayList<>();
            List<UploadedFile> slotUploadedFiles = new ArrayList<>();

            for (int idx : indices) {
                UploadedFile uf = uploadedFiles.get(idx);
                assetIds.add(String.valueOf(uf.getId()));
                slotFiles.add(files.get(idx));
                slotUploadedFiles.add(uf);
            }

            try {
                F03ExtractResponse response = aiExtractClient.requestExtract(slotMonth, ownerName, assetIds, slotFiles);

                if (response != null && response.getAssets() != null) {
                    for (F03ExtractResponse.AssetDto asset : response.getAssets()) {
                        Long fileId = Long.parseLong(asset.getAssetId());
                        UploadedFile uf = fileRepository.findById(fileId).orElse(null);

                        if ("succeeded".equals(asset.getStatus())) {
                            if (uf != null) uf.markSuccess();
                            saveRows(session, uf, asset.getRows(), saved);
                        } else {
                            hasPartialFailure = true;
                            if (uf != null) {
                                uf.markFailed(asset.getErrorMessage() != null ? asset.getErrorMessage() : "소비 내역이 아니어서 제외했어요.");
                            }
                        }
                    }
                }
            } catch (AiExtractClient.AiRejectedException e) {
                hasPartialFailure = true;
                slotUploadedFiles.forEach(uf -> uf.markFailed(e.getMessage()));
            } catch (Exception e) {
                log.error("슬롯 {} AI 추출 중 예외 발생", slotMonth, e);
                hasPartialFailure = true;
                slotUploadedFiles.forEach(uf -> uf.markFailed("AI 추출 서버 연동 중 오류가 발생했습니다."));
            }
        }

        // 3. 새 행의 가맹점 분류 (실패해도 추출 결과는 그대로 둔다)
        try {
            classifyAndStore(saved);
        } catch (RuntimeException e) {
            log.warn("가맹점 분류 실패, 키워드 분류로 대체: {}", e.toString());
        }

        // 4. 간편결제 충전 되돌림 규칙
        applyPayChargeRule(session);

        if (hasPartialFailure) {
            session.updateStatus(AnalysisSession.SessionStatus.PARTIAL_FAILED);
        } else {
            session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
        }
    }

    private void saveRows(AnalysisSession session, UploadedFile uf, List<F03ExtractResponse.RowDto> rows,
                          List<Transaction> saved) {
        if (rows == null) return;
        for (F03ExtractResponse.RowDto row : rows) {
            Transaction tx = Transaction.builder()
                    .session(session)
                    .uploadedFile(uf)
                    .txId(row.getId())
                    .date(row.getDate())
                    .time(row.getTime())
                    .storeName(row.getMerchant())
                    .amount(row.getAmountKrw() != null ? row.getAmountKrw() : 0L)
                    .direction(row.getDirection())
                    .kind(row.getKind())
                    .confidence(row.getConfidence())
                    .rawText(row.getRawText())
                    .flags(row.getFlags() != null ? new ArrayList<>(row.getFlags()) : new ArrayList<>())
                    .isExcluded(false)
                    .build();
            saved.add(transactionRepository.save(tx));
        }
    }

    static boolean isBankFile(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return BANK_FILE_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 세션이 다루는 달 (lastMonth 에서 monthsCount 개 거슬러 + 업로드 파일의 슬롯 달). YYYY-MM 오름차순. */
    private List<String> sessionMonths(AnalysisSession session) {
        Set<String> months = new TreeSet<>();
        try {
            YearMonth last = YearMonth.parse(session.getLastMonth());
            int n = session.getMonthsCount() == null ? 1 : Math.max(1, session.getMonthsCount());
            for (int i = 0; i < n; i++) {
                months.add(last.minusMonths(i).toString());
            }
        } catch (RuntimeException ignored) {
            // lastMonth 형식이 이상하면 파일의 슬롯 달만 쓴다
        }
        fileRepository.findBySession(session).stream()
                .map(UploadedFile::getSlotMonth)
                .filter(m -> m != null && m.length() == 7)
                .forEach(months::add);
        return new ArrayList<>(months);
    }

    /** 새로 저장된 행의 가맹점을 AI /classify 로 분류해 Transaction 에 저장한다. 가맹점 하나당 한 번만 묻는다. */
    private void classifyAndStore(List<Transaction> txs) {
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction t : txs) {
            String m = t.getStoreName();
            if (m == null || m.isBlank()) continue;
            if (!(t.isCountedAsExpense() || "unknown".equals(t.getKind()))) continue;
            byMerchant.computeIfAbsent(m.trim(), k -> new ArrayList<>()).add(t);
        }
        if (byMerchant.isEmpty()) return;

        List<String> merchants = new ArrayList<>(byMerchant.keySet());
        for (int from = 0; from < merchants.size(); from += CLASSIFY_BATCH) {
            List<String> chunk = merchants.subList(from, Math.min(from + CLASSIFY_BATCH, merchants.size()));
            List<AiServiceClient.ClassifyItemRequest> items = chunk.stream()
                    .map(m -> new AiServiceClient.ClassifyItemRequest(m, byMerchant.get(m).get(0).getKind(), null))
                    .toList();

            Optional<AiServiceClient.ClassifyResponse> res = aiServiceClient.classify(items);
            if (res.isEmpty() || res.get().items() == null) continue;

            List<AiServiceClient.ClassifyItemResult> results = res.get().items();
            for (int i = 0; i < results.size(); i++) {
                AiServiceClient.ClassifyItemResult r = results.get(i);
                // 응답 순서는 요청과 같다. 가맹점명이 돌아오면 그걸로, 아니면 순서로 맞춘다
                String merchant = (r.merchant() != null && byMerchant.containsKey(r.merchant())) ? r.merchant()
                        : (i < chunk.size() ? chunk.get(i) : null);
                if (merchant == null || r.category() == null) continue;
                for (Transaction t : byMerchant.get(merchant)) {
                    t.updateCategory(r.category(), r.subcategory(), r.confidence());
                }
            }
        }
        log.info("가맹점 분류 저장 merchants={}", merchants.size());
    }

    /**
     * 간편결제 충전 되돌림 규칙.
     * AI 추출은 "네이버페이 충전", "내 계좌 → 카카오페이" 같은 행을 self_transfer(집계 제외) 로 둔다.
     * 그런데 그 달에 그 간편결제로 결제한 행이 하나도 없으면 충전 자체가 지출이므로 expense 로 되돌린다.
     * 나중에 같은 달의 결제 행이 추가되면 다시 self_transfer 로 돌린다.
     */
    private void applyPayChargeRule(AnalysisSession session) {
        List<Transaction> all = transactionRepository.findBySession(session);
        Map<String, List<Transaction>> byMonth = new HashMap<>();
        for (Transaction t : all) {
            String d = t.getDate();
            if (d == null || d.length() < 7) continue;
            byMonth.computeIfAbsent(d.substring(0, 7), k -> new ArrayList<>()).add(t);
        }
        int reverted = 0;
        for (List<Transaction> month : byMonth.values()) {
            boolean payPaymentExists = month.stream()
                    .anyMatch(t -> "expense".equals(t.getKind()) && mentionsPayCompany(textOf(t)));
            for (Transaction t : month) {
                String text = textOf(t);
                if (payPaymentExists) {
                    if (t.getFlags() != null && t.getFlags().contains(CHARGE_FLAG)) {
                        t.restoreSelfTransfer(CHARGE_FLAG);
                    }
                } else if ("self_transfer".equals(t.getKind()) && (isPayCharge(text) || mentionsPayCompany(text))) {
                    t.overrideKind("expense", CHARGE_FLAG);
                    reverted++;
                }
            }
        }
        if (reverted > 0) {
            log.info("충전 되돌림 규칙 적용 rows={}", reverted);
        }
    }

    private static String textOf(Transaction t) {
        return (t.getStoreName() == null ? "" : t.getStoreName()) + " " + (t.getRawText() == null ? "" : t.getRawText());
    }

    private static boolean mentionsPayCompany(String text) {
        return PAY_COMPANIES.stream().anyMatch(text::contains);
    }

    /** "네이버페이 충전", "카카오페이 머니 충전", "결제 충전". 교통카드(티머니) 충전은 지출이므로 제외. */
    private static boolean isPayCharge(String text) {
        if (!text.contains("충전") || text.contains("티머니")) return false;
        return text.contains("페이") || text.contains("머니") || text.contains("결제 충전");
    }

    public SessionDetailResponse getSessionStatus(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> files = fileRepository.findBySession(session);

        int totalCount = files.size();
        List<UploadedFile> failed = files.stream()
                .filter(f -> f.getStatus() == UploadedFile.ProcessStatus.FAILED)
                .toList();

        List<SessionDetailResponse.StepDto> steps = List.of(
                SessionDetailResponse.StepDto.builder().stepNumber(1).name("캡처 읽기").status("COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(2).name("가맹점 분류")
                        .status(session.getStatus() == AnalysisSession.SessionStatus.EXTRACTING ? "IN_PROGRESS" : "COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(3).name("중복 정리").status("COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(4).name("패턴 후보 찾기").status("COMPLETED").build()
        );

        List<SessionDetailResponse.FileStatusDto> fileStatuses = files.stream()
                .map(f -> SessionDetailResponse.FileStatusDto.builder()
                        .fileId(f.getId())
                        .fileName(f.getOriginalFileName())
                        .statusText(f.getStatus() == UploadedFile.ProcessStatus.FAILED ? "실패" : "완료")
                        .build())
                .toList();

        List<SessionDetailResponse.FailedFileDto> failedDtos = failed.stream()
                .map(f -> SessionDetailResponse.FailedFileDto.builder()
                        .fileId(f.getId())
                        .fileName(f.getOriginalFileName())
                        .slotMonth(f.getSlotMonth())
                        .reason(f.getFailReason())
                        .build())
                .collect(Collectors.toList());

        String guide = (session.getStatus() == AnalysisSession.SessionStatus.PARTIAL_FAILED)
                ? "성공한 " + (totalCount - failed.size()) + "장의 거래는 그대로 유지돼요. 다시 시도해도 같은 거래가 중복으로 생기지 않아요."
                : "성공한 파일은 유지하고, 실패한 파일만 다시 시도할 수 있어요.";

        int completed = totalCount - failed.size();
        int progress = totalCount == 0 ? 100 : (int) Math.round(((double) completed / totalCount) * 100);

        return SessionDetailResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .totalCount(totalCount)
                .completedCount(completed)
                .progressPercentage(progress)
                .currentStep(4)
                .steps(steps)
                .fileStatuses(fileStatuses)
                .failedFiles(failedDtos)
                .guideMessage(guide)
                .build();
    }

    @Transactional
    public void simulateFail(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.PARTIAL_FAILED);
        List<UploadedFile> files = fileRepository.findBySession(session);
        if (!files.isEmpty()) {
            files.get(files.size() - 1).markFailed("소비 내역이 아니어서 제외했어요.");
        }
    }

    @Transactional
    public void simulateComplete(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    @Transactional
    public void proceedWithSuccessful(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    @Transactional
    public void retryFailedFiles(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        for (UploadedFile f : failedFiles) {
            f.markSuccess();
        }
        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);
    }

    @Transactional
    public void resetForReplace(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles);
        session.updateStatus(AnalysisSession.SessionStatus.UPLOADED);
    }

    // 실제 DB에 저장된 AI 추출 결과로 S03 요약 반환
    public ReviewSummaryResponse getReviewSummary(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> transactions = transactionRepository.findBySession(session);

        long totalCount = transactions.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = transactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        List<ReviewSummaryResponse.LowConfidenceItemDto> queueItems = transactions.stream()
                .filter(Transaction::isReviewNeeded)
                .map(tx -> ReviewSummaryResponse.LowConfidenceItemDto.builder()
                        .transactionId(tx.getId())
                        .storeName(tx.getStoreName())
                        .date(tx.getDate())
                        .originalAmount(tx.getAmount())
                        .suggestedAmount(tx.getAmount())
                        .isConfirmed(false)
                        .build())
                .toList();

        ReviewSummaryResponse.SummaryDto summary = ReviewSummaryResponse.SummaryDto.builder()
                .slotDescription("업로드한 기록 기준 · 슬롯 " + session.getMonthsCount() + "개")
                .statusTag("검토 중")
                .totalCount((int) totalCount)
                .totalExpense(totalExpense)
                .build();

        ReviewSummaryResponse.LowConfidenceQueueDto lowConfidence = ReviewSummaryResponse.LowConfidenceQueueDto.builder()
                .count(queueItems.size())
                .badge("확인 필요")
                .items(queueItems)
                .build();

        ReviewSummaryResponse.DuplicateSummaryDto duplicateSummary = ReviewSummaryResponse.DuplicateSummaryDto.builder()
                .message("중복 후보 0건")
                .candidateCount(0)
                .build();

        return ReviewSummaryResponse.builder()
                .sessionId(session.getId())
                .summary(summary)
                .lowConfidenceQueue(lowConfidence)
                .duplicateSummary(duplicateSummary)
                .canConfirm(queueItems.isEmpty())
                .build();
    }

    @Transactional
    public ReviewCompletedResponse checkAndGetCompletedSummary(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> transactions = transactionRepository.findBySession(session);

        long totalCount = transactions.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = transactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();
        long reviewCount = transactions.stream().filter(Transaction::isReviewNeeded).count();

        return ReviewCompletedResponse.builder()
                .sessionId(session.getId())
                .slotDescription("업로드한 기록 기준")
                .statusTag("검토 중")
                .totalCount((int) totalCount)
                .totalExpense(totalExpense)
                .reviewedSummary(ReviewCompletedResponse.ReviewedItemCounts.builder()
                        .lowConfidenceCount((int) reviewCount)
                        .duplicateCount(0)
                        .requiredValueErrorCount(0)
                        .build())
                .isChecked(true)
                .guideMessage("계속하면 확정한 기록으로 패턴을 계산해요. 원본 이미지 삭제가 시작돼요.")
                .build();
    }

    @Transactional
    public SessionConfirmResponse confirmAndStartAnalysis(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.ANALYZING);

        return SessionConfirmResponse.builder()
                .sessionId(sessionId)
                .snapshotId(1001L)
                .status("ANALYZING")
                .nextStepUrl("/api/sessions/" + sessionId + "/analysis/status")
                .build();
    }

    public FinalAnalysisStatusResponse getFinalAnalysisStatus(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> transactions = transactionRepository.findBySession(session);

        long totalCount = transactions.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = transactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        List<FinalAnalysisStatusResponse.StepInfo> steps = List.of(
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(1)
                        .name("최종 집계")
                        .detail(String.format("지출 %d건 · %,d원", totalCount, totalExpense))
                        .status("COMPLETED")
                        .build(),
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(2)
                        .name("반복 패턴 찾기")
                        .detail("요일 · 늦은 시간 · 연쇄 · 날씨")
                        .status("IN_PROGRESS")
                        .build(),
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(3)
                        .name("설명 문장 쓰기")
                        .detail(session.getPersona() != null ? session.getPersona().name().toLowerCase() + " 말투로" : "코치 말투로")
                        .status("WAITING")
                        .build()
        );

        return FinalAnalysisStatusResponse.builder()
                .sessionId(session.getId())
                .snapshotId(1001L)
                .status("ANALYZING")
                .title("확정한 " + totalCount + "건으로 최종 분석하고 있어요")
                .subTitle("이제 숫자는 바뀌지 않아요. 원본 캡처 삭제를 시작했어요.")
                .currentStep(2)
                .steps(steps)
                .guideMessage("완료되면 결과 홈으로 이동해요. 숫자 요약은 먼저 보여드리고, 문장은 준비되는 대로 채워요.")
                .build();
    }
    // E03: 기존 세션에 '달 추가하기'로 새 캡처 파일 누적 업로드 및 AI 추출
    @Transactional
    public void appendFilesToSession(Long sessionId, List slotMonths, List files, String ownerName, String filePassword) {
        AnalysisSession session = findSession(sessionId);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("추가할 파일이 없습니다.");
        }

        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);

        List newUploadedFiles = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = (MultipartFile) files.get(i);
            String slotMonth = (slotMonths != null && i < slotMonths.size())
                    ? (String) slotMonths.get(i)
                    : session.getLastMonth();

            UploadedFile uploadedFile = UploadedFile.builder()
                    .session(session)
                    .slotMonth(slotMonth)
                    .originalFileName(file.getOriginalFilename())
                    .build();
            newUploadedFiles.add(fileRepository.save(uploadedFile));
        }

        // 기존 거래 내역은 유지한 채 새로 추가된 파일들만 AI 추출 실행
        callAiExtraction(session, newUploadedFiles, files, ownerName, filePassword);
    }

    // E04: 실패한 파일만 다시 시도 (또는 캡처 교체) - 기존 성공 거래는 그대로 유지
    @Transactional
    public void retryOrReplaceFailedFiles(Long sessionId, List fileIds, List files, String ownerName, String filePassword) {
        AnalysisSession session = findSession(sessionId);
        List failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);

        if (failedFiles.isEmpty()) {
            session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
            return;
        }

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("다시 시도할 캡처 파일을 첨부해 주세요.");
        }

        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);

        List targetUploadedFiles = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile newFile = (MultipartFile) files.get(i);
            UploadedFile targetUf;

            if (fileIds != null && i < fileIds.size()) {
                Long targetId = Long.valueOf(String.valueOf(fileIds.get(i)));
                targetUf = fileRepository.findById(targetId)
                        .orElseThrow(() -> new IllegalArgumentException("파일을 찾을 수 없습니다. id=" + targetId));
            } else if (i < failedFiles.size()) {
                targetUf = (UploadedFile) failedFiles.get(i);
            } else {
                break;
            }

            // 기존 실패 레코드의 파일명 갱신 후 재추출 대상에 추가 (중복 거래 방지)
            targetUf.replaceFile(newFile.getOriginalFilename());
            targetUploadedFiles.add(targetUf);
        }

        callAiExtraction(session, targetUploadedFiles, files, ownerName, filePassword);
    }
    public AnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));
    }
}