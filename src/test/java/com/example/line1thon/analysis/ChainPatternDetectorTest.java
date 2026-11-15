package com.example.line1thon.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChainPatternDetectorTest {

    @Test
    void 카페에서_쇼핑으로_이어지는_반복_소비_탐지() {

        // 1. 가상의 소비 내역 만들기
        List<AnalysisTransaction> transactions = List.of(
                // 10월 1일: 카페 → 60분 뒤 쇼핑
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(14, 0),
                        "스타벅스", 5000, "cafe", null, "out"),

                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(15, 0),
                        "옷가게", 20000, "shopping", null, "out"),

                // 10월 8일: 카페 → 90분 뒤 쇼핑
                new AnalysisTransaction(
                        3L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(13, 0),
                        "메가커피", 6000, "cafe", null, "out"),

                new AnalysisTransaction(
                        4L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(14, 30),
                        "잡화점", 15000, "shopping", null, "out"),

                // 10월 15일: 카페 → 45분 뒤 쇼핑
                new AnalysisTransaction(
                        5L, LocalDate.of(2026, 10, 15),
                        LocalTime.of(12, 0),
                        "투썸", 4500, "cafe", null, "out"),

                new AnalysisTransaction(
                        6L, LocalDate.of(2026, 10, 15),
                        LocalTime.of(12, 45),
                        "다이소", 8000, "shopping", null, "out")
        );

        // 2. 연쇄 소비 탐지 실행
        ChainPatternDetector detector =
                new ChainPatternDetector();

        var results = detector.detectChainPatterns(transactions);

        // 3. 결과 검증
        assertEquals(1, results.size());

        var pattern = results.get(0);

        assertEquals(PatternType.CHAIN, pattern.type());
        assertEquals("cafe", pattern.fromCategory());
        assertEquals("shopping", pattern.toCategory());
        assertEquals(3, pattern.observedDays());
        // 직접 비용 = 카페 3건(5000+6000+4500), 후속 비용 = 쇼핑 3건(20000+15000+8000)
        assertEquals(15500L, pattern.sumKrw());
        assertEquals(43000L, pattern.chainedSumKrw());
        assertEquals(58500L, pattern.sumKrw() + pattern.chainedSumKrw());

        assertEquals(
                Set.of(1L, 2L, 3L, 4L, 5L, 6L),
                Set.copyOf(pattern.evidenceTransactionIds())
        );
    }

    @Test
    void 같은_앞_결제에_뒤_결제가_여러_개_붙어도_직접_비용은_한_번만_더한다() {

        List<AnalysisTransaction> transactions = List.of(
                // 10월 1일: 주점 → 택시 → (30분 뒤) 택시 한 번 더
                new AnalysisTransaction(1L, LocalDate.of(2026, 10, 1), LocalTime.of(22, 0), "포차", 40000, "bar", null, "out"),
                new AnalysisTransaction(2L, LocalDate.of(2026, 10, 1), LocalTime.of(23, 0), "택시", 12000, "taxi", null, "out"),
                new AnalysisTransaction(3L, LocalDate.of(2026, 10, 1), LocalTime.of(23, 30), "택시", 8000, "taxi", null, "out"),
                // 10월 8일, 10월 15일: 주점 → 택시
                new AnalysisTransaction(4L, LocalDate.of(2026, 10, 8), LocalTime.of(22, 0), "포차", 50000, "bar", null, "out"),
                new AnalysisTransaction(5L, LocalDate.of(2026, 10, 8), LocalTime.of(23, 10), "택시", 15000, "taxi", null, "out"),
                new AnalysisTransaction(6L, LocalDate.of(2026, 10, 15), LocalTime.of(22, 0), "포차", 45000, "bar", null, "out"),
                new AnalysisTransaction(7L, LocalDate.of(2026, 10, 15), LocalTime.of(23, 20), "택시", 13000, "taxi", null, "out")
        );

        var results = new ChainPatternDetector().detectChainPatterns(transactions);
        var bar2taxi = results.stream()
                .filter(p -> p.fromCategory().equals("bar") && p.toCategory().equals("taxi"))
                .findFirst().orElseThrow();

        assertEquals(3, bar2taxi.observedDays());
        assertEquals(135000L, bar2taxi.sumKrw());          // 40000 + 50000 + 45000 (1번 거래는 한 번만)
        assertEquals(48000L, bar2taxi.chainedSumKrw());    // 12000 + 8000 + 15000 + 13000
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), Set.copyOf(bar2taxi.evidenceTransactionIds()));
    }

    @Test
    void 서로_다른_날_2일만_이어지면_패턴이_아니다() {

        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(14, 0),
                        "스타벅스", 5000, "cafe", null, "out"),
                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(15, 0),
                        "옷가게", 20000, "shopping", null, "out"),
                new AnalysisTransaction(
                        3L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(13, 0),
                        "메가커피", 6000, "cafe", null, "out"),
                new AnalysisTransaction(
                        4L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(14, 30),
                        "잡화점", 15000, "shopping", null, "out")
        );

        var results = new ChainPatternDetector().detectChainPatterns(transactions);

        // 최소 관찰 일수(3일) 미만이면 패턴으로 보지 않는다
        assertTrue(results.isEmpty());
    }

    @Test
    void 두_시간을_초과한_소비는_연결하지_않는다() {

        List<AnalysisTransaction> transactions = List.of(
                // 10월 1일: 2시간 1분 차이
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(10, 0),
                        "스타벅스", 5000, "cafe", null, "out"),

                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(12, 1),
                        "옷가게", 20000, "shopping", null, "out"),

                // 10월 8일: 2시간 1분 차이
                new AnalysisTransaction(
                        3L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(13, 0),
                        "메가커피", 6000, "cafe", null, "out"),

                new AnalysisTransaction(
                        4L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(15, 1),
                        "잡화점", 15000, "shopping", null, "out")
        );

        ChainPatternDetector detector =
                new ChainPatternDetector();

        var results = detector.detectChainPatterns(transactions);

        // 120분 초과이므로 연쇄 소비 패턴이 없어야 함
        assertTrue(results.isEmpty());
    }

}
