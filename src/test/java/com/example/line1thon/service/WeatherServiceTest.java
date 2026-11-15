package com.example.line1thon.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WeatherServiceTest {

    // 2026-11-15 고정 시계 (집계 지연 2일 → 11-13 까지 조회 가능)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    /** 아무도 듣지 않는 주소. 연결 거부가 바로 나서 테스트가 느려지지 않는다. */
    private static WeatherService unreachable() {
        RestClient client = WeatherService.buildClient("http://127.0.0.1:9", Duration.ofMillis(500), Duration.ofMillis(500));
        return new WeatherService(client, WeatherService.DEFAULT_RAIN_THRESHOLD_MM, CLOCK);
    }

    @Test
    void 지역_이름을_선택지_이름으로_맞춘다() {
        assertEquals(Optional.of("서울"), WeatherService.normalizeRegion("서울"));
        assertEquals(Optional.of("서울"), WeatherService.normalizeRegion("서울특별시"));
        assertEquals(Optional.of("경기"), WeatherService.normalizeRegion(" 경기도 "));
        assertEquals(Optional.of("제주"), WeatherService.normalizeRegion("제주특별자치도"));
        assertTrue(WeatherService.normalizeRegion(null).isEmpty());
        assertTrue(WeatherService.normalizeRegion("").isEmpty());
        assertTrue(WeatherService.normalizeRegion("뉴욕").isEmpty());
    }

    @Test
    void 프론트_지역_선택지_17개가_모두_좌표를_가진다() {
        List<String> frontRegions = List.of("서울", "경기", "인천", "부산", "대구", "광주", "대전", "울산", "세종",
                "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
        for (String r : frontRegions) {
            assertTrue(WeatherService.REGION_COORDS.containsKey(r), r);
        }
    }

    @Test
    void 응답을_비_온_날_집합으로_바꾼다() {
        var response = new WeatherService.OpenMeteoResponse(new WeatherService.Daily(
                List.of("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05"),
                Arrays.asList(0.0, 0.9, 1.0, 28.4, null)      // null = 아직 집계 안 됨
        ));

        Set<LocalDate> rainy = WeatherService.toRainyDays(response, 1.0);

        assertEquals(Set.of(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4)), rainy);
        assertTrue(WeatherService.toRainyDays(null, 1.0).isEmpty());
        assertTrue(WeatherService.toRainyDays(new WeatherService.OpenMeteoResponse(null), 1.0).isEmpty());
    }

    @Test
    void 지역이_없거나_모르는_지역이면_건너뛴다() {
        WeatherService svc = unreachable();
        LocalDate from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 10, 31);

        assertTrue(svc.rainyDays(null, from, to).isEmpty());
        assertTrue(svc.rainyDays("", from, to).isEmpty());
        assertTrue(svc.rainyDays("뉴욕", from, to).isEmpty());
        assertTrue(svc.rainyDays("서울", null, to).isEmpty());
    }

    @Test
    void 전부_아직_집계되지_않은_기간이면_건너뛴다() {
        WeatherService svc = unreachable();
        // 시계가 11-15 이므로 11-14 이후는 조회 불가
        assertTrue(svc.rainyDays("서울", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)).isEmpty());
    }

    @Test
    void 서버에_닿지_못하면_건너뛰고_실패도_잠시_기억한다() {
        WeatherService svc = unreachable();
        LocalDate from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 10, 31);

        assertTrue(svc.rainyDays("서울", from, to).isEmpty());

        long t = System.nanoTime();
        assertTrue(svc.rainyDays("서울", from, to).isEmpty());   // 캐시된 실패 → 재호출 없이 즉시
        assertTrue(Duration.ofNanos(System.nanoTime() - t).toMillis() < 200);
    }

    @Test
    void 거래_날짜_문자열로_기간을_잡는다() {
        WeatherService svc = unreachable();
        // 날짜가 하나도 없으면 범위를 못 잡아 건너뛴다 (예외 없이)
        assertTrue(svc.rainyDaysFor("서울", List.of()).isEmpty());
        assertTrue(svc.rainyDaysFor("서울", Arrays.asList(null, "", "날짜아님")).isEmpty());
        assertEquals(LocalDate.of(2026, 10, 3), WeatherService.parseDate("2026.10.03 22:18:19"));
        assertEquals(LocalDate.of(2026, 10, 3), WeatherService.parseDate("2026-10-03"));
        assertNull(WeatherService.parseDate("2026-13-03"));
    }
}
