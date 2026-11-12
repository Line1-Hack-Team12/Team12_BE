package com.example.line1thon.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateTransactionRequest {
    private String date;
    private String time;
    private String storeName;
    private Long amount;
    private String direction;
    private String type;
    private String category;
    private String action; // SAVE_AND_CONFIRM, KEEP_AND_CONFIRM, EXCLUDE, RESTORE
}