package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReviewCompletedResponse {
    private Long sessionId;
    private String slotDescription;
    private String statusTag;
    private int totalCount;
    private long totalExpense;
    private ReviewedItemCounts reviewedSummary;
    private boolean isChecked;
    private String guideMessage;

    @Getter
    @Builder
    public static class ReviewedItemCounts {
        private int lowConfidenceCount;
        private int duplicateCount;
        private int requiredValueErrorCount;
    }
}