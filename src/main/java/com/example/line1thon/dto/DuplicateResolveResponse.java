package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DuplicateResolveResponse {
    private Long groupId;
    private String resolvedAction;
    private int remainingDuplicateGroups;
    private int totalCount;
    private long totalExpense;
}