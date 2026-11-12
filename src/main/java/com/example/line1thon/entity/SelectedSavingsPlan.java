package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "selected_savings_plans")
public class SelectedSavingsPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private AnalysisSession session;

    @Column(nullable = false)
    private Long targetAmount; // 사용자가 설정한 월 저축 목표액

    @Column(nullable = false)
    private String planCode; // "PLAN_A" 또는 "PLAN_B"

    @Column(nullable = false)
    private String planTitle; // 예: "플랜 A - 배달 중심"

    @Column(nullable = false)
    private Long monthlySavedAmount; // 월 절감 합계액 (예: 56900)

    @Column(nullable = false)
    private Boolean isDemoNotificationEnabled; // 알림 켜짐 여부

    private LocalDateTime selectedAt;

    public void updatePlan(Long targetAmount, String planCode, String planTitle, Long monthlySavedAmount, boolean isDemoNotificationEnabled) {
        this.targetAmount = targetAmount;
        this.planCode = planCode;
        this.planTitle = planTitle;
        this.monthlySavedAmount = monthlySavedAmount;
        this.isDemoNotificationEnabled = isDemoNotificationEnabled;
        this.selectedAt = LocalDateTime.now();
    }
}