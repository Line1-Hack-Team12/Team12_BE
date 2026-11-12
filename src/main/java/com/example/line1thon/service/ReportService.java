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

    public ConsumptionReportResponse getConsumptionReport(Long sessionId) {
        AnalysisSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));

        List<Transaction> allTransactions = transactionRepository.findBySession(session);
        List<Transaction> expenses = allTransactions.stream()
                .filter(Transaction::isCountedAsExpense)
                .toList();

        long totalExpense = expenses.stream().mapToLong(Transaction::getAmount).sum();

        // 1. 카테고리별 비중 계산 (세부 카테고리 자동 추론 적용)
        Map<String, Long> categoryMap = expenses.stream()
                .collect(Collectors.groupingBy(
                        this::resolveCategory,
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

        // 2. 월별 추이 계산
        Map<String, Long> monthMap = new TreeMap<>();
        for (Transaction tx : expenses) {
            String monthLabel = extractMonthLabel(tx.getDate());
            monthMap.merge(monthLabel, tx.getAmount(), Long::sum);
        }

        List<ConsumptionReportResponse.MonthlyAmountDto> monthlyAmounts = new ArrayList<>();
        List<String> months = new ArrayList<>(monthMap.keySet());
        for (int i = 0; i < months.size(); i++) {
            String m = months.get(i);
            monthlyAmounts.add(ConsumptionReportResponse.MonthlyAmountDto.builder()
                    .month(m)
                    .amount(monthMap.get(m))
                    .isLatest(i == months.size() - 1)
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
                // TODO [보경 S05 머지 후 연동] HabitPattern 테이블에서 해당 세션의 1위 습관 패턴 조회하여 교체
                .observationAndAction(ConsumptionReportResponse.ObservationActionDto.builder()
                        .observedPattern("반복 소비 패턴을 분석했어요.")
                        .recommendedAction("결제 전 장바구니와 보유 내역을 한 번 더 확인해 보세요.")
                        .build())
                .subscriptions(ConsumptionReportResponse.SubscriptionSummaryDto.builder()
                        .items(subItems)
                        .monthlyTotal(monthlySubTotal)
                        .yearlyTotal(monthlySubTotal * 12)
                        .build())
                .noticeMessage(noticeMessage)
                .build();
    }

    private String resolveCategory(Transaction tx) {
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

    private String extractMonthLabel(String date) {
        if (date == null || date.isBlank()) return "기타";
        if (date.contains("-") && date.length() >= 7) {
            int month = Integer.parseInt(date.substring(5, 7));
            return month + "월";
        }
        if (date.contains(".") && date.length() >= 7) {
            int month = Integer.parseInt(date.substring(5, 7));
            return month + "월";
        }
        return "기타";
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