package com.example.line1thon.analysis;

import java.time.LocalDate;
import java.time.LocalTime;

public record AnalysisTransaction(
        Long transactionId, // 거래 고유 번호
        LocalDate date, // 결제 날짜
        LocalTime time, // 결제 시각
        String merchant, // 가맹점
        long amountKrw, // 금액
        String category, // 상위 카테고리
        String subcategory, // 하위 태그
        String direction // 입출금 구분
) {
}
