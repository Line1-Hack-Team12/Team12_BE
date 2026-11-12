package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ReportResponse {
    private Long sessionId;
    private HeaderSummaryDto headerSummary;
    private List<CategoryRatioDto> categoryRatios;
    private MonthlyTrendDto monthlyTrend;
    private ObservationActionDto observationAndAction;
    private SubscriptionSummaryDto subscriptions;
    private String noticeMessage;

    @Getter
    @Builder
    public static class HeaderSummaryDto {
        private String periodDescription;
        private Long totalExpense;
        private String highlightMessage;
    }

    @Getter
    @Builder
    public static class CategoryRatioDto {
        private String category;
        private Long amount;
        private Integer percentage;
    }

    @Getter
    @Builder
    public static class MonthlyTrendDto {
        private String changeRateLabel;
        private List<MonthlyAmountDto> monthlyAmounts;
    }

    @Getter
    @Builder
    public static class MonthlyAmountDto {
        private String month;
        private Long amount;
        private Boolean isLatest;
    }

    @Getter
    @Builder
    public static class ObservationActionDto {
        private String observedPattern;
        private String recommendedAction;
    }

    // 이 부분이 정기결제 요약 DTO입니다!
    @Getter
    @Builder
    public static class SubscriptionSummaryDto {
        private List<SubscriptionItemDto> items;
        private Long monthlyTotal;
        private Long yearlyTotal;
    }

    @Getter
    @Builder
    public static class SubscriptionItemDto {
        private String serviceName;
        private String priceHistory;
        private Boolean isPriceIncreased;
        private Long monthlyAmount;
    }
}