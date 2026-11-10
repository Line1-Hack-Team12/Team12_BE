package com.example.line1thon.repository;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findBySession(AnalysisSession session);
    List<Transaction> findBySessionAndIsExcludedFalse(AnalysisSession session);
}