package com.example.line1thon.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SelectSavingsPlanRequest {
    private Long targetAmount;
    private String planCode;
    private String planTitle;
    private Long monthlySavedAmount;
    private Boolean enableDemoNotification;
}