package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestClient aiRestClient;

    // 6절. 가맹점 분류 POST /classify
    public Optional<ClassifyResponse> classify(List<ClassifyItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        try {
            ClassifyResponse response = aiRestClient.post()
                    .uri("/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClassifyRequest(items))
                    .retrieve()
                    .body(ClassifyResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.debug("AI /classify 미구현 또는 호출 실패 (로컬 분류기 Fallback): {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // 7절. 설명 생성 POST /narrate
    public Optional<NarrateResponse> narrate(NarrateRequest request) {
        try {
            NarrateResponse response = aiRestClient.post()
                    .uri("/narrate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(NarrateResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.debug("AI /narrate 미구현 또는 호출 실패 (백엔드 템플릿 Fallback): {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // 8절. 알림 문구 생성 POST /notify-copy
    public Optional<NotifyCopyResponse> notifyCopy(NotifyCopyRequest request) {
        try {
            NotifyCopyResponse response = aiRestClient.post()
                    .uri("/notify-copy")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(NotifyCopyResponse.class);
            return Optional.ofNullable(response);
        } catch (RuntimeException ex) {
            log.debug("AI /notify-copy 미구현 또는 호출 실패 (백엔드 문구 Fallback): {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public String toAiTone(AnalysisSession.Persona persona) {
        if (persona == null) return "coach";
        return switch (persona) {
            case COACH -> "coach";
            case FRIEND -> "friend";
            case GRANDMOTHER -> "grandma";
        };
    }

    public String toAiCategory(String koreanCategory) {
        if (koreanCategory == null) return "other";
        return switch (koreanCategory) {
            case "외식", "음식" -> "food";
            case "배달" -> "delivery";
            case "카페" -> "cafe";
            case "쇼핑" -> "shopping";
            case "택시", "교통" -> "transport";
            case "구독", "생활", "편의점" -> "living";
            default -> "other";
        };
    }

    // ===== 6절 DTO =====
    public record ClassifyRequest(List<ClassifyItemRequest> items) {}
    public record ClassifyItemRequest(
            String merchant,
            String kind,
            @JsonProperty("raw_text") String rawText
    ) {}
    public record ClassifyResponse(
            List<ClassifyItemResult> items,
            String model,
            @JsonProperty("prompt_version") String promptVersion
    ) {}
    public record ClassifyItemResult(
            String merchant,
            String category,
            String subcategory,
            String confidence
    ) {}

    // ===== 7절 DTO =====
    public record NarrateRequest(
            @JsonProperty("snapshot_id") String snapshotId,
            String tone,
            PeriodDto period,
            List<String> want,
            List<PatternPayload> patterns,
            SummaryPayload summary,
            List<SubscriptionPayload> subscriptions
    ) {}
    public record PeriodDto(String start, String end, List<String> slots) {}
    public record PatternPayload(
            @JsonProperty("pattern_id") String patternId,
            String type,
            Map<String, Object> condition,
            ObservedDto observed,
            @JsonProperty("sum_krw") long sumKrw,
            @JsonProperty("chained_sum_krw") long chainedSumKrw,
            @JsonProperty("evidence_grade") String evidenceGrade,
            @JsonProperty("evidence_transactions") List evidenceTransactions,
            @JsonProperty("chain_transactions") List chainTransactions
    ) {}
    public record ObservedDto(int hit, int window, String unit) {}
    public record SummaryPayload(
            @JsonProperty("total_spend_krw") long totalSpendKrw,
            @JsonProperty("by_category") List byCategory,
            @JsonProperty("by_month") List byMonth,
            Map<String, Object> limits
    ) {}
    public record SubscriptionPayload(
            String merchant,
            @JsonProperty("monthly_krw") long monthlyKrw,
            @JsonProperty("observed_months") int observedMonths,
            @JsonProperty("price_increased") boolean priceIncreased
    ) {}
    public record NarrateResponse(
            List<NarrateCard> cards,
            @JsonProperty("summary_line") String summaryLine,
            NarrateReport report,
            String model,
            @JsonProperty("prompt_version") String promptVersion,
            String tone
    ) {}
    public record NarrateCard(
            @JsonProperty("pattern_id") String patternId,
            String title,
            String observation,
            @JsonProperty("hypothesis_question") String hypothesisQuestion,
            String action,
            @JsonProperty("recommended_tags") List<String> recommendedTags,
            @JsonProperty("evidence_transaction_ids") List<String> evidenceTransactionIds
    ) {}
    public record NarrateReport(
            List<String> overview,
            List observations,
            List<String> actions,
            @JsonProperty("limits_sentence") String limitsSentence
    ) {}

    // ===== 8절 DTO =====
    public record NotifyCopyRequest(
            String tone,
            PatternPayload pattern,
            @JsonProperty("cause_tags") List<String> causeTags,
            @JsonProperty("scheduled_time") String scheduledTime
    ) {}
    public record NotifyCopyResponse(
            String title,
            String body,
            String notice,
            String model,
            @JsonProperty("prompt_version") String promptVersion
    ) {}
}