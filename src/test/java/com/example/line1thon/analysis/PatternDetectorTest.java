package com.example.line1thon.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternDetectorTest {

    @Test
    void 목요일_배달_반복_패턴_탐지() {

        // 1. 가상의 소비 내역 만들기
        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(22, 0),
                        "배민", 20000, "delivery", null, "out"),

                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(21, 30),
                        "쿠팡이츠", 15000, "delivery", null, "out"),

                new AnalysisTransaction(
                        3L, LocalDate.of(2026, 10, 15),
                        LocalTime.of(22, 15),
                        "배민", 25000, "delivery", null, "out"),

                new AnalysisTransaction(
                        4L, LocalDate.of(2026, 10, 16),
                        LocalTime.of(14, 0),
                        "스타벅스", 5000, "cafe", null, "out")
        );

        // 2. 소비 패턴 분석 실행
        PatternDetector detector = new PatternDetector();

        List<PatternDetector.DetectedPattern> results =
                detector.detectWeekdayPatterns(transactions);

        // 3. 분석 결과 검증
        assertEquals(1, results.size());

        var pattern = results.get(0);

        assertEquals(PatternType.WEEKDAY, pattern.type());
        assertEquals("delivery", pattern.category());
        assertEquals(3, pattern.observedDays());
        assertEquals(60000L, pattern.sumKrw());
        assertEquals(List.of(1L, 2L, 3L),
                pattern.evidenceTransactionIds());
    }

    @Test
    void 같은_날짜_결제는_반복_패턴이_아니다() {

        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(21, 0),
                        "배민", 20000, "delivery", null, "out"),

                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(22, 0),
                        "쿠팡이츠", 15000, "delivery", null, "out")
        );

        PatternDetector detector = new PatternDetector();

        var results = detector.detectWeekdayPatterns(transactions);

        assertTrue(results.isEmpty());
    }

    @Test
    void 밤_9시_이후_반복_소비_패턴_탐지() {

        List<AnalysisTransaction> transactions = List.of(
                // 밤 9시 이후 소비
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(22, 0),
                        "배민", 20000, "delivery", null, "out"),

                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 3),
                        LocalTime.of(21, 30),
                        "쿠팡이츠", 15000, "delivery", null, "out"),

                // 밤 9시 이전 소비: 탐지에서 제외돼야 함
                new AnalysisTransaction(
                        3L, LocalDate.of(2026, 10, 5),
                        LocalTime.of(18, 0),
                        "배민", 10000, "delivery", null, "out")
        );

        PatternDetector detector = new PatternDetector();

        var results = detector.detectLateNightPatterns(transactions);

        assertEquals(1, results.size());

        var pattern = results.get(0);

        assertEquals(PatternType.LATE_NIGHT, pattern.type());
        assertEquals("delivery", pattern.category());
        assertEquals(2, pattern.observedDays());
        assertEquals(35000L, pattern.sumKrw());
        assertEquals(List.of(1L, 2L),
                pattern.evidenceTransactionIds());
    }


}
