package com.example.line1thon.controller;

import com.example.line1thon.dto.*;
import com.example.line1thon.global.common.ApiResponse;
import com.example.line1thon.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "03. Transaction Review & Edit", description = "S03 검토 큐, 전체 내역 표, 중복 확인, 내역 수정 API")
@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "S03 [두 행을 수정 · 확인하기] 큐 상세 조회")
    @GetMapping("/api/sessions/{sessionId}/review/queue")
    public ApiResponse<List<TransactionDetailDto>> getReviewQueue(@PathVariable Long sessionId) {
        return ApiResponse.ok(transactionService.getReviewQueue(sessionId));
    }

    @Operation(summary = "S03 검토 큐 확인 완료 처리", description = "두 행 처리를 완료하고 S03 메인의 확정 체크박스를 활성화합니다.")
    @PostMapping("/api/sessions/{sessionId}/review/queue/confirm")
    public ApiResponse<QueueConfirmResponse> confirmReviewQueue(
            @PathVariable Long sessionId,
            @RequestBody ConfirmLowConfidenceRequest request) {
        return ApiResponse.ok("검토 큐 확인이 완료되었습니다.", transactionService.confirmReviewQueue(sessionId, request));
    }

    @Operation(summary = "S03 [수정하기 · 전체 표 보기] 클릭 시")
    @GetMapping("/api/sessions/{sessionId}/transactions")
    public ApiResponse<TransactionListResponse> getAllTransactions(@PathVariable Long sessionId) {
        return ApiResponse.ok(transactionService.getAllTransactions(sessionId));
    }

    @Operation(summary = "단건 거래 상세 조회", description = "특정 행의 수정 폼을 열 때 사용합니다.")
    @GetMapping("/api/transactions/{transactionId}")
    public ApiResponse<TransactionDetailDto> getTransactionDetail(@PathVariable Long transactionId) {
        return ApiResponse.ok(transactionService.getTransactionDetail(transactionId));
    }

    @Operation(summary = "내역 수정 / 그대로 두기 / 제외 / 복원 통합 처리",
            description = "하단 3개 버튼(수정 저장, 그대로 두기, 이 내역 제외) 및 복원 클릭 시 호출됩니다.")
    @PutMapping("/api/transactions/{transactionId}")
    public ApiResponse<TransactionUpdateResponse> updateTransaction(
            @PathVariable Long transactionId,
            @RequestBody UpdateTransactionRequest request) {
        return ApiResponse.ok("내역이 성공적으로 반영되었습니다.", transactionService.updateTransaction(transactionId, request));
    }

    @Operation(summary = "S03 [펼쳐서 중복 확인] 클릭 시")
    @GetMapping("/api/sessions/{sessionId}/duplicates")
    public ApiResponse<DuplicateCandidateResponse> getDuplicateCandidates(@PathVariable Long sessionId) {
        return ApiResponse.ok(transactionService.getDuplicateCandidates(sessionId));
    }

    @Operation(summary = "중복 결정 (같은 거래 vs 서로 다른 결제)")
    @PostMapping("/api/sessions/{sessionId}/duplicates/resolve")
    public ApiResponse<DuplicateResolveResponse> resolveDuplicate(
            @PathVariable Long sessionId,
            @RequestBody DuplicateResolveRequest request) {
        return ApiResponse.ok("중복 결정이 반영되었습니다.", transactionService.resolveDuplicate(sessionId, request));
    }
}