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

        // 2. 제외되지 않은 지출 거래 조회
        List<Transaction> expenses = transactionRepository
                .findBySessionAndIsExcludedFalse(session)
                .stream()
                .filter(t -> t.getAmount() != null
                        && t.getAmount() > 0)
                .toList();

        // 3. F08 탐지 엔진의 입력 형태로 변환
        List<AnalysisTransaction> inputs = expenses.stream()
                .filter(t -> t.getTransactionDateTime() != null)
                .filter(t -> t.getCategory() != null
                        && !t.getCategory().isBlank())
                .map(t -> new AnalysisTransaction(
                        t.getId(),
                        t.getTransactionDateTime().toLocalDate(),
                        t.getTransactionDateTime().toLocalTime(),
                        t.getStoreName(),
                        t.getAmount(),
                        t.getCategory(),
                        null,
                        "out"
                ))
                .toList();

        PatternDetector detector = new PatternDetector();
        List<PatternItem> patterns = new ArrayList<>();

        // 4. 요일 및 심야 소비 패턴 탐지
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

            String key = p.type() + "|" + p.dayOfWeek()
                    + "|" + p.category();

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
