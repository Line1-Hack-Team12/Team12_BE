package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class NotificationResponse {
    private Long sessionId;
    private List<NotificationRuleDto> notifications;
    private String policyFooter;

    @Getter
    @Builder
    public static class NotificationRuleDto {
        private Long notificationId;
        private String sourceLabel;
        private String scheduleTitle;
        private String dayCondition;
        private String weatherCondition;
        private String triggerCriteria;
        private String alertTime;
        private Boolean isEnabled;
        private String previewTagLabel;
        private String previewMessage;
        private String disclaimerNotice;
    }
}