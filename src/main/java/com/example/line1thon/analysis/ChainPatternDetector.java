
package com.example.line1thon.analysis;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChainPatternDetector {

    // F08: 2시간 이내에 이어지는 소비 패턴 탐지
    public List<ChainPattern> detectChainPatterns(
            List<AnalysisTransaction> transactions) {

        if (transactions == null || transactions.isEmpty()) {
            return List.of();
        }

        // 1. 유효한 지출 거래만 날짜별로 묶기
        Map<LocalDate, List<AnalysisTransaction>> byDate =
                transactions.stream()
                        .filter(t -> t != null
                                && t.transactionId() != null
                                && t.date() != null
                                && t.time() != null
                                && t.amountKrw() > 0
                                && "out".equalsIgnoreCase(t.direction()))
                        .filter(t -> t.category() != null
                                && !t.category().isBlank())
                        .collect(Collectors.groupingBy(
                                AnalysisTransaction::date
                        ));

        Map<ChainKey, List<ChainPair>> chains =
                new LinkedHashMap<>();

        // 2. 같은 날짜의 거래를 시간순으로 정렬
        for (var entry : byDate.entrySet()) {

            List<AnalysisTransaction> daily =
                    new ArrayList<>(entry.getValue());

            daily.sort(Comparator.comparing(
                    AnalysisTransaction::time
            ));

            // 3. 두 거래의 시간 차이 확인
            for (int i = 0; i < daily.size(); i++) {
                for (int j = i + 1; j < daily.size(); j++) {

                    AnalysisTransaction first = daily.get(i);
                    AnalysisTransaction second = daily.get(j);

                    long seconds = Duration.between(
                            first.time(),
                            second.time()
                    ).toSeconds();

                    // 정확히 0분인 경우는 제외
                    if (seconds <= 0) {
                        continue;
                    }

                    // 120분 초과는 제외
                    if (seconds > 120 * 60) {
                        break;
                    }

                    // 동일한 거래 ID는 연결하지 않음
                    if (first.transactionId().equals(
                            second.transactionId())) {
                        continue;
                    }

                    ChainKey key = new ChainKey(
                            getCategory(first),
                            getCategory(second)
                    );

                    chains.computeIfAbsent(
                            key, k -> new ArrayList<>()
                    ).add(new ChainPair(
                            entry.getKey(), first, second
                    ));
                }
            }
        }

        List<ChainPattern> results = new ArrayList<>();

        // 4. 서로 다른 날짜에 2일 이상 반복됐는지 확인
        for (var entry : chains.entrySet()) {

            List<ChainPair> pairs = entry.getValue();

            int observedDays = (int) pairs.stream()
                    .map(ChainPair::date)
                    .distinct()
                    .count();

            if (observedDays < 2) {
                continue;
            }

            // 5. 근거 거래 ID와 지출 금액 수집
            // 같은 거래가 여러 쌍에 포함돼도 중복 합산 방지
            Map<Long, Long> evidence = new LinkedHashMap<>();

            for (ChainPair pair : pairs) {
                evidence.putIfAbsent(
                        pair.first().transactionId(),
                        pair.first().amountKrw()
                );

                evidence.putIfAbsent(
                        pair.second().transactionId(),
                        pair.second().amountKrw()
                );
            }

            long sumKrw = evidence.values().stream()
                    .mapToLong(Long::longValue)
                    .sum();

            results.add(new ChainPattern(
                    PatternType.CHAIN,
                    entry.getKey().fromCategory(),
                    entry.getKey().toCategory(),
                    observedDays,
                    sumKrw,
                    EvidenceGrade.NORMAL,
                    new ArrayList<>(evidence.keySet())
            ));
        }

        // 6. 지출 합계가 큰 순서대로 정렬
        results.sort(
                Comparator.comparingLong(
                        ChainPattern::sumKrw
                ).reversed()
        );

        return results;
    }

    // 세부 카테고리가 있으면 우선 사용
    private String getCategory(AnalysisTransaction t) {
        if (t.subcategory() != null
                && !t.subcategory().isBlank()) {
            return t.subcategory();
        }
        return t.category();
    }

    // 카테고리 연결 정보
    private record ChainKey(
            String fromCategory,
            String toCategory
    ) {}

    // 실제 연결된 두 거래
    private record ChainPair(
            LocalDate date,
            AnalysisTransaction first,
            AnalysisTransaction second
    ) {}

    // 탐지된 연쇄 소비 결과
    public record ChainPattern(
            PatternType type,
            String fromCategory,
            String toCategory,
            int observedDays,
            long sumKrw,
            EvidenceGrade evidenceGrade,
            List<Long> evidenceTransactionIds
    ) {}
}
