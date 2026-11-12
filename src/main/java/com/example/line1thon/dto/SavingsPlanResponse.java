package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SavingsPlanResponse {
    private Long sessionId;
    private Long targetAmount;
    private Long minTargetAmount;
    private Long maxTargetAmount;
    private List<String> availableExcludeCategories;
    private List<String> excludedCategories;
    private List<PlanOptionDto> plans;
    private String guideNotice;

    @Getter
    @Builder
    public static class PlanOptionDto {
        private String planCode;         // "PLAN_A", "PLAN_B"
        private String title;            // "플랜 A · 배달 중심"
        private Boolean isGoalAchieved;  // 목표 달성 여부 배지
        private Long monthlyTotalSaved;  // 월 합계 절감액
        private List<PlanActionItemDto> items;
    }

    @Getter
    @Builder
    public static class PlanActionItemDto {
        private String category;         // "배달", "택시", "구독" 등
        private String title;            // "목요일 밤 배달"
        private String subDescription;   // "월 4회 → 2회" 또는 "유튜브 프리미엄"
        private Long savedAmount;        // -42000 (양수로 내려주고 프론트에서 - 표시)
        private Boolean isSubscription;  // 구독 해지 제안 여부 (알림 미생성 구분용)
    }
}