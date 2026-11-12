package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AnalysisSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_file_id")
    private UploadedFile uploadedFile;

    private String txId;
    private String date;
    private String time;
    private String storeName;
    private Long amount;

    private String direction;  // in, out, unknown
    private String kind;       // expense, income, transfer, self_transfer, refund, cancel, unknown
    private String confidence; // high, medium, low

    @Column(columnDefinition = "TEXT")
    private String rawText;

    @ElementCollection
    @CollectionTable(name = "transaction_flags", joinColumns = @JoinColumn(name = "transaction_id"))
    @Column(name = "flag")
    @Builder.Default
    private List<String> flags = new ArrayList<>();

    @Builder.Default
    private Boolean isExcluded = false;

    // ================= 비즈니스 로직 추가 ================= //

    /**
     * 검토 큐(확인 필요) 대상 여부 판별
     */
    public boolean isReviewNeeded() {
        if (this.flags == null || this.flags.isEmpty()) {
            return false;
        }
        return this.flags.contains("date_missing")
                || this.flags.contains("merchant_missing")
                || this.flags.contains("unknown_direction")
                || this.flags.contains("low_confidence")
                || this.flags.contains("month_mismatch");
    }

    /**
     * F07 지출 합계 포함 여부 판별
     * - self_transfer: 본인 계좌 간 이동 제외
     * - refund / cancel: 환불/취소 (금액 차감 대상이거나 별도 처리)
     * - expense + out, transfer + out: 일반 지출
     */
    public boolean isCountedAsExpense() {
        if (Boolean.TRUE.equals(this.isExcluded)) {
            return false;
        }
        if ("self_transfer".equals(this.kind)) {
            return false;
        }
        return ("expense".equals(this.kind) && "out".equals(this.direction))
                || ("transfer".equals(this.kind) && "out".equals(this.direction));
    }
    public void updateDetails(String date, String time, String storeName, Long amount, boolean isExcluded) {
        if (date != null) {
            this.date = date;
        }
        if (time != null) {
            this.time = time;
        }
        if (storeName != null) {
            this.storeName = storeName;
        }
        if (amount != null) {
            this.amount = amount;
        }
        this.isExcluded = isExcluded;
    }
}