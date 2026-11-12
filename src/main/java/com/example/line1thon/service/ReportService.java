package com.example.line1thon.service;

import com.example.line1thon.dto.ConsumptionReportResponse;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final AnalysisSessionRepository sessionRepository;
    private final TransactionRepository transactionRepository;
    private final AnalysisPatternService analysisPatternService;
    private final AiNarrativeService aiNarrativeService;
    private final AiServiceClient aiServiceClient;

    public ConsumptionReportResponse getConsumptionReport(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        List<Transaction> allTransactions = transactionRepository.findBySession(session);
        List<Transaction> expenses = allTransactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .toList();

        long totalExpense = expenses.stream().mapToLong(Transaction::getAmount).sum();

        // 1. 카테고리별 비중 계산 (6절 /classify 응답 우선 활용, 미구현 시 로컬 분류기 Fallback)
        Map<String, String> aiCategoryByMerchant = fetchAiCategories(expenses);

        Map<String, Long> categoryMap = expenses.stream()
                .collect(Collectors.groupingBy(
                        tx -> resolveCategory(tx, aiCategoryByMerchant),
                        Collectors.summingLong(Transaction::getAmount)
                ));

        List<ConsumptionReportResponse.CategoryRatioDto> categoryRatios = categoryMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    int pct = totalExpense == 0 ? 0 : (int) Math.round((double) entry.getValue() * 100 / totalExpense);
                    return ConsumptionReportResponse.CategoryRatioDto.builder()
                            .category(entry.getKey())
                            .amount(entry.getValue())
                            .percentage(pct)
                            .build();
                })
                .toList();

        String topCategoriesText = categoryRatios.stream()
                .limit(2)
                .map(ConsumptionReportResponse.CategoryRatioDto::getCategory)
                .collect(Collectors.joining("과 "));
        String highlightMessage = topCategoriesText.isEmpty()
                ? "분석할 지출 내역이 아직 없어요."
                : "소비가 " + topCategoriesText + "에 집중됐어요.";

        // 2. 월별 추이 계산 (시간순 정렬: 09월 -> 10월)
        Map<String, Long> monthSortMap = new TreeMap<>();
        for (Transaction tx : expenses) {
            String sortableMonth = extractSortableMonthKey(tx.getDate());
            monthSortMap.merge(sortableMonth, tx.getAmount(), Long::sum);
        }

        List<ConsumptionReportResponse.MonthlyAmountDto> monthlyAmounts = new ArrayList<>();
        List<String> sortedKeys = new ArrayList<>(monthSortMap.keySet());
        for (int i = 0; i < sortedKeys.size(); i++) {
            String key = sortedKeys.get(i);
            monthlyAmounts.add(ConsumptionReportResponse.MonthlyAmountDto.builder()
                    .month(formatDisplayMonth(key))
                    .amount(monthSortMap.get(key))
                    .isLatest(i == sortedKeys.size() - 1)
                    .build());
        }

        String changeRateLabel = calculateChangeRateLabel(monthlyAmounts);

        // 3. 정기결제 · 구독 감지
        List<ConsumptionReportResponse.SubscriptionItemDto> subItems = detectSubscriptions(expenses);
        long monthlySubTotal = subItems.stream().mapToLong(ConsumptionReportResponse.SubscriptionItemDto::getMonthlyAmount).sum();

        // 4. 시각 미상 건수 안내 문구
        long unknownTimeCount = expenses.stream()
                .filter(tx -> tx.getTime() == null || tx.getTime().isBlank())
                .count();
        String noticeMessage = unknownTimeCount > 0
                ? String.format("업로드 없는 날은 0원으로 추정하지 않아요. 시각 미상 %d건은 시간 분석에서 제외됐어요.", unknownTimeCount)
                : "업로드 없는 날은 0원으로 추정하지 않아요.";

        // 5. S05 탐지 패턴 & 7절 AI /narrate 연동 (미구현 시 기존 AiNarrativeService Fallback)
        String observedPattern = "반복 소비 패턴을 분석했어요.";
        String recommendedAction = "결제 전 장바구니와 보유 내역을 한 번 더 확인해 보세요.";

        AnalysisPatternService.AnalysisData analysisData = analysisPatternService.analyze(sessionId);
        List<AnalysisPatternService.PatternItem> patterns = analysisData.patterns();

        if (!patterns.isEmpty()) {
            AnalysisSession.Persona persona = session.getPersona() != null
                    ? session.getPersona()
                    : AnalysisSession.Persona.COACH;

            Optional<AiServiceClient.NarrateResponse> narrateOpt = callAiNarrate(
                    sessionId, persona, patterns, totalExpense, categoryRatios, monthlyAmounts, subItems, unknownTimeCount
            );

            if (narrateOpt.isPresent()) {
                AiServiceClient.NarrateResponse nr = narrateOpt.get();
                if (nr.summaryLine() != null && !nr.summaryLine().isBlank()) {
                    highlightMessage = nr.summaryLine();
                }
                if (nr.cards() != null && !nr.cards().isEmpty()) {
                    AiServiceClient.NarrateCard topCard = nr.cards().get(0);
                    if (topCard.observation() != null && !topCard.observation().isBlank()) {
                        observedPattern = topCard.observation();
                    }
                    if (topCard.action() != null && !topCard.action().isBlank()) {
                        recommendedAction = topCard.action();
                    }
                }
                if (nr.report() != null) {
                    if (nr.report().actions() != null && !nr.report().actions().isEmpty()) {
                        recommendedAction = nr.report().actions().get(0);
                    }
                    if (nr.report().limitsSentence() != null && !nr.report().limitsSentence().isBlank()) {
                        noticeMessage = nr.report().limitsSentence();
                    }
                }
            } else {
                AiNarrativeService.Result narrativeResult =
                        aiNarrativeService.generateCached(sessionId, patterns, persona);
                if (!narrativeResult.narratives().isEmpty()) {
                    AiNarrativeService.Narrative topNarrative = narrativeResult.narratives().get(0);
                    observedPattern = topNarrative.observation();
                    recommendedAction = topNarrative.action();
                } else {
                    AnalysisPatternService.PatternItem topPattern = patterns.get(0);
                    observedPattern = topPattern.condition() + " 패턴이 " + topPattern.observedDays() + "일 관찰됐어요.";
                }
            }
        }

        return ConsumptionReportResponse.builder()
                .sessionId(sessionId)
                .headerSummary(ConsumptionReportResponse.HeaderSummaryDto.builder()
                        .periodDescription("업로드 기록 기준")
                        .totalExpense(totalExpense)
                        .highlightMessage(highlightMessage)
                        .build())
                .categoryRatios(categoryRatios)
                .monthlyTrend(ConsumptionReportResponse.MonthlyTrendDto.builder()
                        .changeRateLabel(changeRateLabel)
                        .monthlyAmounts(monthlyAmounts)
                        .build())
                .observationAndAction(ConsumptionReportResponse.ObservationActionDto.builder()
                        .observedPattern(observedPattern)
                        .recommendedAction(recommendedAction)
                        .build())
                .subscriptions(ConsumptionReportResponse.SubscriptionSummaryDto.builder()
                        .items(subItems)
                        .monthlyTotal(monthlySubTotal)
                        .yearlyTotal(monthlySubTotal * 12)
                        .build())
                .noticeMessage(noticeMessage)
                .build();
    }

    private Optional<AiServiceClient.NarrateResponse> callAiNarrate(
            Long sessionId,
            AnalysisSession.Persona persona,
            List<AnalysisPatternService.PatternItem> patterns,
            long totalExpense,
            List<ConsumptionReportResponse.CategoryRatioDto> categoryRatios,
            List<ConsumptionReportResponse.MonthlyAmountDto> monthlyAmounts,
            List<ConsumptionReportResponse.SubscriptionItemDto> subItems,
            long unknownTimeCount
    ) {
        List<AiServiceClient.PatternPayload> patternPayloads = patterns.stream()
                .map(p -> new AiServiceClient.PatternPayload(
                        "pat_" + p.patternId(),
                        p.type().toLowerCase(Locale.ROOT),
                        Map.of("description", p.condition()),
                        new AiServiceClient.ObservedDto(p.observedDays(), 13, "일"),
                        p.sumKrw(),
                        p.chainedSumKrw(),
                        p.evidenceGrade().toLowerCase(Locale.ROOT),
                        List.of(),
                        List.of()
                ))
                .toList();

        List byCategory = categoryRatios.stream()
                .map(c -> Map.<String, Object>of(
                        "category", aiServiceClient.toAiCategory(c.getCategory()),
                        "sum_krw", c.getAmount(),
                        "share", Math.round((c.getPercentage() / 100.0) * 100.0) / 100.0
                ))
                .toList();

        List byMonth = monthlyAmounts.stream()
                .map(m -> Map.<String, Object>of(
                        "month", m.getMonth(),
                        "sum_krw", m.getAmount()
                ))
                .toList();

        AiServiceClient.SummaryPayload summaryPayload = new AiServiceClient.SummaryPayload(
                totalExpense,
                byCategory,
                byMonth,
                Map.of(
                        "days_without_upload", 0,
                        "transactions_without_time", unknownTimeCount,
                        "weather_skipped", true
                )
        );

        List<AiServiceClient.SubscriptionPayload> subPayloads = subItems.stream()
                .map(s -> new AiServiceClient.SubscriptionPayload(
                        s.getServiceName(),
                        s.getMonthlyAmount(),
                        2,
                        Boolean.TRUE.equals(s.getIsPriceIncreased())
                ))
                .toList();

        AiServiceClient.NarrateRequest request = new AiServiceClient.NarrateRequest(
                "snap_" + sessionId,
                aiServiceClient.toAiTone(persona),
                new AiServiceClient.PeriodDto(null, null, List.of()),
                List.of("cards", "summary", "report"),
                patternPayloads,
                summaryPayload,
                subPayloads
        );

        return aiServiceClient.narrate(request);
    }

    private Map<String, String> fetchAiCategories(List<Transaction> expenses) {
        List<AiServiceClient.ClassifyItemRequest> items = expenses.stream()
                .map(Transaction::getStoreName)
                .filter(Objects::nonNull)
                .distinct()
                .limit(100)
                .map(merchant -> new AiServiceClient.ClassifyItemRequest(merchant, "expense", null))
                .toList();

        return aiServiceClient.classify(items)
                .map(res -> {
                    Map<String, String> map = new HashMap<>();
                    if (res.items() != null) {
                        for (AiServiceClient.ClassifyItemResult item : res.items()) {
                            if (item.merchant() != null && item.category() != null && !"low".equals(item.confidence())) {
                                map.put(item.merchant(), mapAiCategoryToKorean(item.category(), item.subcategory()));
                            }
                        }
                    }
                    return map;
                })
                .orElse(Map.of());
    }

    private String mapAiCategoryToKorean(String category, String subcategory) {
        if ("구독".equals(subcategory)) return "구독";
        return switch (category) {
            case "delivery" -> "배달";
            case "cafe" -> "카페";
            case "shopping" -> "쇼핑";
            case "transport" -> "택시";
            case "food" -> "외식";
            case "living" -> "생활";
            default -> "기타";
        };
    }

    private String resolveCategory(Transaction tx, Map<String, String> aiCategoryByMerchant) {
        if (tx.getStoreName() != null && aiCategoryByMerchant.containsKey(tx.getStoreName())) {
            return aiCategoryByMerchant.get(tx.getStoreName());
        }

        String kind = tx.getKind();
        if (kind != null && !kind.isBlank()
                && !kind.equalsIgnoreCase("expense")
                && !kind.equalsIgnoreCase("unknown")) {
            return kind;
        }

        String store = tx.getStoreName() != null ? tx.getStoreName().toLowerCase() : "";
        if (store.contains("배달의민족") || store.contains("배민") || store.contains("쿠팡이츠") || store.contains("요기요")) {
            return "배달";
        }
        if (store.contains("택시") || store.contains("카카오t") || store.contains("우버") || store.contains("타다")) {
            return "택시";
        }
        if (store.contains("스타벅스") || store.contains("메가커피") || store.contains("투썸")
                || store.contains("컴포즈") || store.contains("이디야") || store.contains("빽다방") || store.contains("카페")) {
            return "카페";
        }
        if (store.contains("youtube") || store.contains("유튜브") || store.contains("넷플릭스")
                || store.contains("netflix") || store.contains("멜론") || store.contains("티빙")) {
            return "구독";
        }
        if (store.contains("올리브영") || store.contains("무신사") || store.contains("다이소") || store.contains("쇼핑")) {
            return "쇼핑";
        }
        return "기타";
    }

    private String extractSortableMonthKey(String date) {
        if (date == null || date.isBlank()) return "9999-99";
        String normalized = date.trim().replace('.', '-').replace('/', '-');
        String[] parts = normalized.split("-");
        if (parts.length >= 2) {
            try {
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                return String.format("%04d-%02d", year, month);
            } catch (NumberFormatException ignored) {
                return "9999-99";
            }
        }
        return "9999-99";
    }

    private String formatDisplayMonth(String sortableKey) {
        if ("9999-99".equals(sortableKey) || !sortableKey.contains("-")) return "기타";
        int month = Integer.parseInt(sortableKey.split("-")[1]);
        return month + "월";
    }

    private String calculateChangeRateLabel(List<ConsumptionReportResponse.MonthlyAmountDto> list) {
        if (list.size() < 2) return "비교 데이터 부족";
        long prev = list.get(list.size() - 2).getAmount();
        long curr = list.get(list.size() - 1).getAmount();
        if (prev == 0) return "전월 대비 +100%";
        long rate = Math.round(((double) (curr - prev) / prev) * 100);
        return rate >= 0 ? "전월 대비 +" + rate + "%" : "전월 대비 " + rate + "%";
    }

    private List<ConsumptionReportResponse.SubscriptionItemDto> detectSubscriptions(List<Transaction> expenses) {
        List<String> subKeywords = List.of("유튜브", "youtube", "넷플릭스", "netflix", "쿠팡와우", "멜론", "스포티파이", "티빙", "디즈니", "구독", "멤버십");
        Map<String, List<Transaction>> byStore = expenses.stream()
                .filter(tx -> tx.getStoreName() != null)
                .collect(Collectors.groupingBy(Transaction::getStoreName));

        List<ConsumptionReportResponse.SubscriptionItemDto> result = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : byStore.entrySet()) {
            String store = entry.getKey();
            List<Transaction> txs = entry.getValue();
            boolean matchesKeyword = subKeywords.stream().anyMatch(kw -> store.toLowerCase().contains(kw));

            if (matchesKeyword) {
                txs.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
                long firstAmt = txs.get(0).getAmount();
                long lastAmt = txs.get(txs.size() - 1).getAmount();
                boolean increased = lastAmt > firstAmt;

                String history = txs.stream()
                        .map(tx -> String.format("%,d", tx.getAmount()))
                        .collect(Collectors.joining(" → "));

                result.add(ConsumptionReportResponse.SubscriptionItemDto.builder()
                        .serviceName(store)
                        .priceHistory(history)
                        .isPriceIncreased(increased)
                        .monthlyAmount(lastAmt)
                        .build());
            }
        }
        return result;
    }
}