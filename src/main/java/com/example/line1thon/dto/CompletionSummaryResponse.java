package com.example.line1thon.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CompletionSummaryResponse {
    private Long sessionId;
    private Integer observedPatternCount;   // 관찰한 패턴 수
    private Integer answeredQuestionCount;  // 답한 질문(태그) 수
    private Long activeDemoRuleCount;       // 플랜에서 켜진 demo 규칙 수
    private String selectedPlanSummary;     // 예: "A - 월 56,900원 제안"
    private String chosenActionMessage;     // 이번에 고른 행동 요약 문구
}