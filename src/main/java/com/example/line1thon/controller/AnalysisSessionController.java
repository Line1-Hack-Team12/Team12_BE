package com.example.line1thon.controller;

import com.example.line1thon.dto.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.global.common.ApiResponse;
import com.example.line1thon.service.AnalysisSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "01. Analysis Session", description = "S01/S02 세션 생성 및 추출 상태 관리, S03 확정 및 분석 API")
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class AnalysisSessionController {

    private final AnalysisSessionService sessionService;

    @Operation(summary = "S01 세션 생성 및 영수증 캡처 다중 업로드")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> createSessionWithFiles(
            @RequestParam Integer monthsCount,
            @RequestParam String lastMonth,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Integer payday,
            @RequestParam AnalysisSession.Persona persona,
            @RequestParam Boolean aiConsent,
            @RequestParam List<String> slotMonths,
            @RequestPart(value = "files") List<MultipartFile> files
    ) {
        Long sessionId = sessionService.createSessionWithFiles(
                monthsCount, lastMonth, region, payday, persona, aiConsent, slotMonths, files
        );
        return ApiResponse.ok("세션 생성 및 파일 업로드가 완료되었습니다.", sessionId);
    }

    @Operation(summary = "S02 추출 진행 및 상태 폴링", description = "진행률, 4단계 상태, 파일별 상태, 실패 목록을 반환합니다.")
    @GetMapping("/{sessionId}/status")
    public ApiResponse<SessionDetailResponse> getSessionStatus(@PathVariable Long sessionId) {
        return ApiResponse.ok(sessionService.getSessionStatus(sessionId));
    }

    @Operation(summary = "S02 [파일 실패 예시] 데모 트리거", description = "디자인 시연용: 8장 중 7장 완료 상태로 전환합니다.")
    @PostMapping("/{sessionId}/mock/simulate-fail")
    public ApiResponse<String> simulateFail(@PathVariable Long sessionId) {
        sessionService.simulateFail(sessionId);
        return ApiResponse.ok("파일 실패 상태로 전환되었습니다.", "PARTIAL_FAILED");
    }

    @Operation(summary = "S02 [완료 상태 보기] 데모 트리거", description = "디자인 시연용: 전체 완료 상태로 즉시 전환합니다.")
    @PostMapping("/{sessionId}/mock/simulate-complete")
    public ApiResponse<String> simulateComplete(@PathVariable Long sessionId) {
        sessionService.simulateComplete(sessionId);
        return ApiResponse.ok("전체 완료 상태로 전환되었습니다.", "REVIEWING");
    }

    @Operation(summary = "S02/E04 실패화면: [성공한 7장으로 검토]", description = "실패 파일 제외하고 S03 검토 화면(REVIEWING)으로 이동합니다.")
    @PostMapping("/{sessionId}/proceed-partial")
    public ApiResponse<String> proceedPartial(@PathVariable Long sessionId) {
        sessionService.proceedWithSuccessful(sessionId);
        return ApiResponse.ok("성공한 데이터로 검토를 진행합니다.", "REVIEWING");
    }

    @Operation(summary = "S02/E04 실패화면: [실패한 파일만 다시 시도]", description = "실패한 파일을 재추출하며 S02 진행 화면(EXTRACTING)으로 돌아갑니다.")
    @PostMapping("/{sessionId}/retry-failed")
    public ApiResponse<String> retryFailed(
            @PathVariable Long sessionId,
            @RequestParam(required = false) List<Long> fileIds,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) {
        if (files != null && !files.isEmpty()) {
            sessionService.retryOrReplaceFailedFiles(sessionId, fileIds, files);
        } else {
            sessionService.retryFailedFiles(sessionId);
        }
        return ApiResponse.ok("실패한 파일의 재추출을 시작합니다. S02 진행 화면으로 이동합니다.", "EXTRACTING");
    }

    @Operation(summary = "S02/E04 실패화면: [캡처 교체하기]", description = "실패 파일을 삭제하고 S01 캡처 선택 화면(UPLOADED)으로 돌아갑니다.")
    @PostMapping("/{sessionId}/reset-for-replace")
    public ApiResponse<String> resetForReplace(@PathVariable Long sessionId) {
        sessionService.resetForReplace(sessionId);
        return ApiResponse.ok("실패 파일이 정리되었습니다. S01 캡처 화면으로 이동합니다.", "UPLOADED");
    }

    @Operation(summary = "E03 달 추가하기 (기존 세션에 캡처 추가 업로드)", description = "기존 세션의 거래 내역을 유지한 채 새로운 월의 캡처 파일을 추가 추출합니다.")
    @PostMapping(value = "/{sessionId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SessionDetailResponse> appendFiles(
            @PathVariable Long sessionId,
            @RequestParam(required = false) List<String> slotMonths,
            @RequestPart("files") List<MultipartFile> files
    ) {
        sessionService.appendFilesToSession(sessionId, slotMonths, files);
        return ApiResponse.ok("추가 캡처 업로드 및 추출이 완료되었습니다.", sessionService.getSessionStatus(sessionId));
    }

    @Operation(summary = "S03 검토 화면 요약 데이터 조회", description = "상단 요약, 확신 낮은 행, 중복 요약 정보를 반환합니다.")
    @GetMapping("/{sessionId}/review")
    public ApiResponse<ReviewSummaryResponse> getReviewSummary(@PathVariable Long sessionId) {
        return ApiResponse.ok(sessionService.getReviewSummary(sessionId));
    }

    @Operation(summary = "S03 건수와 합계 확인 체크박스 체크", description = "체크박스 체크 시 호출되어 S03-R(건수와 합계 확인 완료) 화면 데이터를 반환합니다.")
    @PostMapping("/{sessionId}/review/check")
    public ApiResponse<ReviewCompletedResponse> checkReviewSummary(@PathVariable Long sessionId) {
        return ApiResponse.ok("확인 완료 처리되었습니다. S03-R 요약 정보를 반환합니다.",
                sessionService.checkAndGetCompletedSummary(sessionId));
    }

    @Operation(summary = "S03-R [계속 · 확정하고 분석] 클릭", description = "최종 확정하고 S03-P 분석 진행 화면으로 이동합니다.")
    @PostMapping("/{sessionId}/confirm")
    public ApiResponse<SessionConfirmResponse> confirmSession(@PathVariable Long sessionId) {
        return ApiResponse.ok("소비 데이터가 확정되었으며 최종 분석을 시작합니다.",
                sessionService.confirmAndStartAnalysis(sessionId));
    }

    @Operation(summary = "S03-P 최종 분석 진행 상태 폴링", description = "최종 집계, 반복 패턴 찾기, 문장 작성 3단계 진행 상황을 조회합니다.")
    @GetMapping("/{sessionId}/analysis/status")
    public ApiResponse<FinalAnalysisStatusResponse> getFinalAnalysisStatus(@PathVariable Long sessionId) {
        return ApiResponse.ok(sessionService.getFinalAnalysisStatus(sessionId));
    }
}