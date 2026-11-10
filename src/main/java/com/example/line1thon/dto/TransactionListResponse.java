package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class TransactionListResponse {
    private int totalCount;
    private long totalExpense;
    private String rangeDescription;
    private List<TransactionItemDto> transactions;

    @Getter
    @Builder
    public static class TransactionItemDto {
        private Long transactionId;
        private String date;
        private String time;
        private String storeName;
        private long amount;
        private String direction;
        private String category;
        private boolean isExcluded;
        private String statusLabel; // "포함" 또는 "제외됨"
    }
}