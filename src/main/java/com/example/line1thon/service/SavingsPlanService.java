package com.example.line1thon.service;

import com.example.line1thon.dto.SavingsPlanResponse;
import com.example.line1thon.dto.SelectSavingsPlanRequest;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.SelectedSavingsPlan;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.SelectedSavingsPlanRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavingsPlanService {

    private final AnalysisSessionRepository sessionRepository;
    private final TransactionRepository transactionRepository;
    private final SelectedSavingsPlanRepository selectedPlanRepository;
    private final NotificationAndSummaryService notificationAndSummaryService;

    public SavingsPlanResponse simulatePlans(Long sessionId, Long targetAmount, List<String> excludeCategories) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        long safeTarget = (targetAmount == null) ? 50000L : Math.max(10000L, Math.min(500000L, targetAmount));
        Set<String> excluded = (excludeCategories == null)
                ? Collections.emptySet()
                : excludeCategories.stream().map(String::trim).collect(Collectors.toSet());

        List<Transaction> expenses = transactionRepository.findBySession(session).stream()
                .filter(Transaction::isCountedAsExpense)
                .toList();

        // 카테고리별 지출액 집계 ("expense"인 경우 가맹점명으로 세부 카테고리 추론)
        Map<String, Long> categoryMonthlyAvg = expenses.stream()
                .collect(Collectors.groupingBy(
                        this::resolveCategory,
                        Collectors.summingLong(Transaction::getAmount)
                ));

        // 제외 카테고리 및 "기타", "구독"을 뺀 일반 지출 상위 카테고리 리스트
        var reducibleCategories = categoryMonthlyAvg.entrySet().stream()
                .filter(e -> !excluded.contains(e.getKey()))
                .filter(e -> !e.getKey().equals("기타") && !e.getKey().equals("구독"))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .toList();

        SavingsPlanResponse.PlanActionItemDto subItem = detectTopSubscriptionItem(expenses);

        List<SavingsPlanResponse.PlanOptionDto> plans = new ArrayList<>();

        // 플랜 A: 1순위 절감 카테고리 + 구독 해지 조합
        if (!reducibleCategories.isEmpty()) {
            Map.Entry<String, Long> top1 = reducibleCategories.get(0);
            long maxSave1 = Math.round(top1.getValue() * 0.5);
            plans.add(buildPlanOption("PLAN_A", "플랜 A · " + top1.getKey() + " 중심", safeTarget, top1.getKey(), maxSave1, subItem));
        }

        // 플랜 B: 2순위 절감 카테고리 + 구독 해지 조합
        if (reducibleCategories.size() >= 2) {
            Map.Entry<String, Long> top2 = reducibleCategories.get(1);
            long maxSave2 = Math.round(top2.getValue() * 0.5);
            plans.add(buildPlanOption("PLAN_B", "플랜 B · " + top2.getKey() + " 중심", safeTarget, top2.getKey(), maxSave2, subItem));
        } else if (reducibleCategories.size() == 1) {
            Map.Entry<String, Long> top1 = reducibleCategories.get(0);
            long maxSaveB = Math.round(top1.getValue() * 0.6);
            plans.add(buildPlanOption("PLAN_B", "플랜 B · " + top1.getKey() + " 집중 감축", safeTarget, top1.getKey(), maxSaveB, subItem));
        }

        return SavingsPlanResponse.builder()
                .sessionId(sessionId)
                .targetAmount(safeTarget)
                .minTargetAmount(10000L)
                .maxTargetAmount(500000L)
                .availableExcludeCategories(List.of("배달", "택시", "카페"))
                .excludedCategories(new ArrayList<>(excluded))
                .plans(plans)
                .guideNotice("절감액은 월 지출의 최대 60% 상한으로 계산했어요. 선택하면 해당 패턴의 demo 알림이 켜져요.")
                .build();
    }

    @Transactional
    public Map<String, Object> selectPlan(Long sessionId, SelectSavingsPlanRequest request) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        boolean enableNoti = request.getEnableDemoNotification() == null || request.getEnableDemoNotification();

        SelectedSavingsPlan plan = selectedPlanRepository.findBySession(session)
                .orElseGet(() -> SelectedSavingsPlan.builder()
                        .session(session)
                        .targetAmount(request.getTargetAmount())
                        .planCode(request.getPlanCode())
                        .planTitle(request.getPlanTitle())
                        .monthlySavedAmount(request.getMonthlySavedAmount())
                        .isDemoNotificationEnabled(enableNoti)
                        .selectedAt(LocalDateTime.now())
                        .build());

        plan.updatePlan(
                request.getTargetAmount(),
                request.getPlanCode(),
                request.getPlanTitle(),
                request.getMonthlySavedAmount(),
                enableNoti
        );
        selectedPlanRepository.save(plan);

        // S06 알림 규칙 자동 생성/활성화 연동
        if (enableNoti) {
            notificationAndSummaryService.createDefaultDemoRule(
                    session,
                    request.getPlanTitle(),
                    request.getPlanTitle()
            );
        }

        return Map.of(
                "sessionId", sessionId,
                "selectedPlanCode", plan.getPlanCode(),
                "planTitle", plan.getPlanTitle(),
                "monthlySavedAmount", plan.getMonthlySavedAmount(),
                "isDemoNotificationEnabled", plan.getIsDemoNotificationEnabled(),
                "message", "적금 플랜이 저장되고 demo 알림이 설정되었습니다."
        );
    }

    private SavingsPlanResponse.PlanOptionDto buildPlanOption(
            String code, String title, long targetAmount,
            String category, long categorySave,
            SavingsPlanResponse.PlanActionItemDto subItem) {

        List<SavingsPlanResponse.PlanActionItemDto> items = new ArrayList<>();
        items.add(SavingsPlanResponse.PlanActionItemDto.builder()
                .category(category)
                .title(category + " 소비 줄이기")
                .subDescription("빈도 절반으로 줄이기")
                .savedAmount(categorySave)
                .isSubscription(false)
                .build());

        long totalSaved = categorySave;
        if (subItem != null) {
            items.add(subItem);
            totalSaved += subItem.getSavedAmount();
        }

        return SavingsPlanResponse.PlanOptionDto.builder()
                .planCode(code)
                .title(title)
                .isGoalAchieved(totalSaved >= targetAmount)
                .monthlyTotalSaved(totalSaved)
                .items(items)
                .build();
    }

    private SavingsPlanResponse.PlanActionItemDto detectTopSubscriptionItem(List<Transaction> expenses) {
        List<String> subKeywords = List.of("유튜브", "youtube", "넷플릭스", "netflix", "쿠팡와우", "멜론", "스포티파이", "티빙", "디즈니", "구독", "멤버십");
        return expenses.stream()
                .filter(tx -> tx.getStoreName() != null && subKeywords.stream().anyMatch(kw -> tx.getStoreName().toLowerCase().contains(kw)))
                .max(Comparator.comparingLong(Transaction::getAmount))
                .map(tx -> SavingsPlanResponse.PlanActionItemDto.builder()
                        .category("구독")
                        .title("구독 해지 제안")
                        .subDescription(tx.getStoreName())
                        .savedAmount(tx.getAmount())
                        .isSubscription(true)
                        .build())
                .orElse(null);
    }

    private String resolveCategory(Transaction tx) {
        String kind = tx.getKind();
        if (kind != null && !kind.isBlank()
                && !kind.equalsIgnoreCase("expense")
                && !kind.equalsIgnoreCase("unknown")) {
            return kind;
        }

        String store = tx.getStoreName() != null ? tx.getStoreName().toLowerCase() : "";
        if (store.contains("배달의민족") || store.contains("배민") || store.contains("쿠팡이츠") || store.contains("요기요")) {
            return "배달";
        }
        if (store.contains("택시") || store.contains("카카오t") || store.contains("우버") || store.contains("타다")) {
            return "택시";
        }
        if (store.contains("스타벅스") || store.contains("메가커피") || store.contains("투썸")
                || store.contains("컴포즈") || store.contains("이디야") || store.contains("빽다방") || store.contains("카페")) {
            return "카페";
        }
        if (store.contains("youtube") || store.contains("유튜브") || store.contains("넷플릭스")
                || store.contains("netflix") || store.contains("멜론") || store.contains("티빙")) {
            return "구독";
        }
        if (store.contains("올리브영") || store.contains("무신사") || store.contains("다이소") || store.contains("쇼핑")) {
            return "쇼핑";
        }
        return "기타";
    }
}