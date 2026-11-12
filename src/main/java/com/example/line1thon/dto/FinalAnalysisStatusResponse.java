package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FinalAnalysisStatusResponse {
    private Long sessionId;
    private Long snapshotId;
    private String status; // ANALYZING, COMPLETED
    private String title;
    private String subTitle;
    private int currentStep;
    private List<StepInfo> steps;
    private String guideMessage;

    @Getter
    @Builder
    public static class StepInfo {
        private int stepNumber;
        private String name;
        private String detail;
        private String status; // COMPLETED, IN_PROGRESS, WAITING
    }
}