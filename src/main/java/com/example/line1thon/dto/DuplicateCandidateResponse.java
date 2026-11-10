package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class DuplicateCandidateResponse {
    private int currentGroupIndex;
    private int totalGroupsCount;
    private String groupTitle;
    private Long groupId;
    private String defaultSelection;
    private String defaultSelectionDesc;
    private List<DuplicateItemDto> items;

    @Getter
    @Builder
    public static class DuplicateItemDto {
        private Long transactionId;
        private String sourceLabel; // 예: "8월 캡처 A · 배민"
        private String date;        // 예: "08.27"
        private String time;        // 예: "22:05"
        private String direction;   // "출금"
        private long amount;        // 23500
    }
}