package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.TransactionRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SavingsSimulationServiceTest {

    @Autowired
    private SavingsSimulationService service;

    @Autowired
    private AnalysisSessionRepository sessionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    void testSavingsSimulation() {

        // 테스트용 세션
        var session = sessionRepository.save(
                AnalysisSession.builder()
                        .monthsCount(1)
                        .lastMonth("2026-10")
                        .persona(AnalysisSession.Persona.COACH)
                        .build()
        );

        // 카페 5만 원, 배달 20만 원, 쇼핑 10만 원
        saveTransaction(session, "카페", 50000L);
        saveTransaction(session, "배달", 200000L);
        saveTransaction(session, "쇼핑", 100000L);

        // 목표 20만 원, 카페 잠금
        var result = service.simulate(
                session.getId(),
                200000L,
                List.of("카페")
        );

        assertEquals(350000L, result.monthlyExpenseKrw());
        assertEquals(180000L, result.maxPossibleSavingsKrw());
        assertFalse(result.achievable());
        assertEquals(180000L, result.suggestedGoalKrw());

        // 목표를 10만 원으로 낮추면 달성 가능
        var adjusted = service.simulate(
                session.getId(),
                100000L,
                List.of("카페")
        );

        assertTrue(adjusted.achievable());
        assertEquals(100000L, adjusted.plans().get(0)
                .expectedSavingsKrw());
    }

    private void saveTransaction(
            AnalysisSession session,
            String category,
            Long amount) {

        transactionRepository.save(
                Transaction.builder()
                        .session(session)
                        .slotMonth("2026-10")
                        .transactionDateTime(
                                LocalDateTime.of(2026, 10, 10, 12, 0)
                        )
                        .storeName(category + " 테스트")
                        .amount(amount)
                        .category(category)
                        .build()
        );
    }
}
