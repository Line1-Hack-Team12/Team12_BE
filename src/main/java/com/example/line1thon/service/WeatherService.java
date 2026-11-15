package com.example.line1thon.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 과거 날씨 조회 (Open-Meteo 과거 날씨 API, 키 불필요).
 *
 * 세션의 지역과 거래 기간으로 "비 온 날" 집합을 돌려준다. 지역이 없거나 모르는 지역이거나
 * API 가 실패하면 {@link Optional#empty()} 를 돌려주고, 호출한 쪽은 날씨 패턴만 건너뛴다.
 * 같은 지역·기간은 메모리에 캐시해 결과 화면을 다시 열 때마다 외부 호출을 하지 않는다.
 */
@Slf4j
@Service
public class WeatherService {

    /** 일 강수량이 이 값(mm) 이상이면 "비 온 날" 로 본다. */
    public static final double DEFAULT_RAIN_THRESHOLD_MM = 1.0;

    /** 과거 날씨 API 는 최근 며칠은 아직 집계되지 않는다. 이 일수 전까지만 조회한다. */
    static final int ARCHIVE_LAG_DAYS = 2;

    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(10);

    /** 프론트 S01 지역 선택지 17개 → 대표 지점 위경도 (도 단위 지역은 도청 소재지). */
    static final Map<String, double[]> REGION_COORDS = Map.ofEntries(
            Map.entry("서울", new double[]{37.5665, 126.9780}),
            Map.entry("경기", new double[]{37.2636, 127.0286}),
            Map.entry("인천", new double[]{37.4563, 126.7052}),
            Map.entry("부산", new double[]{35.1796, 129.0756}),
            Map.entry("대구", new double[]{35.8714, 128.6014}),
            Map.entry("광주", new double[]{35.1595, 126.8526}),
            Map.entry("대전", new double[]{36.3504, 127.3845}),
            Map.entry("울산", new double[]{35.5384, 129.3114}),
            Map.entry("세종", new double[]{36.4800, 127.2890}),
            Map.entry("강원", new double[]{37.8813, 127.7298}),
            Map.entry("충북", new double[]{36.6424, 127.4890}),
            Map.entry("충남", new double[]{36.6588, 126.6728}),
            Map.entry("전북", new double[]{35.8242, 127.1480}),
            Map.entry("전남", new double[]{34.8161, 126.4629}),
            Map.entry("경북", new double[]{36.5760, 128.5056}),
            Map.entry("경남", new double[]{35.2383, 128.6922}),
            Map.entry("제주", new double[]{33.4996, 126.5312})
    );

    private final RestClient restClient;
    private final double rainThresholdMm;
    private final Clock clock;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public WeatherService(@Value("${weather.api.url:https://archive-api.open-meteo.com/v1/archive}") String apiUrl,
                          @Value("${weather.rain-threshold-mm:1.0}") double rainThresholdMm) {
        this(buildClient(apiUrl, Duration.ofSeconds(3), Duration.ofSeconds(8)), rainThresholdMm, Clock.systemDefaultZone());
    }

    WeatherService(RestClient restClient, double rainThresholdMm, Clock clock) {
        this.restClient = restClient;
        this.rainThresholdMm = rainThresholdMm;
        this.clock = clock;
    }

    static RestClient buildClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** "서울특별시", "경기도 " 같은 입력을 선택지 이름("서울", "경기")으로 맞춘다. 모르는 지역은 empty. */
    public static Optional<String> normalizeRegion(String region) {
        if (region == null) return Optional.empty();
        String r = region.trim();
        if (r.isEmpty()) return Optional.empty();
        if (REGION_COORDS.containsKey(r)) return Optional.of(r);
        return REGION_COORDS.keySet().stream().filter(r::startsWith).findFirst();
    }

    /** 거래 날짜 문자열("YYYY-MM-DD", "YYYY.MM.DD")들의 최소~최대 범위로 비 온 날을 조회한다. */
    public Optional<Set<LocalDate>> rainyDaysFor(String region, Collection<String> dateStrings) {
        LocalDate from = null, to = null;
        for (String s : dateStrings) {
            LocalDate d = parseDate(s);
            if (d == null) continue;
            if (from == null || d.isBefore(from)) from = d;
            if (to == null || d.isAfter(to)) to = d;
        }
        return rainyDays(region, from, to);
    }

    /** 지역의 [from, to] 기간 중 비 온 날. 조회할 수 없으면 empty (호출한 쪽은 날씨 패턴만 건너뛴다). */
    public Optional<Set<LocalDate>> rainyDays(String region, LocalDate from, LocalDate to) {
        Optional<String> key = normalizeRegion(region);
        if (key.isEmpty() || from == null || to == null) {
            return Optional.empty();
        }
        LocalDate latest = LocalDate.now(clock).minusDays(ARCHIVE_LAG_DAYS);
        LocalDate end = to.isAfter(latest) ? latest : to;
        if (end.isBefore(from)) {
            return Optional.empty();   // 전부 아직 집계되지 않은 기간
        }

        String cacheKey = key.get() + "|" + from + "|" + end;
        CacheEntry hit = cache.get(cacheKey);
        Instant now = Instant.now(clock);
        if (hit != null && hit.expiresAt().isAfter(now)) {
            return Optional.ofNullable(hit.rainyDays());
        }

        double[] coord = REGION_COORDS.get(key.get());
        try {
            OpenMeteoResponse response = restClient.get()
                    .uri(b -> b.queryParam("latitude", coord[0])
                            .queryParam("longitude", coord[1])
                            .queryParam("start_date", from.toString())
                            .queryParam("end_date", end.toString())
                            .queryParam("daily", "precipitation_sum")
                            .queryParam("timezone", "Asia/Seoul")
                            .build())
                    .retrieve()
                    .body(OpenMeteoResponse.class);
            Set<LocalDate> rainy = toRainyDays(response, rainThresholdMm);
            cache.put(cacheKey, new CacheEntry(rainy, now.plus(CACHE_TTL)));
            log.info("날씨 조회 성공: region={} {}~{} 비 온 날 {}일", key.get(), from, end, rainy.size());
            return Optional.of(rainy);
        } catch (RuntimeException e) {
            cache.put(cacheKey, new CacheEntry(null, now.plus(FAILURE_TTL)));
            log.warn("날씨 조회 실패, 날씨 패턴은 건너뜀: region={} {}~{} ({})", key.get(), from, end, e.toString());
            return Optional.empty();
        }
    }

    /** API 응답 → 일 강수량이 기준 이상인 날짜 집합. 값이 없는 날(null) 은 비 안 온 날로 본다. */
    static Set<LocalDate> toRainyDays(OpenMeteoResponse response, double thresholdMm) {
        Set<LocalDate> out = new HashSet<>();
        if (response == null || response.daily() == null
                || response.daily().time() == null || response.daily().precipitationSum() == null) {
            return out;
        }
        List<String> time = response.daily().time();
        List<Double> rain = response.daily().precipitationSum();
        for (int i = 0; i < Math.min(time.size(), rain.size()); i++) {
            Double mm = rain.get(i);
            LocalDate d = parseDate(time.get(i));
            if (mm != null && d != null && mm >= thresholdMm) {
                out.add(d);
            }
        }
        return out;
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10).replace('.', '-').replace('/', '-'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record CacheEntry(Set<LocalDate> rainyDays, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenMeteoResponse(Daily daily) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Daily(List<String> time,
                 @JsonProperty("precipitation_sum") List<Double> precipitationSum) {}
}
