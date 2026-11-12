package com.example.line1thon.repository;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.DemoNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DemoNotificationRepository extends JpaRepository<DemoNotification, Long> {
    List<DemoNotification> findBySession(AnalysisSession session);
    long countBySessionAndIsEnabledTrue(AnalysisSession session);
}