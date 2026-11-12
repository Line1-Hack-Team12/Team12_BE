package com.example.line1thon.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateNotificationRequest {
    private String alertTime;
    private Boolean isEnabled;
}