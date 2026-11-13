package com.example.line1thon.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

@Getter
@NoArgsConstructor
@ToString
public class F03ExtractResponse {

    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("slot_month")
    private String slotMonth;

    private String status; // succeeded, partial, failed, no_transactions

    private String model;

    @JsonProperty("extractor_version")
    private String extractorVersion;

    @JsonProperty("prompt_version")
    private String promptVersion;

    private List<AssetDto> assets;

    @Getter
    @NoArgsConstructor
    @ToString
    public static class AssetDto {
        @JsonProperty("asset_id")
        private String assetId;

        private String status; // succeeded, skipped, failed

        @JsonProperty("slot_month")
        private String slotMonth; // /parse 응답에서만: 이 자산(달)의 YYYY-MM

        @JsonProperty("app_guess")
        private String appGuess;

        @JsonProperty("displayed_month")
        private String displayedMonth;

        @JsonProperty("error_code")
        private String errorCode;

        @JsonProperty("error_message")
        private String errorMessage;

        @JsonProperty("elapsed_sec")
        private Double elapsedSec;

        private List<RowDto> rows;
    }

    @Getter
    @NoArgsConstructor
    @ToString
    public static class RowDto {
        private String id; // tx_xxxxxxxxxx

        @JsonProperty("asset_id")
        private String assetId;

        @JsonProperty("source_row_id")
        private String sourceRowId;

        private String date; // YYYY-MM-DD 또는 ""
        private String time; // HH:MM 또는 null
        private String merchant;

        @JsonProperty("amount_krw")
        private Long amountKrw;

        private String direction; // in, out, unknown
        private String kind;      // expense, income, transfer, self_transfer, refund, cancel, unknown
        private String confidence; // high, medium, low

        @JsonProperty("raw_text")
        private String rawText;

        private List<String> flags; // date_missing, merchant_missing, low_confidence 등
    }
}