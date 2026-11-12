package com.example.line1thon.repository;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.SelectedSavingsPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SelectedSavingsPlanRepository extends JpaRepository<SelectedSavingsPlan, Long> {
    Optional<SelectedSavingsPlan> findBySession(AnalysisSession session);
}