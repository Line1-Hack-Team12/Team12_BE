package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ReviewSummaryResponse {
    private Long sessionId;
    private SummaryDto summary;
    private LowConfidenceQueueDto lowConfidenceQueue;
    private DuplicateSummaryDto duplicateSummary;
    private boolean canConfirm; // 체크박스 활성화 여부

    @Getter
    @Builder
    public static class SummaryDto {
        private String slotDescription;
        private String statusTag;
        private int totalCount;
        private long totalExpense;
    }

    @Getter
    @Builder
    public static class LowConfidenceQueueDto {
        private int count;
        private String badge;
        private List<LowConfidenceItemDto> items;
    }

    @Getter
    @Builder
    public static class LowConfidenceItemDto {
        private Long transactionId;
        private String storeName;
        private String date; // "09.07"
        private long originalAmount;
        private long suggestedAmount;
        private boolean isConfirmed;
    }

    @Getter
    @Builder
    public static class DuplicateSummaryDto {
        private String message;
        private int candidateCount;
    }
}