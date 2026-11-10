package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AnalysisSession session;

    @Column(nullable = false, length = 7)
    private String slotMonth; // "2026-08", "2026-09", "2026-10"

    private LocalDateTime transactionDateTime; // 결제 일시

    @Column(nullable = false)
    private String storeName; // 가맹점명

    @Column(nullable = false)
    private Long amount; // 결제 금액

    private String category; // 배달, 택시, 카페 등

    private Double confidence; // AI 신뢰도 점수

    private Boolean isExcluded; // S03 제외 여부

    private Long duplicateGroupId; // 중복 후보 묶음 ID

    @Builder
    public Transaction(AnalysisSession session, String slotMonth, LocalDateTime transactionDateTime,
                       String storeName, Long amount, String category, Double confidence, Long duplicateGroupId) {
        this.session = session;
        this.slotMonth = slotMonth;
        this.transactionDateTime = transactionDateTime;
        this.storeName = storeName;
        this.amount = amount;
        this.category = category;
        this.confidence = confidence != null ? confidence : 1.0;
        this.isExcluded = false;
        this.duplicateGroupId = duplicateGroupId;
    }

    public void updateTransaction(String storeName, Long amount, String category) {
        this.storeName = storeName;
        this.amount = amount;
        this.category = category;
    }

    public void setExcluded(boolean excluded) {
        this.isExcluded = excluded;
    }
}