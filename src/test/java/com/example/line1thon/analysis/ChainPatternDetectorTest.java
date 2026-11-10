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
                        "잡화점", 15000, "shopping", null, "out")
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
        assertEquals(2, pattern.observedDays());
        assertEquals(46000L, pattern.sumKrw());

        assertEquals(
                Set.of(1L, 2L, 3L, 4L),
                Set.copyOf(pattern.evidenceTransactionIds())
        );
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
