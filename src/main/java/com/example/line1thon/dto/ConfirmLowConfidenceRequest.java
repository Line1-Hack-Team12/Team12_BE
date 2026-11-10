package com.example.line1thon.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class ConfirmLowConfidenceRequest {
    private List<Item> confirmedItems;

    @Getter
    @NoArgsConstructor
    public static class Item {
        private Long transactionId;
        private boolean applySuggested;
    }
}