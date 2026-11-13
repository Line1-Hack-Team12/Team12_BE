package com.example.line1thon.analysis;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalTime;

public class PatternDetector {

    /** 패턴으로 인정하는 최소 관찰 일수(서로 다른 날짜). 2일은 우연일 수 있어 3일부터. */
    public static final int MIN_OBSERVED_DAYS = 3;

    // F08: 특정 요일에 반복되는 소비 패턴 탐지
    public List<DetectedPattern> detectWeekdayPatterns(
            List<AnalysisTransaction> transactions) {

        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        // 1. 지출 거래만 골라 요일과 카테고리별로 묶기
        Map<DayCategory, List<AnalysisTransaction>> groups =
                transactions.stream()
                        .filter(t -> t != null
                                && t.transactionId() != null
                                && t.date() != null
                                && t.amountKrw() > 0
                                && "out".equalsIgnoreCase(t.direction()))
                        .filter(t -> t.category() != null
                                && !t.category().isBlank())
                        .collect(Collectors.groupingBy(t ->
                                new DayCategory(
                                        t.date().getDayOfWeek(),
                                        t.subcategory() != null
                                                && !t.subcategory().isBlank()
                                                ? t.subcategory()
                                                : t.category()
                                )
                        ));

        List<DetectedPattern> results = new ArrayList<>();

        // 2. 서로 다른 날짜에서 MIN_OBSERVED_DAYS 일 이상 관찰됐는지 확인
        for (var entry : groups.entrySet()) {
            List<AnalysisTransaction> group = entry.getValue();

            int observedDays = (int) group.stream()
                    .map(AnalysisTransaction::date)
                    .distinct()
                    .count();

            if (observedDays < MIN_OBSERVED_DAYS) {
                continue;
            }

            // 3. 지출 합계와 근거 거래 ID 계산
            long sumKrw = group.stream()
                    .mapToLong(AnalysisTransaction::amountKrw)
                    .sum();

            List<Long> evidenceIds = group.stream()
                    .map(AnalysisTransaction::transactionId)
                    .distinct()
                    .toList();

            results.add(new DetectedPattern(
                    PatternType.WEEKDAY,
                    entry.getKey().day(),
                    entry.getKey().category(),
                    observedDays,
                    sumKrw,
                    EvidenceGrade.NORMAL,
                    evidenceIds
            ));
        }

        // 4. 지출 합계가 큰 패턴부터 정렬
        results.sort(
                Comparator.comparingLong(
                        DetectedPattern::sumKrw
                ).reversed()
        );

        return results;
    }


    // F08: 밤 9시 이후 반복되는 소비 패턴 탐지
    public List<DetectedPattern> detectLateNightPatterns(
            List<AnalysisTransaction> transactions) {

        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        // 1. 밤 9시 이후 지출만 추출하고 카테고리별 분류
        Map<String, List<AnalysisTransaction>> groups =
                transactions.stream()
                        .filter(t -> t != null
                                && t.transactionId() != null
                                && t.date() != null
                                && t.time() != null
                                && t.amountKrw() > 0
                                && "out".equalsIgnoreCase(t.direction()))
                        .filter(t -> !t.time().isBefore(
                                LocalTime.of(21, 0)))
                        .filter(t -> t.category() != null
                                && !t.category().isBlank())
                        .collect(Collectors.groupingBy(t ->
                                t.subcategory() != null
                                        && !t.subcategory().isBlank()
                                        ? t.subcategory()
                                        : t.category()
                        ));

        List<DetectedPattern> results = new ArrayList<>();

        // 2. 서로 다른 날짜에 MIN_OBSERVED_DAYS 일 이상 소비했는지 확인
        for (var entry : groups.entrySet()) {
            List<AnalysisTransaction> group = entry.getValue();

            int observedDays = (int) group.stream()
                    .map(AnalysisTransaction::date)
                    .distinct()
                    .count();

            if (observedDays < MIN_OBSERVED_DAYS) {
                continue;
            }

            // 3. 지출 합계 계산
            long sumKrw = group.stream()
                    .mapToLong(AnalysisTransaction::amountKrw)
                    .sum();

            // 4. 근거 거래 ID 수집
            List<Long> evidenceIds = group.stream()
                    .map(AnalysisTransaction::transactionId)
                    .distinct()
                    .toList();

            results.add(new DetectedPattern(
                    PatternType.LATE_NIGHT,
                    null,
                    entry.getKey(),
                    observedDays,
                    sumKrw,
                    EvidenceGrade.NORMAL,
                    evidenceIds
            ));
        }

        // 5. 지출 합계가 큰 순서대로 정렬
        results.sort(
                Comparator.comparingLong(
                        DetectedPattern::sumKrw
                ).reversed()
        );

        return results;
    }

    private record DayCategory(
            DayOfWeek day,
            String category
    ) {}

    public record DetectedPattern(
            PatternType type,
            DayOfWeek dayOfWeek,
            String category,
            int observedDays,
            long sumKrw,
            EvidenceGrade evidenceGrade,
            List<Long> evidenceTransactionIds
    ) {}
}
