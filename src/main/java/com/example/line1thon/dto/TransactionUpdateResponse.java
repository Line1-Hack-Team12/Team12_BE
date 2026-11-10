package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TransactionUpdateResponse {
    private Long transactionId;
    private String action;
    private String storeName;
    private long amount;
    private boolean isExcluded;
    private String statusLabel;
    private UpdatedSummary sessionSummary;

    @Getter
    @Builder
    public static class UpdatedSummary {
        private int totalCount;
        private long totalExpense;
    }
}