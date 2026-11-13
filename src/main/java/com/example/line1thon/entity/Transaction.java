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

    // AI /classify 결과 (추출 직후 저장). category 는 food, delivery, cafe, shopping, transport, living, other
    private String category;
    private String subcategory;        // 외식, 주점, 택시, 구독, 여가, 차량 ... (없으면 null)
    private String categoryConfidence; // high, medium, low

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
    public void updateCategory(String category, String subcategory, String confidence) {
        this.category = category;
        this.subcategory = subcategory;
        this.categoryConfidence = confidence;
    }

    /** 충전 되돌림 규칙 등 서비스 규칙으로 kind 를 바꿀 때. 바꾼 이유는 flags 에 남긴다. */
    public void overrideKind(String kind, String reasonFlag) {
        this.kind = kind;
        if (reasonFlag != null && !this.flags.contains(reasonFlag)) {
            this.flags.add(reasonFlag);
        }
    }

    /** 충전 되돌림을 취소한다 (같은 달에 간편결제 결제가 나중에 들어온 경우). */
    public void restoreSelfTransfer(String reasonFlag) {
        this.kind = "self_transfer";
        this.flags.remove(reasonFlag);
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