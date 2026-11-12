package com.example.line1thon.controller;

import com.example.line1thon.dto.*;
import com.example.line1thon.service.NotificationAndSummaryService;
import com.example.line1thon.service.ReportService;
import com.example.line1thon.service.SavingsPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "04. 리포트 · 적금 플랜 · 알림 API", description = "S08 소비 리포트 상세, S09 저축 목표 플랜, S06 알림 관리, S10 완료 요약")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final SavingsPlanService savingsPlanService;
    private final NotificationAndSummaryService notificationAndSummaryService;

    @Operation(summary = "S08 소비 리포트 상세 조회", description = "카테고리 비중, 월별 추이, 정기결제 감지 내역을 조회합니다.")
    @GetMapping("/sessions/{sessionId}/report")
    public ResponseEntity<ConsumptionReportResponse> getReport(@PathVariable Long sessionId) {
        return ResponseEntity.ok(reportService.getConsumptionReport(sessionId));
    }

    @Operation(summary = "S09 월 저축 목표 플랜 시뮬레이션 조회", description = "목표 금액과 제외 항목을 기준으로 플랜 A/B를 계산합니다.")
    @GetMapping("/sessions/{sessionId}/savings-plans")
    public ResponseEntity<SavingsPlanResponse> getSavingsPlans(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "50000") Long targetAmount,
            @RequestParam(required = false) List<String> excludeCategories
    ) {
        return ResponseEntity.ok(savingsPlanService.simulatePlans(sessionId, targetAmount, excludeCategories));
    }

    @Operation(summary = "S09 적금 플랜 선택 확정", description = "선택한 플랜을 저장하고 연동된 demo 알림을 활성화합니다.")
    @PostMapping("/sessions/{sessionId}/savings-plans/select")
    public ResponseEntity selectSavingsPlan(
            @PathVariable Long sessionId,
            @RequestBody SelectSavingsPlanRequest request
    ) {
        return ResponseEntity.ok(savingsPlanService.selectPlan(sessionId, request));
    }

    @Operation(summary = "S06 알림 설정 및 규칙 목록 조회", description = "세션에 연결된 demo 알림 규칙 목록과 미리보기를 조회합니다.")
    @GetMapping("/sessions/{sessionId}/notifications")
    public ResponseEntity<NotificationResponse> getNotifications(@PathVariable Long sessionId) {
        return ResponseEntity.ok(notificationAndSummaryService.getNotifications(sessionId));
    }

    @Operation(summary = "S06 알림 시각 및 켜짐/꺼짐 수정", description = "알림 시각 변경 또는 토글 상태를 수정합니다.")
    @PatchMapping("/notifications/{notificationId}")
    public ResponseEntity updateNotification(
            @PathVariable Long notificationId,
            @RequestBody UpdateNotificationRequest request
    ) {
        return ResponseEntity.ok(notificationAndSummaryService.updateNotification(notificationId, request));
    }

    @Operation(summary = "S06 알림 규칙 해제", description = "해당 demo 알림 규칙을 삭제합니다.")
    @DeleteMapping("/notifications/{notificationId}")
    public ResponseEntity deleteNotification(@PathVariable Long notificationId) {
        return ResponseEntity.ok(notificationAndSummaryService.deleteNotification(notificationId));
    }

    @Operation(summary = "S06 지금 보내보기 (인앱 데모 발송)", description = "설정된 알림 미리보기 메시지를 즉시 인앱 데모 형태로 반환합니다.")
    @PostMapping("/notifications/{notificationId}/demo-send")
    public ResponseEntity sendDemoNotification(@PathVariable Long notificationId) {
        return ResponseEntity.ok(notificationAndSummaryService.sendDemoNotification(notificationId));
    }

    @Operation(summary = "S10 완료 요약 조회", description = "관찰한 패턴, 답한 질문, 켜진 demo 규칙, 선택한 플랜 요약을 조회합니다.")
    @GetMapping("/sessions/{sessionId}/completion-summary")
    public ResponseEntity<CompletionSummaryResponse> getCompletionSummary(@PathVariable Long sessionId) {
        return ResponseEntity.ok(notificationAndSummaryService.getCompletionSummary(sessionId));
    }
}