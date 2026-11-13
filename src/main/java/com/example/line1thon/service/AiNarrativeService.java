package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.repository.AnalysisSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * S05 패턴 카드 문장과 S07 보고서 문장. AI /narrate (7절) 를 한 번 불러 cards + summary + report 를 받고
 * (세션, 말투, 패턴 스냅샷) 단위로 캐시한다. AI 가 실패하거나 응답이 비면 템플릿 문장으로 대체한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNarrativeService {

    private static final List<String> WANT = List.of("cards", "summary", "report");

    private final AiServiceClient aiServiceClient;
    private final AiPayloadMapper payloadMapper;
    private final AnalysisSessionRepository sessionRepository;
    private final ConcurrentMap<CacheKey, Result> cache = new ConcurrentHashMap<>();

    // S05 GET: cache successful AI descriptions by session, persona and pattern snapshot.
    public Result generateCached(Long analysisId,
                                 List<AnalysisPatternService.PatternItem> patterns,
                                 AnalysisSession.Persona persona) {
        return generateCached(analysisId, patterns, persona, List.of());
    }

    public Result generateCached(Long analysisId,
                                 List<AnalysisPatternService.PatternItem> patterns,
                                 AnalysisSession.Persona persona,
                                 List<AiServiceClient.SubscriptionPayload> subscriptions) {
        CacheKey key = cacheKey(analysisId, patterns, persona);
        Result saved = cache.get(key);
        if (saved != null) return saved;
        Result result = generate(analysisId, patterns, persona, subscriptions);
        if (result.fromAi()) cache.putIfAbsent(key, result);
        return result;
    }

    // S05 POST: regenerate on persona selection and refresh the cache.
    public Result regenerate(Long analysisId,
                             List<AnalysisPatternService.PatternItem> patterns,
                             AnalysisSession.Persona persona) {
        Result result = generate(analysisId, patterns, persona, List.of());
        if (result.fromAi()) cache.put(cacheKey(analysisId, patterns, persona), result);
        return result;
    }

    private CacheKey cacheKey(Long analysisId,
                              List<AnalysisPatternService.PatternItem> patterns,
                              AnalysisSession.Persona persona) {
        List<String> signature = patterns.stream()
                .map(p -> p.patternId() + ":" + p.observedDays() + ":"
                        + p.sumKrw() + ":" + p.condition())
                .toList();
        return new CacheKey(analysisId, persona, signature);
    }

    public Result generate(Long analysisId,
                           List<AnalysisPatternService.PatternItem> patterns,
                           AnalysisSession.Persona persona,
                           List<AiServiceClient.SubscriptionPayload> subscriptions) {
        if (patterns.isEmpty()) {
            return new Result("NO_PATTERNS", "아직 반복 소비 패턴이 발견되지 않았어요.", List.of(), null, null);
        }
        AnalysisSession.Persona tone = persona != null ? persona : AnalysisSession.Persona.COACH;
        Optional<AnalysisSession> sessionOpt = sessionRepository.findById(analysisId);
        if (sessionOpt.isEmpty()) {
            return templateResult(patterns, tone);
        }

        try {
            AiServiceClient.NarrateRequest base = payloadMapper.toNarrateRequest(sessionOpt.get(), patterns, WANT, subscriptions);
            // 말투는 호출자가 고른 persona (S05 재생성에서 세션 persona 와 다를 수 있다)
            AiServiceClient.NarrateRequest request = new AiServiceClient.NarrateRequest(
                    base.snapshotId(), aiServiceClient.toAiTone(tone), base.period(), base.want(),
                    base.patterns(), base.summary(), base.subscriptions());

            if (request.patterns().isEmpty()) {
                log.warn("S05 근거 거래가 없는 패턴뿐이라 AI 설명을 건너뜀 analysisId={}", analysisId);
                return templateResult(patterns, tone);
            }

            Optional<AiServiceClient.NarrateResponse> opt = aiServiceClient.narrate(request);
            if (opt.isEmpty() || opt.get().cards() == null) {
                return templateResult(patterns, tone);
            }
            AiServiceClient.NarrateResponse response = opt.get();

            Map<Long, AiServiceClient.NarrateCard> byId = new HashMap<>();
            for (AiServiceClient.NarrateCard c : response.cards()) {
                Long id = AiPayloadMapper.parsePatternId(c.patternId());
                if (id != null) byId.putIfAbsent(id, c);
            }

            List<Narrative> results = new ArrayList<>();
            boolean allAi = true;
            for (var p : patterns) {
                AiServiceClient.NarrateCard c = byId.get(p.patternId());
                if (c == null || isBlank(c.observation()) || isBlank(c.hypothesisQuestion()) || isBlank(c.action())) {
                    results.add(template(p, tone));
                    allAi = false;
                } else {
                    results.add(new Narrative(p.patternId(), c.observation(), c.hypothesisQuestion(), c.action(),
                            c.title(), c.recommendedTags() == null ? List.of() : c.recommendedTags(),
                            AiPayloadMapper.parseTxIds(c.evidenceTransactionIds())));
                }
            }
            String overall = isBlank(response.summaryLine()) ? defaultOverall(tone) : response.summaryLine();
            return new Result(allAi ? "AI" : "PARTIAL_TEMPLATE", overall, results, response.summaryLine(), response.report());
        } catch (RuntimeException ex) {
            log.warn("S05 AI 설명 생성 실패, 템플릿으로 대체: {}", ex.toString());
            return templateResult(patterns, tone);
        }
    }

    private Result templateResult(List<AnalysisPatternService.PatternItem> patterns, AnalysisSession.Persona persona) {
        return new Result("TEMPLATE", defaultOverall(persona),
                patterns.stream().map(p -> template(p, persona)).toList(), null, null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Narrative template(AnalysisPatternService.PatternItem p,
                               AnalysisSession.Persona persona) {
        String observation = switch (persona) {
            case COACH -> p.condition() + " 패턴이 " + p.observedDays() + "일 관찰됐어요.";
            case FRIEND -> p.condition() + " 패턴이 " + p.observedDays() + "일이나 나타났네!";
            case GRANDMOTHER -> p.condition() + " 패턴이 " + p.observedDays() + "일이나 있었구나.";
        };
        String question = switch (persona) {
            case COACH -> "이 소비가 반복된 이유가 있을까요?";
            case FRIEND -> "혹시 특별한 이유가 있었어?";
            case GRANDMOTHER -> "무슨 사정이 있었던 게냐?";
        };
        String action = switch (persona) {
            case COACH -> "다음에는 지출을 한 번 점검해 보세요.";
            case FRIEND -> "다음에는 한 번 더 생각하고 소비해 보자!";
            case GRANDMOTHER -> "앞으로는 조금씩 살펴보면 좋겠구나.";
        };
        return new Narrative(p.patternId(), observation, question, action, p.condition(), List.of(),
                p.evidenceTransactionIds());
    }

    private String defaultOverall(AnalysisSession.Persona persona) {
        return switch (persona) {
            case COACH -> "반복되는 소비를 확인하고 작은 실천부터 시작해 보세요.";
            case FRIEND -> "우리 소비 습관을 같이 하나씩 바꿔보자!";
            case GRANDMOTHER -> "작은 습관부터 고쳐나가면 되는 게야.";
        };
    }

    private record CacheKey(Long analysisId, AnalysisSession.Persona persona, List<String> signature) {}

    /** 패턴 카드 문장. title/recommendedTags/evidenceTransactionIds 는 AI 응답일 때만 의미가 있다. */
    public record Narrative(Long patternId, String observation,
                            String hypothesisQuestion, String action,
                            String title, List<String> recommendedTags,
                            List<Long> evidenceTransactionIds) {}

    /** status: AI / PARTIAL_TEMPLATE / TEMPLATE / NO_PATTERNS. summaryLine, report 는 AI 응답일 때만. */
    public record Result(String status, String overallComment,
                         List<Narrative> narratives,
                         String summaryLine,
                         AiServiceClient.NarrateReport report) {
        /** AI 응답을 받은 결과인가 (일부 카드가 템플릿이어도 캐시한다. 패턴이 10개를 넘으면 11번째부터는 늘 템플릿). */
        public boolean fromAi() {
            return "AI".equals(status) || "PARTIAL_TEMPLATE".equals(status);
        }
    }
}
