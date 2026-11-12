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

    @Transactional
    public Long createSessionWithFiles(Integer monthsCount, String lastMonth, String region, Integer payday,
                                       AnalysisSession.Persona persona, Boolean aiConsent,
                                       List<String> slotMonths, List<MultipartFile> files) {
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
            callAiExtraction(savedSession, uploadedFiles, files);
        }

        return savedSession.getId();
    }

    private void callAiExtraction(AnalysisSession session, List<UploadedFile> uploadedFiles, List<MultipartFile> files) {
        // 슬롯 연월별로 파일 묶기
        Map<String, List<Integer>> slotGroupMap = new HashMap<>();
        for (int i = 0; i < uploadedFiles.size(); i++) {
            String slot = uploadedFiles.get(i).getSlotMonth();
            slotGroupMap.computeIfAbsent(slot, k -> new ArrayList<>()).add(i);
        }

        boolean hasPartialFailure = false;

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
                F03ExtractResponse response = aiExtractClient.requestExtract(
                        slotMonth,
                        "사용자",
                        assetIds,
                        slotFiles
                );

                if (response != null && response.getAssets() != null) {
                    for (F03ExtractResponse.AssetDto asset : response.getAssets()) {
                        Long fileId = Long.parseLong(asset.getAssetId());
                        UploadedFile uf = fileRepository.findById(fileId).orElse(null);

                        if ("succeeded".equals(asset.getStatus())) {
                            if (uf != null) uf.markSuccess();

                            if (asset.getRows() != null) {
                                for (F03ExtractResponse.RowDto row : asset.getRows()) {
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
                                            .flags(row.getFlags() != null ? row.getFlags() : new ArrayList<>())
                                            .isExcluded(false)
                                            .build();
                                    transactionRepository.save(tx);
                                }
                            }
                        } else {
                            hasPartialFailure = true;
                            if (uf != null) {
                                uf.markFailed(asset.getErrorMessage() != null ? asset.getErrorMessage() : "소비 내역이 아니어서 제외했어요.");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("슬롯 {} AI 추출 중 예외 발생", slotMonth, e);
                hasPartialFailure = true;
                slotUploadedFiles.forEach(uf -> uf.markFailed("AI 추출 서버 연동 중 오류가 발생했습니다."));
            }
        }

        if (hasPartialFailure) {
            session.updateStatus(AnalysisSession.SessionStatus.PARTIAL_FAILED);
        } else {
            session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
        }
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
    public void appendFilesToSession(Long sessionId, List slotMonths, List files) {
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
        callAiExtraction(session, newUploadedFiles, files);
    }

    // E04: 실패한 파일만 다시 시도 (또는 캡처 교체) - 기존 성공 거래는 그대로 유지
    @Transactional
    public void retryOrReplaceFailedFiles(Long sessionId, List fileIds, List files) {
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

        callAiExtraction(session, targetUploadedFiles, files);
    }
    public AnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));
    }
}