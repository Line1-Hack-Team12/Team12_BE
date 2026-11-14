package com.example.line1thon.service;

import com.example.line1thon.dto.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AnalysisSessionRepository sessionRepository;

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