package com.example.line1thon.service;

import com.example.line1thon.dto.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TransactionService {

    // 1. [두 행을 수정 · 확인하기] 큐 상세 조회
    public List<TransactionDetailDto> getReviewQueue(Long sessionId) {
        return List.of(
                TransactionDetailDto.builder()
                        .transactionId(101L)
                        .date("2026-09-07")
                        .time("21:15")
                        .storeName("GS25 역삼점")
                        .amount(3900L)
                        .direction("출금")
                        .type("지출")
                        .category("생활 / 편의점")
                        .isExcluded(false)
                        .build(),
                TransactionDetailDto.builder()
                        .transactionId(102L)
                        .date("2026-10-03")
                        .time("20:15")
                        .storeName("배민")
                        .amount(21000L)
                        .direction("출금")
                        .type("지출")
                        .category("배달")
                        .isExcluded(false)
                        .build()
        );
    }

    // 2. 검토 큐 확인 완료 처리
    public QueueConfirmResponse confirmReviewQueue(Long sessionId, ConfirmLowConfidenceRequest request) {
        return QueueConfirmResponse.builder()
                .sessionId(sessionId)
                .canConfirm(true)
                .remainingReviewCount(0)
                .updatedTotalExpense(1238500L)
                .build();
    }

    // 3. 단건 거래 상세 조회 (수정 폼 진입용)
    public TransactionDetailDto getTransactionDetail(Long transactionId) {
        return TransactionDetailDto.builder()
                .transactionId(transactionId)
                .date("2026-09-07")
                .time("21:15")
                .storeName("GS25 역삼점")
                .amount(3900L)
                .direction("출금")
                .type("지출")
                .category("생활 / 편의점")
                .isExcluded(false)
                .build();
    }

    // 4. 내역 수정 / 그대로 두기 / 제외 / 복원 통합 처리
    public TransactionUpdateResponse updateTransaction(Long transactionId, UpdateTransactionRequest request) {
        boolean isExcluded = "EXCLUDE".equals(request.getAction());
        String statusLabel = isExcluded ? "제외됨" : "포함";

        return TransactionUpdateResponse.builder()
                .transactionId(transactionId)
                .action(request.getAction())
                .storeName(request.getStoreName() != null ? request.getStoreName() : "GS25 역삼점")
                .amount(request.getAmount() != null ? request.getAmount() : 3900L)
                .isExcluded(isExcluded)
                .statusLabel(statusLabel)
                .sessionSummary(TransactionUpdateResponse.UpdatedSummary.builder()
                        .totalCount(isExcluded ? 86 : 87)
                        .totalExpense(isExcluded ? 1239600L : 1238500L)
                        .build())
                .build();
    }

    // 5. [펼쳐서 중복 확인] 묶음 후보 조회
    public DuplicateCandidateResponse getDuplicateCandidates(Long sessionId) {
        List<DuplicateCandidateResponse.DuplicateItemDto> items = List.of(
                DuplicateCandidateResponse.DuplicateItemDto.builder()
                        .transactionId(101L)
                        .sourceLabel("8월 캡처 A · 배민")
                        .date("08.27")
                        .time("22:05")
                        .direction("출금")
                        .amount(23500L)
                        .build(),
                DuplicateCandidateResponse.DuplicateItemDto.builder()
                        .transactionId(102L)
                        .sourceLabel("8월 캡처 B · 배민")
                        .date("08.27")
                        .time("22:05")
                        .direction("출금")
                        .amount(23500L)
                        .build()
        );

        return DuplicateCandidateResponse.builder()
                .currentGroupIndex(1)
                .totalGroupsCount(3)
                .groupTitle("중복 후보 3건 중 1건")
                .groupId(10L)
                .defaultSelection("SAME_TRANSACTION")
                .defaultSelectionDesc("한 건만 합계에 포함해요. 원본 거래는 삭제하지 않아요.")
                .items(items)
                .build();
    }

    // 6. 중복 결정 처리 (같은 거래 vs 서로 다른 결제)
    public DuplicateResolveResponse resolveDuplicate(Long sessionId, DuplicateResolveRequest request) {
        boolean isMerge = "MERGE".equals(request.getResolution());
        return DuplicateResolveResponse.builder()
                .groupId(request.getGroupId())
                .resolvedAction(request.getResolution())
                .remainingDuplicateGroups(2)
                .totalCount(isMerge ? 86 : 87)
                .totalExpense(isMerge ? 1220000L : 1243500L)
                .build();
    }

    // 7. [수정하기 · 전체 표 보기] 전체 거래 목록 조회
    public TransactionListResponse getAllTransactions(Long sessionId) {
        List<TransactionListResponse.TransactionItemDto> list = List.of(
                TransactionListResponse.TransactionItemDto.builder()
                        .transactionId(1L)
                        .date("09.07")
                        .time("21:15")
                        .storeName("GS25 역삼점")
                        .amount(3900L)
                        .direction("출금")
                        .category("생활 / 편의점")
                        .isExcluded(false)
                        .statusLabel("포함")
                        .build(),
                TransactionListResponse.TransactionItemDto.builder()
                        .transactionId(2L)
                        .date("09.10")
                        .time("22:10")
                        .storeName("배민")
                        .amount(23500L)
                        .direction("출금")
                        .category("배달")
                        .isExcluded(false)
                        .statusLabel("포함")
                        .build(),
                TransactionListResponse.TransactionItemDto.builder()
                        .transactionId(3L)
                        .date("09.17")
                        .time(null)
                        .storeName("카페 A")
                        .amount(6000L)
                        .direction("출금")
                        .category("카페")
                        .isExcluded(true)
                        .statusLabel("제외됨")
                        .build()
        );

        return TransactionListResponse.builder()
                .totalCount(87)
                .totalExpense(1243500L)
                .rangeDescription("업로드한 8~10월 기록 기준")
                .transactions(list)
                .build();
    }
}