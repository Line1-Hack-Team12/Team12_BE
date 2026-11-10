package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SessionConfirmResponse {
    private Long sessionId;
    private Long snapshotId;
    private String status;
    private String nextStepUrl;
}