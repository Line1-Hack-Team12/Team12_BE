package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class QueueConfirmResponse {
    private Long sessionId;
    private boolean canConfirm;
    private int remainingReviewCount;
    private long updatedTotalExpense;
}