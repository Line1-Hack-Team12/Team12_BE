package com.example.line1thon.dto;

import com.example.line1thon.entity.AnalysisSession;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SessionDetailResponse {
    private Long sessionId;
    private AnalysisSession.SessionStatus status;
    private int totalCount;
    private int completedCount;
    private int progressPercentage;
    private int currentStep;
    private List<StepDto> steps;
    private List<FileStatusDto> fileStatuses;
    private List<FailedFileDto> failedFiles;
    private String guideMessage;

    @Getter
    @Builder
    public static class StepDto {
        private int stepNumber;
        private String name;
        private String status; // WAITING, IN_PROGRESS, COMPLETED
    }

    @Getter
    @Builder
    public static class FileStatusDto {
        private Long fileId;
        private String fileName;
        private String statusText;
    }

    @Getter
    @Builder
    public static class FailedFileDto {
        private Long fileId;
        private String fileName;
        private String slotMonth;
        private String reason;
    }
}