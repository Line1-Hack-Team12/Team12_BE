package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TransactionDetailDto {
    private Long transactionId;
    private String date;        // 예: "2026-09-07"
    private String time;        // 예: "21:15"
    private String storeName;   // 예: "GS25 역삼점"
    private long amount;        // 예: 3900
    private String direction;   // "출금"
    private String type;        // "지출"
    private String category;    // "생활 / 편의점"
    private boolean isExcluded; // 제외 여부
}