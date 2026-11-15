package com.example.line1thon.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PatternDetectorTest {

    @Test
    void 비_오는_날_택시_반복_패턴_탐지() {

        Set<LocalDate> rainyDays = Set.of(
                LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 25));

        List<AnalysisTransaction> transactions = List.of(
                // 비 온 날 택시 3일
                new AnalysisTransaction(1L, LocalDate.of(2026, 10, 2), LocalTime.of(18, 30), "카카오T", 12000, "transport", "택시", "out"),
                new AnalysisTransaction(2L, LocalDate.of(2026, 10, 9), null, "카카오T", 9800, "transport", "택시", "out"),
                new AnalysisTransaction(3L, LocalDate.of(2026, 10, 20), LocalTime.of(19, 10), "우티", 15600, "transport", "택시", "out"),
                // 비 안 온 날 택시 (근거에서 빠져야 함)
                new AnalysisTransaction(4L, LocalDate.of(2026, 10, 7), LocalTime.of(23, 50), "카카오T", 16800, "transport", "택시", "out"),
                // 비 온 날이지만 다른 카테고리 1건 (3일 미만)
                new AnalysisTransaction(5L, LocalDate.of(2026, 10, 25), LocalTime.of(12, 0), "스타벅스", 5000, "cafe", null, "out")
        );

        List<PatternDetector.DetectedPattern> results =
                new PatternDetector().detectRainPatterns(transactions, rainyDays);

        assertEquals(1, results.size());
        var p = results.get(0);
        assertEquals(PatternType.WEATHER, p.type());
        assertNull(p.dayOfWeek());
        assertEquals("택시", p.category());            // 세부 분류 우선
        assertEquals(3, p.observedDays());
        assertEquals(37400L, p.sumKrw());              // 12000 + 9800 + 15600
        assertEquals(List.of(1L, 2L, 3L), p.evidenceTransactionIds());
    }

    @Test
    void 비_온_날이_2일이면_날씨_패턴이_아니다() {

        Set<LocalDate> rainyDays = Set.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 9));
        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(1L, LocalDate.of(2026, 10, 2), LocalTime.of(18, 30), "카카오T", 12000, "transport", "택시", "out"),
                new AnalysisTransaction(2L, LocalDate.of(2026, 10, 9), LocalTime.of(18, 0), "카카오T", 9800, "transport", "택시", "out"),
                new AnalysisTransaction(3L, LocalDate.of(2026, 10, 9), LocalTime.of(22, 0), "우티", 15600, "transport", "택시", "out")
        );

        assertTrue(new PatternDetector().detectRainPatterns(transactions, rainyDays).isEmpty());
    }

    @Test
    void 강수일_정보가_없으면_날씨_패턴을_만들지_않는다() {
        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(1L, LocalDate.of(2026, 10, 2), LocalTime.of(18, 30), "카카오T", 12000, "transport", "택시", "out")
        );
        assertTrue(new PatternDetector().detectRainPatterns(transactions, Set.of()).isEmpty());
        assertTrue(new PatternDetector().detectRainPatterns(transactions, null).isEmpty());
        assertTrue(new PatternDetector().detectRainPatterns(List.of(), Set.of(LocalDate.of(2026, 10, 2))).isEmpty());
    }

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
    void 서로_다른_날_2일은_반복_패턴이_아니다() {

        List<AnalysisTransaction> transactions = List.of(
                new AnalysisTransaction(
                        1L, LocalDate.of(2026, 10, 1),
                        LocalTime.of(22, 0),
                        "배민", 20000, "delivery", null, "out"),
                new AnalysisTransaction(
                        2L, LocalDate.of(2026, 10, 8),
                        LocalTime.of(21, 30),
                        "쿠팡이츠", 15000, "delivery", null, "out")
        );

        var results = new PatternDetector().detectWeekdayPatterns(transactions);

        // 최소 관찰 일수(3일) 미만이면 패턴으로 보지 않는다
        assertTrue(results.isEmpty());
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

                new AnalysisTransaction(
                        4L, LocalDate.of(2026, 10, 7),
                        LocalTime.of(23, 10),
                        "요기요", 12000, "delivery", null, "out"),

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
        assertEquals(3, pattern.observedDays());
        assertEquals(47000L, pattern.sumKrw());
        assertEquals(List.of(1L, 2L, 4L),
                pattern.evidenceTransactionIds());
    }


}
