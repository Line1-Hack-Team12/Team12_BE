package com.example.line1thon.service;

import com.example.line1thon.dto.CompletionSummaryResponse;
import com.example.line1thon.dto.NotificationResponse;
import com.example.line1thon.dto.UpdateNotificationRequest;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.DemoNotification;
import com.example.line1thon.entity.SelectedSavingsPlan;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.DemoNotificationRepository;
import com.example.line1thon.repository.SelectedSavingsPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationAndSummaryService {

    private final AnalysisSessionRepository sessionRepository;
    private final DemoNotificationRepository notificationRepository;
    private final SelectedSavingsPlanRepository selectedPlanRepository;

    // S06: 알림 설정 목록 조회 (없으면 기본 데모 규칙 1건 자동 초기화)
    @Transactional
    public NotificationResponse getNotifications(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        List<DemoNotification> rules = notificationRepository.findBySession(session);
        if (rules.isEmpty()) {
            DemoNotification defaultRule = createDefaultDemoRule(session, "플랜에서 켜짐", "소비");
            rules = List.of(notificationRepository.save(defaultRule));
        }

        List<NotificationResponse.NotificationRuleDto> dtoList = rules.stream()
                .map(r -> NotificationResponse.NotificationRuleDto.builder()
                        .notificationId(r.getId())
                        .sourceLabel(r.getSourceLabel())
                        .scheduleTitle(r.getScheduleTitle())
                        .dayCondition(r.getDayCondition())
                        .weatherCondition(r.getWeatherCondition())
                        .triggerCriteria(r.getTriggerCriteria())
                        .alertTime(r.getAlertTime())
                        .isEnabled(r.getIsEnabled())
                        .previewTagLabel(r.getPreviewTagLabel())
                        .previewMessage(r.getPreviewMessage())
                        .disclaimerNotice("발해금지 22:00-08:00 · 주 최대 3회. 실제 푸시는 권한 · 구독 · 동의 · 최신 기록 연결 후 가능해요.")
                        .build())
                .toList();

        return NotificationResponse.builder()
                .sessionId(sessionId)
                .notifications(dtoList)
                .policyFooter("F14 P1 | demo 기본 · live 4요건 · 22-08시 · 10분 초과 · 주3회 skip")
                .build();
    }

    // S06: 알림 시각 / 켜짐 토글 수정
    @Transactional
    public Map<String, Object> updateNotification(Long notificationId, UpdateNotificationRequest request) {
        DemoNotification rule = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림 규칙을 찾을 수 없습니다. id=" + notificationId));

        rule.updateSettings(request.getAlertTime(), request.getIsEnabled());

        return Map.of(
                "notificationId", rule.getId(),
                "scheduleTitle", rule.getScheduleTitle(),
                "alertTime", rule.getAlertTime(),
                "isEnabled", rule.getIsEnabled(),
                "message", "알림 설정이 업데이트되었습니다."
        );
    }

    // S06: 이 규칙 해제 (삭제)
    @Transactional
    public Map<String, Object> deleteNotification(Long notificationId) {
        DemoNotification rule = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림 규칙을 찾을 수 없습니다. id=" + notificationId));

        notificationRepository.delete(rule);
        return Map.of(
                "notificationId", notificationId,
                "message", "알림 규칙이 해제되었습니다."
        );
    }

    // S06: 지금 보내보기 (인앱 데모 발송)
    // S06: 지금 보내보기 (사전 알림 데모 화면 응답)
    public Map sendDemoNotification(Long notificationId) {
        DemoNotification rule = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림 규칙을 찾을 수 없습니다. id=" + notificationId));

        String demoTitle = rule.getDayCondition() + " 밤이에요";

        return Map.of(
                "notificationId", rule.getId(),
                "appName", "소비 습관 돌아보기",
                "timeLabel", "지금",
                "badge", "DEMO",
                "title", demoTitle,
                "body", rule.getPreviewMessage(),
                "disclaimerNotice", "과거 기록을 참고한 사전 안내이며 새 결제를 감지한 결과가 아닙니다. 인앱 미리보기라 실제 발송 기록에 남지 않아요.",
                "sent", true
        );
    }

    // S10: 소비 돌아보기를 마쳤어요 (완료 요약 조회)
    public CompletionSummaryResponse getCompletionSummary(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        long activeRuleCount = notificationRepository.countBySessionAndIsEnabledTrue(session);

        String planSummary = selectedPlanRepository.findBySession(session)
                .map(p -> {
                    String shortCode = p.getPlanCode().replace("PLAN_", "");
                    return String.format("%s - 월 %,d원 제안", shortCode, p.getMonthlySavedAmount());
                })
                .orElse("선택된 플랜 없음");

        // TODO [보경님 S05/S05-1 머지 후 연동]
        // 1. observedPatternCount = habitPatternRepository.countBySession(session)
        // 2. answeredQuestionCount = patternTagRepository.countBySession(session)
        // 3. chosenActionMessage = 선택된 태그 기반 다음 행동 문구로 교체
        int observedPatternCount = 3;
        int answeredQuestionCount = 1;
        String chosenActionMessage = "한 달 뒤 다시 기록을 올려보세요. 기록 보관 기간이 지나면 새로 업로드해야 해요.";

        return CompletionSummaryResponse.builder()
                .sessionId(sessionId)
                .observedPatternCount(observedPatternCount)
                .answeredQuestionCount(answeredQuestionCount)
                .activeDemoRuleCount(activeRuleCount)
                .selectedPlanSummary(planSummary)
                .chosenActionMessage(chosenActionMessage)
                .footerNotice("P0 | 태그 · 알림 · 플랜은 선택 · 실제 개선 성과로 표시하지 않음")
                .build();
    }

    // S09 플랜 선택 시 호출할 헬퍼 메서드
    @Transactional
    public void createOrActivateRuleFromPlan(AnalysisSession session, String categoryName) {
        List<DemoNotification> existing = notificationRepository.findBySession(session);
        if (existing.isEmpty()) {
            notificationRepository.save(createDefaultDemoRule(session, "플랜에서 켜짐", categoryName));
        } else {
            existing.get(0).updateSettings(null, true);
        }
    }

    public DemoNotification createDefaultDemoRule(AnalysisSession session, String sourceLabel, String category) {
        // TODO [보경님 S05-1 머지 후 연동] 사용자가 선택한 원인 태그(예: "야근") 및 패턴 횟수를 반영하여 미리보기 문구 동적 생성
        String safeCategory = (category != null && !category.isBlank()) ? category : sourceLabel;
        DemoNotification notification = DemoNotification.builder()
                .session(session)
                .sourceLabel(sourceLabel)
                .scheduleTitle("목요일 21:00")
                .dayCondition("목요일")
                .weatherCondition("없음")
                .triggerCriteria("예상 소비 22시의 한 시간 전")
                .alertTime("21:00")
                .isEnabled(true)
                .previewTagLabel("소비 패턴을 반영한 미리보기")
                .previewMessage(safeCategory + " 지출을 고민한다면 장바구니와 보유 내역부터 먼저 확인해 보세요.")
                .build();
        return notificationRepository.save(notification);
    }
}