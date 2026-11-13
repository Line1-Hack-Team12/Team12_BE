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

    public ConsumptionReportResponse getConsumptionReport(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        List<Transaction> allTransactions = transactionRepository.findBySession(session);
        List<Transaction> expenses = allTransactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .toList();

        long totalExpense = expenses.stream().mapToLong(Transaction::getAmount).sum();

        // 1. 카테고리별 비중 계산 (추출 직후 저장된 AI /classify 결과 기준, 없으면 키워드 분류).
        //    이체(송금·저금·충전) 는 비중에서 뺀다. 총지출(totalExpense) 과 월별 추이에는 그대로 들어간다.
        Map<String, Long> categoryMap = expenses.stream()
                .filter(tx -> !analysisPatternService.isTransfer(tx))
                .collect(Collectors.groupingBy(
                        analysisPatternService::categoryLabel,
                        Collectors.summingLong(Transaction::getAmount)
                ));
        long ratioBase = categoryMap.values().stream().mapToLong(Long::longValue).sum();

        List<ConsumptionReportResponse.CategoryRatioDto> categoryRatios = categoryMap.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> {
                    int pct = ratioBase == 0 ? 0 : (int) Math.round((double) entry.getValue() * 100 / ratioBase);
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
        List<SubscriptionHit> subHits = detectSubscriptions(expenses);
        List<ConsumptionReportResponse.SubscriptionItemDto> subItems = subHits.stream().map(SubscriptionHit::dto).toList();
        long monthlySubTotal = subItems.stream().mapToLong(ConsumptionReportResponse.SubscriptionItemDto::getMonthlyAmount).sum();

        // 4. 시각 미상 건수 안내 문구
        long unknownTimeCount = expenses.stream()
                .filter(tx -> tx.getTime() == null || tx.getTime().isBlank())
                .count();
        String noticeMessage = unknownTimeCount > 0
                ? String.format("업로드 없는 날은 0원으로 추정하지 않아요. 시각 미상 %d건은 시간 분석에서 제외됐어요.", unknownTimeCount)
                : "업로드 없는 날은 0원으로 추정하지 않아요.";

        // 5. S05 탐지 패턴 + AI /narrate (7절). S05 카드와 같은 캐시를 쓰므로 두 번 부르지 않는다
        String observedPattern = "반복 소비 패턴을 분석했어요.";
        String recommendedAction = "결제 전 장바구니와 보유 내역을 한 번 더 확인해 보세요.";

        AnalysisPatternService.AnalysisData analysisData = analysisPatternService.analyze(sessionId);
        List<AnalysisPatternService.PatternItem> patterns = analysisData.patterns();

        if (!patterns.isEmpty()) {
            AnalysisSession.Persona persona = session.getPersona() != null
                    ? session.getPersona()
                    : AnalysisSession.Persona.COACH;

            List<AiServiceClient.SubscriptionPayload> subPayloads = subHits.stream()
                    .map(s -> new AiServiceClient.SubscriptionPayload(
                            s.dto().getServiceName(), s.dto().getMonthlyAmount(), s.observedMonths(),
                            Boolean.TRUE.equals(s.dto().getIsPriceIncreased())))
                    .toList();

            AiNarrativeService.Result narrative = aiNarrativeService.generateCached(sessionId, patterns, persona, subPayloads);

            if (narrative.summaryLine() != null && !narrative.summaryLine().isBlank()) {
                highlightMessage = narrative.summaryLine();
            }
            if (!narrative.narratives().isEmpty()) {
                AiNarrativeService.Narrative top = narrative.narratives().get(0);
                if (top.observation() != null && !top.observation().isBlank()) observedPattern = top.observation();
                if (top.action() != null && !top.action().isBlank()) recommendedAction = top.action();
            }
            AiServiceClient.NarrateReport report = narrative.report();
            if (report != null) {
                if (report.actions() != null && !report.actions().isEmpty()) {
                    recommendedAction = report.actions().get(0);
                }
                if (report.limitsSentence() != null && !report.limitsSentence().isBlank()) {
                    noticeMessage = report.limitsSentence();
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

    /** 구독 감지 결과 + 관찰 개월 수 (AI /narrate 의 observed_months 로 보낸다) */
    private record SubscriptionHit(ConsumptionReportResponse.SubscriptionItemDto dto, int observedMonths) {}

    private List<SubscriptionHit> detectSubscriptions(List<Transaction> expenses) {
        List<String> subKeywords = List.of("유튜브", "youtube", "넷플릭스", "netflix", "쿠팡와우", "멜론", "스포티파이", "티빙", "디즈니", "구독", "멤버십");
        Map<String, List<Transaction>> byStore = expenses.stream()
                .filter(tx -> tx.getStoreName() != null)
                .collect(Collectors.groupingBy(Transaction::getStoreName));

        List<SubscriptionHit> result = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> entry : byStore.entrySet()) {
            String store = entry.getKey();
            List<Transaction> txs = new ArrayList<>(entry.getValue());
            boolean matchesKeyword = subKeywords.stream().anyMatch(kw -> store.toLowerCase().contains(kw))
                    || "구독".equals(analysisPatternService.categoryLabel(txs.get(0)));

            if (matchesKeyword) {
                txs.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
                long firstAmt = txs.get(0).getAmount();
                long lastAmt = txs.get(txs.size() - 1).getAmount();
                boolean increased = lastAmt > firstAmt;

                String history = txs.stream()
                        .map(tx -> String.format("%,d", tx.getAmount()))
                        .collect(Collectors.joining(" → "));

                int months = (int) txs.stream()
                        .map(tx -> extractSortableMonthKey(tx.getDate()))
                        .distinct()
                        .count();

                result.add(new SubscriptionHit(
                        ConsumptionReportResponse.SubscriptionItemDto.builder()
                                .serviceName(store)
                                .priceHistory(history)
                                .isPriceIncreased(increased)
                                .monthlyAmount(lastAmt)
                                .build(),
                        Math.max(1, months)));
            }
        }
        return result;
    }
}
