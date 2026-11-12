package com.example.line1thon.service;

import com.example.line1thon.dto.CompletionSummaryResponse;
import com.example.line1thon.dto.NotificationResponse;
import com.example.line1thon.dto.UpdateNotificationRequest;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.DemoNotification;
import com.example.line1thon.entity.PatternResponse;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.DemoNotificationRepository;
import com.example.line1thon.repository.PatternResponseRepository;
import com.example.line1thon.repository.SelectedSavingsPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationAndSummaryService {

    private static final String DEFAULT_NOTICE =
            "과거 기록을 참고한 사전 안내이며 새 결제를 감지한 결과가 아닙니다.";

    private final AnalysisSessionRepository sessionRepository;
    private final DemoNotificationRepository notificationRepository;
    private final SelectedSavingsPlanRepository selectedPlanRepository;
    private final AnalysisPatternService analysisPatternService;
    private final PatternResponseRepository patternResponseRepository;
    private final AiServiceClient aiServiceClient;

    // S06: 알림 설정 목록 조회
    @Transactional
    public NotificationResponse getNotifications(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        List<DemoNotification> rules = notificationRepository.findBySession(session);
        if (rules.isEmpty()) {
            DemoNotification defaultRule = createDefaultDemoRule(session, "플랜에서 켜짐", "소비");
            rules = List.of(defaultRule);
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
                        .disclaimerNotice("방해금지 22:00-08:00 · 주 최대 3회. 실제 푸시는 권한 · 구독 · 동의 · 최신 기록 연결 후 가능해요.")
                        .build())
                .toList();

        return NotificationResponse.builder()
                .sessionId(sessionId)
                .notifications(dtoList)
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

    // S06: 지금 보내보기 (AI /notify-copy 우선 호출, 미구현 시 백엔드 동적 문구 Fallback)
    public Map<String, Object> sendDemoNotification(Long notificationId) {
        DemoNotification rule = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("알림 규칙을 찾을 수 없습니다. id=" + notificationId));

        Optional<AiServiceClient.NotifyCopyResponse> aiCopyOpt =
                requestAiNotifyCopy(rule.getSession(), rule.getAlertTime());

        String demoTitle;
        String demoBody;
        String notice;

        if (aiCopyOpt.isPresent()
                && aiCopyOpt.get().title() != null && !aiCopyOpt.get().title().isBlank()
                && aiCopyOpt.get().body() != null && !aiCopyOpt.get().body().isBlank()) {
            AiServiceClient.NotifyCopyResponse aiCopy = aiCopyOpt.get();
            demoTitle = aiCopy.title();
            demoBody = aiCopy.body();
            notice = (aiCopy.notice() != null && !aiCopy.notice().isBlank())
                    ? aiCopy.notice()
                    : DEFAULT_NOTICE;
        } else {
            String day = rule.getDayCondition();
            boolean isMorning = rule.getAlertTime() != null && rule.getAlertTime().compareTo("12:00") < 0;
            if (day != null && day.endsWith("요일")) {
                demoTitle = isMorning ? day + " 오전이에요" : day + " 밤이에요";
            } else {
                demoTitle = isMorning ? "오늘 오전이에요" : "소비 주의 시간이에요";
            }
            demoBody = rule.getPreviewMessage();
            notice = DEFAULT_NOTICE + " 인앱 미리보기라 실제 발송 기록에 남지 않아요.";
        }

        return Map.of(
                "notificationId", rule.getId(),
                "appName", "소비 습관 돌아보기",
                "timeLabel", "지금",
                "badge", "DEMO",
                "title", demoTitle,
                "body", demoBody,
                "disclaimerNotice", notice,
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

        int observedPatternCount = analysisPatternService.analyze(sessionId).patterns().size();

        List<PatternResponse> responses = patternResponseRepository.findByAnalysisId(sessionId);
        List<PatternResponse> answeredList = responses.stream()
                .filter(r -> "ANSWERED".equals(r.getResponseStatus()))
                .toList();
        int answeredQuestionCount = answeredList.size();

        String chosenActionMessage = resolveChosenTagLabel(answeredList)
                .map(tagLabel -> String.format("'%s' 원인으로 반복된 소비를 줄이기 위해 한 달 뒤 다시 기록을 올려보세요.", tagLabel))
                .orElse("한 달 뒤 다시 기록을 올려보세요. 기록 보관 기간이 지나면 새로 업로드해야 해요.");

        return CompletionSummaryResponse.builder()
                .sessionId(sessionId)
                .observedPatternCount(observedPatternCount)
                .answeredQuestionCount(answeredQuestionCount)
                .activeDemoRuleCount(activeRuleCount)
                .selectedPlanSummary(planSummary)
                .chosenActionMessage(chosenActionMessage)
                .build();
    }

    // S09 플랜 선택 시 호출할 헬퍼 메서드
    @Transactional
    public void createOrActivateRuleFromPlan(AnalysisSession session, String categoryName) {
        List<DemoNotification> existing = notificationRepository.findBySession(session);
        if (existing.isEmpty()) {
            createDefaultDemoRule(session, "플랜에서 켜짐", categoryName);
        } else {
            existing.get(0).updateSettings(null, true);
        }
    }

    // S06 기본 알림 규칙 생성 (AI /notify-copy 우선 호출, 미구현 시 백엔드 동적 문구 Fallback)
    @Transactional
    public DemoNotification createDefaultDemoRule(AnalysisSession session, String sourceLabel, String category) {
        String safeCategory = (category != null && !category.isBlank()) ? category : sourceLabel;

        List<AnalysisPatternService.PatternItem> patterns = analysisPatternService.analyze(session.getId()).patterns();
        List<PatternResponse> answeredList = patternResponseRepository.findByAnalysisId(session.getId()).stream()
                .filter(r -> "ANSWERED".equals(r.getResponseStatus()))
                .toList();

        Optional<String> reasonTagOpt = resolveChosenTagLabel(answeredList);
        PatternSchedule schedule = resolveScheduleFromPattern(
                patterns.isEmpty() ? null : patterns.get(0),
                reasonTagOpt.orElse(null)
        );

        String previewMessage = requestAiNotifyCopy(session, schedule.alertTime())
                .map(AiServiceClient.NotifyCopyResponse::body)
                .filter(body -> !body.isBlank())
                .orElseGet(() -> buildFallbackPreviewMessage(reasonTagOpt, patterns, safeCategory));

        DemoNotification notification = DemoNotification.builder()
                .session(session)
                .sourceLabel(sourceLabel)
                .scheduleTitle(schedule.scheduleTitle())
                .dayCondition(schedule.dayCondition())
                .weatherCondition(schedule.weatherCondition())
                .triggerCriteria(schedule.triggerCriteria())
                .alertTime(schedule.alertTime())
                .isEnabled(true)
                .previewTagLabel("소비 패턴을 반영한 미리보기")
                .previewMessage(previewMessage)
                .build();
        return notificationRepository.save(notification);
    }

    private Optional<AiServiceClient.NotifyCopyResponse> requestAiNotifyCopy(AnalysisSession session, String scheduledTime) {
        List<AnalysisPatternService.PatternItem> patterns = analysisPatternService.analyze(session.getId()).patterns();
        if (patterns.isEmpty()) {
            return Optional.empty();
        }

        AnalysisPatternService.PatternItem top = patterns.get(0);

        List<String> causeTags = patternResponseRepository.findByAnalysisId(session.getId()).stream()
                .filter(r -> "ANSWERED".equals(r.getResponseStatus()))
                .flatMap(r -> r.getCauseTags().stream())
                .filter(tag -> !"custom".equals(tag))
                .distinct()
                .toList();

        AiServiceClient.PatternPayload patternPayload = new AiServiceClient.PatternPayload(
                "pat_" + top.patternId(),
                mapPatternType(top.type()),
                buildAiConditionMap(top),
                new AiServiceClient.ObservedDto(top.observedDays(), 13, "주"),
                top.sumKrw(),
                top.chainedSumKrw(),
                top.evidenceGrade().toLowerCase(Locale.ROOT),
                List.of(),
                List.of()
        );

        AiServiceClient.NotifyCopyRequest request = new AiServiceClient.NotifyCopyRequest(
                aiServiceClient.toAiTone(session.getPersona()),
                patternPayload,
                causeTags,
                scheduledTime
        );

        return aiServiceClient.notifyCopy(request);
    }

    private String buildFallbackPreviewMessage(
            Optional<String> reasonTagOpt,
            List<AnalysisPatternService.PatternItem> patterns,
            String safeCategory
    ) {
        if (reasonTagOpt.isPresent() && !patterns.isEmpty()) {
            AnalysisPatternService.PatternItem topPattern = patterns.get(0);
            return String.format(
                    "'%s' 원인으로 %s(%d일 관찰) 지출을 고민한다면 장바구니와 보유 내역부터 먼저 확인해 보세요.",
                    reasonTagOpt.get(), topPattern.condition(), topPattern.observedDays()
            );
        }
        if (reasonTagOpt.isPresent()) {
            return String.format(
                    "'%s' 원인으로 %s 지출을 고민한다면 장바구니와 보유 내역부터 먼저 확인해 보세요.",
                    reasonTagOpt.get(), safeCategory
            );
        }
        if (!patterns.isEmpty()) {
            AnalysisPatternService.PatternItem topPattern = patterns.get(0);
            return String.format(
                    "%s(%d일 관찰) 지출을 고민한다면 장바구니와 보유 내역부터 먼저 확인해 보세요.",
                    topPattern.condition(), topPattern.observedDays()
            );
        }
        return safeCategory + " 지출을 고민한다면 장바구니와 보유 내역부터 먼저 확인해 보세요.";
    }

    private Map<String, Object> buildAiConditionMap(AnalysisPatternService.PatternItem top) {
        Map<String, Object> map = new HashMap<>();
        String cond = top.condition() != null ? top.condition() : "";

        Integer weekday = extractWeekdayNumber(cond);
        if (weekday != null) {
            map.put("weekday", weekday);
        }
        if (cond.contains("밤 9시 이후")) {
            map.put("hour_from", 21);
            map.put("hour_to", 24);
        } else if (cond.contains("오전") || cond.contains("카페")) {
            map.put("hour_from", 7);
            map.put("hour_to", 11);
        }

        String koreanCat = "기타";
        for (String cat : List.of("배달", "카페", "택시", "구독", "쇼핑", "외식")) {
            if (cond.contains(cat)) {
                koreanCat = cat;
                break;
            }
        }
        map.put("category", aiServiceClient.toAiCategory(koreanCat));
        return map;
    }

    private String mapPatternType(String rawType) {
        if (rawType == null) return "weekday";
        return switch (rawType.toUpperCase(Locale.ROOT)) {
            case "WEEKDAY" -> "weekday";
            case "LATE_NIGHT" -> "late_night";
            case "CHAIN" -> "chain";
            case "RAIN" -> "rain";
            default -> rawType.toLowerCase(Locale.ROOT);
        };
    }

    private Integer extractWeekdayNumber(String condition) {
        if (condition.contains("월요일")) return 1;
        if (condition.contains("화요일")) return 2;
        if (condition.contains("수요일")) return 3;
        if (condition.contains("목요일")) return 4;
        if (condition.contains("금요일")) return 5;
        if (condition.contains("토요일")) return 6;
        if (condition.contains("일요일")) return 7;
        return null;
    }

    private PatternSchedule resolveScheduleFromPattern(AnalysisPatternService.PatternItem topPattern, String reasonTag) {
        String weatherCondition = "비 오는 날".equals(reasonTag) ? "비 예보 시" : "없음";
        if (topPattern == null || topPattern.condition() == null) {
            return new PatternSchedule("매일 20:00", "매일", weatherCondition, "예상 소비 21시의 한 시간 전", "20:00");
        }

        String condition = topPattern.condition();
        boolean isMorningOrCafe = condition.contains("오전") || condition.contains("아침")
                || condition.contains("카페") || condition.contains("커피");

        for (String day : List.of("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")) {
            if (condition.startsWith(day)) {
                return isMorningOrCafe
                        ? new PatternSchedule(day + " 08:30", day, weatherCondition, day + " 오전 예상 소비 30분 전", "08:30")
                        : new PatternSchedule(day + " 20:00", day, weatherCondition, day + " 예상 소비 21시의 한 시간 전", "20:00");
            }
        }

        if (isMorningOrCafe) {
            return new PatternSchedule("평일 08:30", "평일 오전", weatherCondition, condition + " 예상 시간 30분 전", "08:30");
        }
        if (condition.contains("밤 9시 이후")) {
            return new PatternSchedule("매일 20:00", "매일 밤", weatherCondition, "밤 9시 이후 예상 소비 한 시간 전", "20:00");
        }
        if (condition.contains("연쇄 소비")) {
            return new PatternSchedule("평일 18:00", "평일", weatherCondition, condition + " 예상 시간 한 시간 전", "18:00");
        }
        return new PatternSchedule("매일 20:00", "매일", weatherCondition, "예상 소비 한 시간 전", "20:00");
    }

    private record PatternSchedule(
            String scheduleTitle,
            String dayCondition,
            String weatherCondition,
            String triggerCriteria,
            String alertTime
    ) {}

    private Optional<String> resolveChosenTagLabel(List<PatternResponse> answeredList) {
        for (PatternResponse pr : answeredList) {
            if (pr.getCauseTags() != null && !pr.getCauseTags().isEmpty()) {
                String tag = pr.getCauseTags().get(0);
                if ("custom".equals(tag) && pr.getCustomReason() != null && !pr.getCustomReason().isBlank()) {
                    return Optional.of(pr.getCustomReason());
                }
                return Optional.of(mapTagCodeToKorean(tag));
            }
        }
        return Optional.empty();
    }

    private String mapTagCodeToKorean(String tag) {
        return switch (tag) {
            case "overtime" -> "야근";
            case "rain" -> "비 오는 날";
            case "stress" -> "스트레스";
            case "habit" -> "습관";
            case "no_specific_reason" -> "특별한 이유 없음";
            default -> tag;
        };
    }
}