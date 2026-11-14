package com.example.line1thon.controller;

import com.example.line1thon.dto.*;
import com.example.line1thon.global.common.ApiResponse;
import com.example.line1thon.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "03. Transaction Review & Edit", description = "S01 파일 업로드 및 S03 검토 큐, 내역 수정 API")
@RestController
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    // ==========================================
    // S01 API: 파일 업로드 (엑셀 + 보조 파일)
    // ==========================================
    @Operation(summary = "S01 거래내역 및 보조 파일 업로드", description = "엑셀 파일과 보조 파일(캡처, PDF)을 통합 업로드하고, 생성된 Session ID를 반환합니다.")
    @PostMapping(value = "/api/transactions/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> uploadTransactionFiles(
            @Parameter(description = "엑셀 거래내역 파일 (필수)")
            @RequestPart(value = "excelFile") MultipartFile excelFile,

            @Parameter(description = "보조 파일들 (캡처 이미지, PDF 등 - 선택)")
            @RequestPart(value = "auxFiles", required = false) List<MultipartFile> auxFiles,

            @Parameter(description = "엑셀 열기 암호 (선택)")
            @RequestParam(value = "password", required = false) String password,

            @Parameter(description = "사용자 이름 (본인 계좌 이체 구분용 - 선택)")
            @RequestParam(value = "userName", required = false) String userName
    ) {
        // 서비스 실행 후 생성된 세션 ID를 받아옴
        Long newSessionId = transactionService.processUpload(excelFile, auxFiles, password, userName);

        // 프론트엔드가 다음 S03 API들을 호출할 수 있도록 sessionId를 리턴
        return ApiResponse.ok("파일 업로드 및 파싱이 완료되었습니다.", newSessionId);
    }

    // ==========================================
    // S03 API: 기존 로직
    // ==========================================
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

    @Operation(summary = "내역 수정 / 그대로 두기 / 제외 / 복원 통합 처리")
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