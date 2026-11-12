package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavingsSimulationService {

    private final AnalysisSessionRepository sessionRepository;
    private final TransactionRepository transactionRepository;

    public SimulationResult simulate(
            Long analysisId,
            Long monthlyGoalKrw,
            List<String> lockedCategories) {

        // 1. 요청값 검사
        if (analysisId == null || analysisId <= 0 ||
                monthlyGoalKrw == null ||
                monthlyGoalKrw < 10000 ||
                monthlyGoalKrw > 500000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "월 저축 목표는 10,000~500,000원이어야 합니다."
            );
        }

        // 2. 분석 세션 조회
        AnalysisSession session = sessionRepository
                .findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "분석 세션을 찾을 수 없습니다."
                ));

        int months = session.getMonthsCount();

        // 3. 제외되지 않은 거래 불러오기
        List<Transaction> expenses = transactionRepository
                .findBySessionAndIsExcludedFalse(session)
                .stream()
                .filter(t -> t.getAmount() != null
                        && t.getAmount() > 0)
                .toList();

        long monthlyExpense = expenses.stream()
                .mapToLong(Transaction::getAmount)
                .sum() / months;

        // 4. 거래를 카테고리별로 집계
        // 같은 거래를 여러 패턴에서 중복 합산하지 않음
        Map<String, Long> categoryTotals = new HashMap<>();

        for (Transaction t : expenses) {
            if (t.getCategory() == null ||
                    t.getCategory().isBlank()) {
                continue;
            }

            categoryTotals.merge(
                    t.getCategory().trim(),
                    t.getAmount(),
                    Long::sum
            );
        }

        Set<String> locked = new HashSet<>();

        if (lockedCategories != null) {
            for (String category : lockedCategories) {
                if (category != null) {
                    locked.add(category.trim());
                }
            }
        }

        // 5. 카테고리별 최대 60% 절감 상한 계산
        List<CategoryBudget> budgets = new ArrayList<>();

        for (var entry : categoryTotals.entrySet()) {

            String category = entry.getKey();
            long monthlyAmount = entry.getValue() / months;

            String lower = category.toLowerCase(Locale.ROOT);

            boolean isLocked = locked.contains(category)
                    || lower.contains("정기결제")
                    || lower.contains("구독")
                    || lower.contains("subscription");

            long maxCut = isLocked
                    ? 0L
                    : monthlyAmount * 60 / 100;

            budgets.add(new CategoryBudget(
                    category,
                    monthlyAmount,
                    maxCut,
                    isLocked
            ));
        }

        budgets.sort(Comparator.comparing(
                CategoryBudget::category
        ));

        // 6. 최대 절약 가능 금액
        long maxSavings = budgets.stream()
                .mapToLong(CategoryBudget::maxCutKrw)
                .sum();

        boolean achievable = monthlyGoalKrw <= maxSavings;

        // 7. 저축 플랜 A/B 생성
        List<SavingsPlan> plans = new ArrayList<>();

        if (achievable) {
            List<CategoryBudget> eligible = budgets.stream()
                    .filter(b -> b.maxCutKrw() > 0)
                    .toList();

            List<CategoryBudget> descending =
                    new ArrayList<>(eligible);

            descending.sort(Comparator.comparingLong(
                    CategoryBudget::maxCutKrw
            ).reversed());

            plans.add(createPlan(
                    "A", "큰 지출 중심",
                    descending, monthlyGoalKrw
            ));

            // 절감 순서가 다른 두 번째 플랜
            if (eligible.size() > 1 && monthlyGoalKrw < maxSavings) {
                List<CategoryBudget> ascending =
                        new ArrayList<>(eligible);

                ascending.sort(Comparator.comparingLong(
                        CategoryBudget::maxCutKrw
                ));

                plans.add(createPlan(
                        "B", "작은 지출부터 분산",
                        ascending, monthlyGoalKrw
                ));
            }
        }


        // 8. 프론트에 전달할 결과
        String status;

        if (expenses.isEmpty()) {
            status = "NO_DATA";
        } else if (achievable) {
            status = "ACHIEVABLE";
        } else {
            status = "UNACHIEVABLE";
        }

        return new SimulationResult(
                analysisId,
                monthlyGoalKrw,
                monthlyExpense,
                maxSavings,
                achievable,
                achievable ? monthlyGoalKrw : maxSavings,
                List.copyOf(budgets),
                List.copyOf(plans),
                status
        );

    }

    // 개별 플랜 구성
    private SavingsPlan createPlan(
            String id,
            String name,
            List<CategoryBudget> budgets,
            long goal) {

        long remaining = goal;
        List<PlanCut> cuts = new ArrayList<>();

        for (CategoryBudget budget : budgets) {
            if (remaining <= 0) {
                break;
            }

            long cut = Math.min(
                    remaining, budget.maxCutKrw()
            );

            if (cut > 0) {
                cuts.add(new PlanCut(
                        budget.category(),
                        cut
                ));
                remaining -= cut;
            }
        }

        return new SavingsPlan(
                id,
                name,
                goal - remaining,
                List.copyOf(cuts)
        );
    }


    public record SimulationResult(
            Long analysisId,
            long monthlyGoalKrw,
            long monthlyExpenseKrw,
            long maxPossibleSavingsKrw,
            boolean achievable,
            long suggestedGoalKrw,
            List<CategoryBudget> categories,
            List<SavingsPlan> plans,
            String status
    ) {}


    public record CategoryBudget(
            String category,
            long monthlyExpenseKrw,
            long maxCutKrw,
            boolean locked
    ) {}

    public record SavingsPlan(
            String id,
            String name,
            long expectedSavingsKrw,
            List<PlanCut> cuts
    ) {}

    public record PlanCut(
            String category,
            long savingKrw
    ) {}

}
