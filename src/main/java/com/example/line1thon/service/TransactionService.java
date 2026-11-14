package com.example.line1thon.service;

import com.example.line1thon.dto.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AnalysisSessionRepository sessionRepository;

    // ==========================================
    // 0. S01 파일 업로드 및 엑셀 파싱 로직
    // ==========================================
    @Transactional
    public Long processUpload(MultipartFile excelFile, List<MultipartFile> auxFiles, String password, String userName) {
        // 1. 새로운 분석 세션 생성 및 저장
        AnalysisSession session = AnalysisSession.builder().build();        sessionRepository.save(session);

        // 2. 엑셀 파일 파싱하여 해당 세션과 연결해 DB 저장
        parseExcelAndSave(excelFile, password, userName, session);

        // 3. 보조 파일(캡처, PDF)이 있을 경우 처리 (AI 연동 등)
        if (auxFiles != null && !auxFiles.isEmpty()) {
            processAuxFiles(auxFiles);
        }

        // 4. 프론트엔드가 사용할 수 있도록 생성된 세션 ID 반환
        return session.getId();
    }

    private void parseExcelAndSave(MultipartFile excelFile, String password, String userName, AnalysisSession session) {
        try (InputStream is = excelFile.getInputStream();
             Workbook workbook = WorkbookFactory.create(is, password)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<Transaction> transactionList = new ArrayList<>();

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // 헤더 스킵

                Cell dateCell = row.getCell(0);
                Cell amountCell = row.getCell(1);
                Cell senderCell = row.getCell(2);

                if (dateCell != null && amountCell != null) {
                    String dateStr = formatter.formatCellValue(dateCell);
                    String storeName = formatter.formatCellValue(senderCell);
                    String amountStr = formatter.formatCellValue(amountCell).replace(",", "");

                    long amount = 0L;
                    if (!amountStr.isBlank()) {
                        amount = Long.parseLong(amountStr);
                    }

                    // 본인 이체 여부 확인 (이름이 포함되어 있으면 true)
                    boolean isSelfTransfer = false;
                    if (userName != null && !userName.isBlank() && storeName.contains(userName)) {
                        isSelfTransfer = true;
                    }

                    // Transaction 엔티티 생성 및 세션 연결
                    Transaction tx = Transaction.builder()
                            .date(dateStr)
                            .storeName(storeName)
                            .amount(amount)
                            .isExcluded(isSelfTransfer) // 본인 이체면 제외 처리
                            .session(session) // 방금 만든 세션에 묶어주기!
                            .build();

                    transactionList.add(tx);
                }
            }

            if (!transactionList.isEmpty()) {
                transactionRepository.saveAll(transactionList);
                log.info("새로운 세션(ID:{})에 엑셀 데이터 {}건 파싱 및 DB 저장 완료", session.getId(), transactionList.size());
            }

        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("엑셀 파일의 비밀번호가 올바르지 않습니다.");
        } catch (Exception e) {
            throw new RuntimeException("엑셀 데이터 파싱 중 오류가 발생했습니다.", e);
        }
    }

    private void processAuxFiles(List<MultipartFile> auxFiles) {
        for (MultipartFile file : auxFiles) {
            log.info("보조 파일 전송 대기: {}", file.getOriginalFilename());
            // TODO: AI 서버로 멀티파트 전송 등 보조 파일 처리 로직
        }
    }


    // ==========================================
    // 기존 S03 로직들 (수정 없이 그대로 유지)
    // ==========================================

    // 1. 검토 큐 상세 조회 (확인 필요한 거래 목록)
    public List<TransactionDetailDto> getReviewQueue(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        return transactionRepository.findBySession(session).stream()
                .filter(Transaction::isReviewNeeded)
                .map(this::toDetailDto)
                .toList();
    }

    // 2. 검토 큐 확인 완료 처리
    @Transactional
    public QueueConfirmResponse confirmReviewQueue(Long sessionId, ConfirmLowConfidenceRequest request) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> transactions = transactionRepository.findBySession(session);

        long totalExpense = transactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        return QueueConfirmResponse.builder()
                .sessionId(sessionId)
                .canConfirm(true)
                .remainingReviewCount(0)
                .updatedTotalExpense(totalExpense)
                .build();
    }

    // 3. 단건 거래 상세 조회
    public TransactionDetailDto getTransactionDetail(Long transactionId) {
        Transaction tx = findTransaction(transactionId);
        return toDetailDto(tx);
    }

    // 4. 내역 수정 / 그대로 두기 / 제외 / 복원 통합 처리
    @Transactional
    public TransactionUpdateResponse updateTransaction(Long transactionId, UpdateTransactionRequest request) {
        Transaction tx = findTransaction(transactionId);
        boolean isExcluded = "EXCLUDE".equals(request.getAction());

        tx.updateDetails(
                request.getDate(),
                request.getTime(),
                request.getStoreName(),
                request.getAmount(),
                isExcluded
        );

        List<Transaction> sessionTxs = transactionRepository.findBySession(tx.getSession());
        long totalCount = sessionTxs.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = sessionTxs.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        return TransactionUpdateResponse.builder()
                .transactionId(tx.getId())
                .action(request.getAction())
                .storeName(tx.getStoreName())
                .amount(tx.getAmount())
                .isExcluded(tx.getIsExcluded())
                .statusLabel(Boolean.TRUE.equals(tx.getIsExcluded()) ? "제외됨" : "포함")
                .sessionSummary(TransactionUpdateResponse.UpdatedSummary.builder()
                        .totalCount((int) totalCount)
                        .totalExpense(totalExpense)
                        .build())
                .build();
    }

    // 5. 중복 후보 조회
    public DuplicateCandidateResponse getDuplicateCandidates(Long sessionId) {
        return DuplicateCandidateResponse.builder()
                .currentGroupIndex(0)
                .totalGroupsCount(0)
                .groupTitle("중복 후보 0건")
                .groupId(0L)
                .defaultSelection("SAME_TRANSACTION")
                .defaultSelectionDesc("중복된 거래가 없어요.")
                .items(List.of())
                .build();
    }

    // 6. 중복 결정 처리
    @Transactional
    public DuplicateResolveResponse resolveDuplicate(Long sessionId, DuplicateResolveRequest request) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> sessionTxs = transactionRepository.findBySession(session);
        long totalCount = sessionTxs.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = sessionTxs.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        return DuplicateResolveResponse.builder()
                .groupId(request.getGroupId())
                .resolvedAction(request.getResolution())
                .remainingDuplicateGroups(0)
                .totalCount((int) totalCount)
                .totalExpense(totalExpense)
                .build();
    }

    // 7. 전체 거래 목록 조회 (AI 추출 결과 전체 표)
    public TransactionListResponse getAllTransactions(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<Transaction> transactions = transactionRepository.findBySession(session);

        long totalCount = transactions.stream().filter(Transaction::isCountedAsExpense).count();
        long totalExpense = transactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .mapToLong(Transaction::getAmount)
                .sum();

        List<TransactionListResponse.TransactionItemDto> list = transactions.stream()
                .map(tx -> TransactionListResponse.TransactionItemDto.builder()
                        .transactionId(tx.getId())
                        .date(tx.getDate())
                        .time(tx.getTime())
                        .storeName(tx.getStoreName())
                        .amount(tx.getAmount())
                        .direction("in".equals(tx.getDirection()) ? "입금" : "출금")
                        .category(tx.getKind())
                        .isExcluded(tx.getIsExcluded())
                        .statusLabel(Boolean.TRUE.equals(tx.getIsExcluded()) ? "제외됨" : "포함")
                        .build())
                .toList();

        return TransactionListResponse.builder()
                .totalCount((int) totalCount)
                .totalExpense(totalExpense)
                .rangeDescription("AI 추출 거래 내역 전체")
                .transactions(list)
                .build();
    }

    private TransactionDetailDto toDetailDto(Transaction tx) {
        return TransactionDetailDto.builder()
                .transactionId(tx.getId())
                .date(tx.getDate())
                .time(tx.getTime())
                .storeName(tx.getStoreName())
                .amount(tx.getAmount())
                .direction("in".equals(tx.getDirection()) ? "입금" : "출금")
                .type(tx.getKind())
                .category(tx.getKind())
                .isExcluded(tx.getIsExcluded())
                .build();
    }

    private AnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));
    }

    private Transaction findTransaction(Long transactionId) {
        return transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("거래 내역을 찾을 수 없습니다. id=" + transactionId));
    }
}