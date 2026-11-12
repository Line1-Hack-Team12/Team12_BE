package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "demo_notifications")
public class DemoNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AnalysisSession session;

    @Column(nullable = false)
    private String sourceLabel; // 예: "플랜에서 켜짐", "습관 카드에서 켜짐"

    @Column(nullable = false)
    private String scheduleTitle; // 예: "목요일 21:00"

    @Column(nullable = false)
    private String dayCondition; // 예: "목요일"

    @Column(nullable = false)
    private String weatherCondition; // 예: "없음" 또는 "비 오는 날"

    @Column(nullable = false)
    private String triggerCriteria; // 예: "예상 소비 22시의 한 시간 전"

    @Column(nullable = false)
    private String alertTime; // 예: "21:00"

    @Column(nullable = false)
    private Boolean isEnabled; // 켜짐/꺼짐 토글 상태

    @Column(nullable = false)
    private String previewTagLabel; // 예: "야근 태그를 반영한 미리보기"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String previewMessage; // 예: "야근 날 주문을 고민한다면 냉장고부터 확인해 보세요. 목요일 밤 배달이 13주 중 11회 관찰됐어요."

    public void updateSettings(String alertTime, Boolean isEnabled) {
        if (alertTime != null && !alertTime.isBlank()) {
            this.alertTime = alertTime;
            this.scheduleTitle = this.dayCondition + " " + alertTime;
        }
        if (isEnabled != null) {
            this.isEnabled = isEnabled;
        }
    }
}