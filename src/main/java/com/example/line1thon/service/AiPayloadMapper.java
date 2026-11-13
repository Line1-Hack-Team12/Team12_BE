package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.entity.UploadedFile;
import com.example.line1thon.repository.TransactionRepository;
import com.example.line1thon.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 백엔드 탐지 결과(PatternItem, Transaction) 를 AI /narrate, /notify-copy 요청(7~8절) 으로 바꾼다.
 *
 * AI 는 여기서 보낸 숫자와 근거 거래 id 만 문장에 쓸 수 있으므로, 근거 거래는 반드시 1건 이상(최대 10건) 넣는다.
 * 잔액·계좌번호·원문(raw_text) 은 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AiPayloadMapper {

    public static final int MAX_EVIDENCE = 10;

    private final TransactionRepository transactionRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final AiServiceClient aiServiceClient;

    /** 패턴 하나 -> PatternPayload. 근거 거래가 하나도 없으면 null (AI 요청에서 뺀다). */
    public AiServiceClient.PatternPayload toPatternPayload(AnalysisPatternService.PatternItem p,
                                                           Map<Long, Transaction> txById) {
        List<AiServiceClient.EvidenceTxPayload> evidence = p.evidenceTransactionIds().stream()
                .map(txById::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Transaction::getTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_EVIDENCE)
                .map(this::toEvidence)
                .toList();
        if (evidence.isEmpty()) {
            return null;
        }
        return new AiServiceClient.PatternPayload(
                "pat_" + p.patternId(),
                toAiPatternType(p.type()),
                p.aiCondition(),
                new AiServiceClient.ObservedDto(p.observedDays(), p.observedWindow(), p.observedUnit()),
                p.sumKrw(),
                p.chainedSumKrw(),
                p.evidenceGrade() == null ? "normal" : p.evidenceGrade().toLowerCase(Locale.ROOT),
                evidence,
                List.of()
        );
    }

    /** /narrate 요청 전체. summary 는 세션의 지출 거래로 만든다 (AI 가 "쓸 수 있는 숫자" 로 쓴다). */
    public AiServiceClient.NarrateRequest toNarrateRequest(AnalysisSession session,
                                                           List<AnalysisPatternService.PatternItem> patterns,
                                                           List<String> want,
                                                           List<AiServiceClient.SubscriptionPayload> subscriptions) {
        List<Transaction> all = transactionRepository.findBySession(session);
        List<Transaction> expenses = all.stream().filter(Transaction::isCountedAsExpense).toList();
        Map<Long, Transaction> txById = all.stream().collect(Collectors.toMap(Transaction::getId, Function.identity()));

        List<AiServiceClient.PatternPayload> payloads = patterns.stream()
                .map(p -> toPatternPayload(p, txById))
                .filter(Objects::nonNull)
                .limit(10)   // /narrate 는 패턴 10개까지
                .toList();

        return new AiServiceClient.NarrateRequest(
                "snap_" + session.getId(),
                aiServiceClient.toAiTone(session.getPersona()),
                toPeriod(session, expenses),
                want,
                payloads,
                toSummary(expenses),
                subscriptions == null ? List.of() : subscriptions
        );
    }

    public AiServiceClient.PeriodDto toPeriod(AnalysisSession session, List<Transaction> expenses) {
        List<String> dates = expenses.stream()
                .map(Transaction::getDate)
                .filter(d -> d != null && d.length() >= 10)
                .sorted()
                .toList();
        List<String> slots = uploadedFileRepository.findBySession(session).stream()
                .map(UploadedFile::getSlotMonth)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        String start = dates.isEmpty() ? session.getLastMonth() + "-01" : dates.get(0);
        String end = dates.isEmpty() ? session.getLastMonth() + "-01" : dates.get(dates.size() - 1);
        return new AiServiceClient.PeriodDto(start, end, slots);
    }

    public AiServiceClient.SummaryPayload toSummary(List<Transaction> expenses) {
        long total = expenses.stream().mapToLong(t -> t.getAmount() == null ? 0L : t.getAmount()).sum();

        Map<String, Long> byCat = new LinkedHashMap<>();
        for (Transaction t : expenses) {
            String code = t.getCategory() != null ? t.getCategory() : "other";
            byCat.merge(code, t.getAmount() == null ? 0L : t.getAmount(), Long::sum);
        }
        List<Map<String, Object>> byCategory = byCat.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("category", e.getKey());
                    m.put("sum_krw", e.getValue());
                    m.put("share", total == 0 ? 0.0 : Math.round((double) e.getValue() / total * 100.0) / 100.0);
                    return m;
                })
                .toList();

        Map<String, Long> byMon = new TreeMap<>();
        for (Transaction t : expenses) {
            String d = t.getDate();
            if (d == null || d.length() < 7) continue;
            byMon.merge(d.substring(0, 7), t.getAmount() == null ? 0L : t.getAmount(), Long::sum);
        }
        List<Map<String, Object>> byMonth = byMon.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("month", e.getKey());
                    m.put("sum_krw", e.getValue());
                    return m;
                })
                .toList();

        long withoutTime = expenses.stream().filter(t -> t.getTime() == null || t.getTime().isBlank()).count();
        Map<String, Object> limits = new HashMap<>();
        limits.put("days_without_upload", 0);
        limits.put("transactions_without_time", withoutTime);
        limits.put("weather_skipped", true);

        return new AiServiceClient.SummaryPayload(total, byCategory, byMonth, limits);
    }

    public AiServiceClient.EvidenceTxPayload toEvidence(Transaction t) {
        return new AiServiceClient.EvidenceTxPayload(
                String.valueOf(t.getId()),
                t.getDate() == null ? "" : t.getDate(),
                t.getTime() == null || t.getTime().isBlank() ? null : t.getTime(),
                t.getStoreName() == null ? "" : t.getStoreName(),
                t.getAmount() == null ? 0L : t.getAmount(),
                t.getCategory(),
                t.getSubcategory()
        );
    }

    /** 탐지기 타입명(WEEKDAY, LATE_NIGHT, CHAIN, WEATHER) -> AI 타입(weekday, late_night, chain, rain) */
    public static String toAiPatternType(String rawType) {
        if (rawType == null) return "weekday";
        return switch (rawType.toUpperCase(Locale.ROOT)) {
            case "WEEKDAY" -> "weekday";
            case "LATE_NIGHT" -> "late_night";
            case "CHAIN" -> "chain";
            case "WEATHER", "RAIN" -> "rain";
            default -> rawType.toLowerCase(Locale.ROOT);
        };
    }

    /** AI 카드의 "pat_123" -> 123. 형식이 다르면 null. */
    public static Long parsePatternId(String aiPatternId) {
        if (aiPatternId == null) return null;
        String s = aiPatternId.startsWith("pat_") ? aiPatternId.substring(4) : aiPatternId;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** AI 카드의 근거 거래 id("123") 목록 -> Long 목록 (형식이 다른 것은 뺀다). */
    public static List<Long> parseTxIds(List<String> ids) {
        List<Long> out = new ArrayList<>();
        if (ids == null) return out;
        for (String s : ids) {
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // AI 가 형식을 어긴 id 는 무시
            }
        }
        return out;
    }
}
