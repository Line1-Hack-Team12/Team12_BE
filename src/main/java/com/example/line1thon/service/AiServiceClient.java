package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 서비스(FastAPI) 의 JSON 엔드포인트 호출. 계약은 Team12_consume 레포의 backend/F03_INTERFACE.md 6~8절.
 *
 * 모든 호출은 실패 시 Optional.empty() 를 돌려주고 호출자가 템플릿 문구로 대체한다.
 * 4xx 응답 본문에는 AI 가 거른 이유(ANALYSIS_OUTPUT_INVALID 의 errors 등)가 들어 있으므로 warn 으로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiServiceClient {

    private final RestClient aiRestClient;

    // 6절. 가맹점 분류 POST /classify (한 번에 최대 1000개)
    public Optional<ClassifyResponse> classify(List<ClassifyItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        return post("/classify", new ClassifyRequest(items), ClassifyResponse.class);
    }

    // 7절. 설명 생성 POST /narrate (9~11초)
    public Optional<NarrateResponse> narrate(NarrateRequest request) {
        return post("/narrate", request, NarrateResponse.class);
    }

    // 8절. 알림 문구 생성 POST /notify-copy (2~3초)
    public Optional<NotifyCopyResponse> notifyCopy(NotifyCopyRequest request) {
        return post("/notify-copy", request, NotifyCopyResponse.class);
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type) {
        try {
            T response = aiRestClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(type);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException ex) {
            // 422 ANALYSIS_OUTPUT_INVALID / 400 입력 오류: 본문에 이유가 있다
            log.warn("AI {} 실패 status={} body={}", path, ex.getStatusCode().value(), abbreviate(ex.getResponseBodyAsString()));
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("AI {} 호출 실패 (대체 문구 사용): {}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }

    // ===== 값 변환 =====

    public String toAiTone(AnalysisSession.Persona persona) {
        if (persona == null) return "coach";
        return switch (persona) {
            case COACH -> "coach";
            case FRIEND -> "friend";
            case GRANDMOTHER -> "grandma";
        };
    }

    /** 백엔드가 쓰는 한글 분류 라벨 -> AI 카테고리 코드 */
    public String toAiCategory(String koreanCategory) {
        if (koreanCategory == null) return "other";
        return switch (koreanCategory) {
            case "외식", "음식", "주점" -> "food";
            case "배달" -> "delivery";
            case "카페" -> "cafe";
            case "쇼핑" -> "shopping";
            case "택시", "교통" -> "transport";
            case "구독", "생활", "편의점", "주거·통신", "의료" -> "living";
            default -> "other";
        };
    }

    /**
     * AI /classify 결과 -> 백엔드 한글 분류 라벨. 패턴 탐지와 보고서가 이 라벨로 묶는다.
     * 세부 분류가 있으면 그 쪽을 쓴다 (주점, 택시, 구독, 편의점, 여가, 차량, 이체).
     * "이체"(송금·저금·간편결제 충전) 는 총지출에는 들어가지만 패턴 탐지와 보고서 비중에서는 뺀다.
     */
    public String toKoreanCategory(String category, String subcategory) {
        if (category == null) return "기타";
        String sub = subcategory == null ? "" : subcategory;
        return switch (category) {
            case "food" -> "주점".equals(sub) ? "주점" : "외식";
            case "delivery" -> "배달";
            case "cafe" -> "카페";
            case "shopping" -> "쇼핑";
            case "transport" -> "대중교통".equals(sub) ? "교통" : "택시";
            case "living" -> switch (sub) {
                case "구독" -> "구독";
                case "편의점" -> "편의점";
                case "주거·통신" -> "주거·통신";
                case "의료" -> "의료";
                default -> "생활";
            };
            default -> switch (sub) {
                case "이체" -> AnalysisPatternService.TRANSFER_LABEL;
                case "여가" -> "여가";
                case "차량" -> "차량";
                default -> "기타";
            };
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
            @JsonProperty("evidence_transactions") List<EvidenceTxPayload> evidenceTransactions,
            @JsonProperty("chain_transactions") List<EvidenceTxPayload> chainTransactions
    ) {}
    /** AI 가 설명에 인용할 근거 거래. 1~10건, id 중복 불가. 잔액·계좌번호·원문은 보내지 않는다. */
    public record EvidenceTxPayload(
            String id,
            String date,
            String time,
            String merchant,
            @JsonProperty("amount_krw") long amountKrw,
            String category,
            String subcategory
    ) {}
    public record ObservedDto(int hit, int window, String unit) {}
    public record SummaryPayload(
            @JsonProperty("total_spend_krw") long totalSpendKrw,
            @JsonProperty("by_category") List<Map<String, Object>> byCategory,
            @JsonProperty("by_month") List<Map<String, Object>> byMonth,
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
            String tone,
            @JsonProperty("snapshot_id") String snapshotId
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
            List<Map<String, Object>> observations,
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
            @JsonProperty("pattern_id") String patternId,
            String tone,
            String model,
            @JsonProperty("prompt_version") String promptVersion
    ) {}
}
