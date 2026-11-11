
package com.example.line1thon.dto;

import java.util.List;

public record PatternDetailResponse(
        Long analysisId,
        Long patternId,
        String type,
        String condition,
        int observedDays,
        long sumKrw,
        long chainedSumKrw,
        String evidenceGrade,
        String observation,
        String hypothesisQuestion,
        String action,
        List<EvidenceTransaction> evidenceTransactions,
        List<String> causeTags,
        String responseStatus
) {

    // 소비 패턴의 근거가 되는 거래 내역
    public record EvidenceTransaction(
            Long transactionId,
            String date,
            String time,
            String merchant,
            long amountKrw
    ) {}
}
