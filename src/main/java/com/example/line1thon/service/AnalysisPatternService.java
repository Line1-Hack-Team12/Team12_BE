package com.example.line1thon.service;

import com.example.line1thon.analysis.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisPatternService {

    private final AnalysisSessionRepository sessionRepository;
    private final TransactionRepository transactionRepository;

    public AnalysisData analyze(Long analysisId) {

        // 1. 분석 세션 조회
        AnalysisSession session = sessionRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "분석 세션이 없습니다."
                ));


        // 2. 실제 지출 거래 조회
        List<Transaction> expenses = transactionRepository
                .findBySession(session)
                .stream()
                .filter(Transaction::isCountedAsExpense)
                .filter(t -> t.getAmount() != null
                        && t.getAmount() > 0)
                .toList();

        // 3. F08 탐지 엔진의 입력 형태로 변환
        List<AnalysisTransaction> inputs = expenses.stream()
                .map(t -> new AnalysisTransaction(
                        t.getId(),
                        parseDate(t.getDate()),
                        parseTime(t.getTime()),
                        t.getStoreName(),
                        t.getAmount(),
                        resolveCategory(t.getStoreName()),
                        null,
                        t.getDirection()
                ))
                .toList();


        // 4. 요일 및 심야 소비 패턴 탐지
        PatternDetector detector = new PatternDetector();

        List<PatternItem> patterns = new ArrayList<>();

        List<PatternDetector.DetectedPattern> detected =
                new ArrayList<>();

        detected.addAll(detector.detectWeekdayPatterns(inputs));
        detected.addAll(detector.detectLateNightPatterns(inputs));

        for (var p : detected) {

            String condition;

            if (p.dayOfWeek() != null) {
                String day = p.dayOfWeek().getDisplayName(
                        TextStyle.FULL, Locale.KOREAN
                );

                condition = day + " " + p.category() + " 소비";
            } else {
                condition = "밤 9시 이후 " + p.category() + " 소비";
            }

            String key = p.type() + "|"
                    + p.dayOfWeek() + "|"
                    + p.category();

            patterns.add(new PatternItem(
                    stableId(analysisId, key),
                    p.type().name(),
                    condition,
                    p.observedDays(),
                    p.sumKrw(),
                    0L,
                    p.evidenceGrade().name(),
                    p.evidenceTransactionIds()
            ));
        }


        // 5. 연쇄 소비 패턴 탐지
        ChainPatternDetector chainDetector =
                new ChainPatternDetector();

        for (var p : chainDetector.detectChainPatterns(inputs)) {

            String condition = p.fromCategory()
                    + " → " + p.toCategory() + " 연쇄 소비";

            String key = "CHAIN|" + p.fromCategory()
                    + "|" + p.toCategory();

            patterns.add(new PatternItem(
                    stableId(analysisId, key),
                    p.type().name(),
                    condition,
                    p.observedDays(),
                    p.sumKrw(),
                    p.sumKrw(),
                    p.evidenceGrade().name(),
                    p.evidenceTransactionIds()
            ));
        }

        // 6. 지출 금액이 큰 패턴부터 정렬
        patterns.sort(
                Comparator.comparingLong(PatternItem::sumKrw)
                        .reversed()
                        .thenComparing(PatternItem::patternId)
        );

        // 7. 전체 지출 합계 계산
        long totalExpense = expenses.stream()
                .mapToLong(Transaction::getAmount)
                .sum();

        return new AnalysisData(
                analysisId,
                session.getStatus().name(),
                expenses.size(),
                totalExpense,
                List.copyOf(patterns)
        );
    }

    // 문자열 날짜를 LocalDate로 변환
    private java.time.LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            String normalized = value.trim()
                    .replace('.', '-')
                    .replace('/', '-');

            String[] parts = normalized.split("-");

            if (parts.length != 3) {
                return null;
            }

            return java.time.LocalDate.of(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    // 문자열 시간을 LocalTime으로 변환
    private java.time.LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return java.time.LocalTime.parse(value.trim());
        } catch (RuntimeException e) {
            try {
                return java.time.LocalTime.parse(
                        value.trim(),
                        java.time.format.DateTimeFormatter
                                .ofPattern("H:mm")
                );
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    // 가맹점 이름으로 소비 카테고리 분류
    private String resolveCategory(String merchant) {
        String name = merchant == null
                ? ""
                : merchant.toLowerCase(java.util.Locale.ROOT);

        if (name.contains("배민")
                || name.contains("배달의민족")
                || name.contains("쿠팡이츠")
                || name.contains("요기요")) {
            return "배달";
        }

        if (name.contains("택시")
                || name.contains("카카오t")
                || name.contains("우버")
                || name.contains("타다")) {
            return "택시";
        }

        if (name.contains("스타벅스")
                || name.contains("메가커피")
                || name.contains("컴포즈")
                || name.contains("투썸")
                || name.contains("이디야")
                || name.contains("빽다방")
                || name.contains("카페")) {
            return "카페";
        }

        if (name.contains("넷플릭스")
                || name.contains("netflix")
                || name.contains("유튜브")
                || name.contains("youtube")
                || name.contains("멜론")
                || name.contains("티빙")) {
            return "구독";
        }

        if (name.contains("올리브영")
                || name.contains("무신사")
                || name.contains("다이소")
                || name.contains("쇼핑")) {
            return "쇼핑";
        }

        return "기타";
    }


    // 같은 분석의 동일 패턴에 안정적인 ID 부여
    private long stableId(Long analysisId, String key) {
        String value = analysisId + "|" + key;

        UUID uuid = UUID.nameUUIDFromBytes(
                value.getBytes(StandardCharsets.UTF_8)
        );

        return uuid.getMostSignificantBits() & Long.MAX_VALUE;
    }

    public record AnalysisData(
            Long analysisId,
            String sessionStatus,
            int transactionCount,
            long totalExpenseKrw,
            List<PatternItem> patterns
    ) {}

    public record PatternItem(
            Long patternId,
            String type,
            String condition,
            int observedDays,
            long sumKrw,
            long chainedSumKrw,
            String evidenceGrade,
            List<Long> evidenceTransactionIds
    ) {}
}
