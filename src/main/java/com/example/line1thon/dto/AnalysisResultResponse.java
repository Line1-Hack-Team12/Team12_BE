package com.example.line1thon.dto;

import java.util.List;

public record AnalysisResultResponse(
        Long analysisId,
        Long snapshotId,
        String status,
        Summary summary,
        List<PatternCard> cards,
        String reasonCode
) {

    // S05 상단 소비 요약
    public record Summary(
            int transactionCount,
            long totalExpenseKrw,
            String overallComment
    ) {}

    // S05 소비 패턴 카드
    public record PatternCard(
            Long patternId,
            String type,
            String condition,
            int observedDays,
            long sumKrw,
            long chainedSumKrw,
            String evidenceGrade,
            String observation,
            String hypothesisQuestion,
            String action
    ) {}
}
