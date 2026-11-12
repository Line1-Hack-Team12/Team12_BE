package com.example.line1thon.service;

import com.example.line1thon.entity.AnalysisSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiNarrativeService {

    // Existing RestClient bean already points to AI_SERVER_URL (localhost:8001).
    private final RestClient aiRestClient;
    private final ConcurrentMap<CacheKey, Result> cache = new ConcurrentHashMap<>();

    // S05 GET: cache successful AI descriptions by session, persona and pattern snapshot.
    public Result generateCached(Long analysisId,
                                 List<AnalysisPatternService.PatternItem> patterns,
                                 AnalysisSession.Persona persona) {
        CacheKey key = cacheKey(analysisId, patterns, persona);
        Result saved = cache.get(key);
        if (saved != null) return saved;
        Result result = generate(patterns, persona);
        if ("AI".equals(result.status())) cache.putIfAbsent(key, result);
        return result;
    }

    // S05 POST: regenerate on persona selection and refresh the cache.
    public Result regenerate(Long analysisId,
                             List<AnalysisPatternService.PatternItem> patterns,
                             AnalysisSession.Persona persona) {
        Result result = generate(patterns, persona);
        if ("AI".equals(result.status())) cache.put(cacheKey(analysisId, patterns, persona), result);
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

    public Result generate(List<AnalysisPatternService.PatternItem> patterns,
                           AnalysisSession.Persona persona) {
        if (patterns.isEmpty()) {
            return new Result("NO_PATTERNS", "아직 반복 소비 패턴이 발견되지 않았어요.", List.of());
        }

        List<InputPattern> input = patterns.stream()
                .map(p -> new InputPattern(p.patternId(), p.type(),
                        p.condition(), p.observedDays(), p.sumKrw()))
                .toList();

        try {
            PythonResponse response = aiRestClient.post()
                    .uri("/narratives/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PythonRequest(persona.name(), input))
                    .retrieve()
                    .body(PythonResponse.class);

            if (response == null || response.narratives() == null) {
                throw new IllegalStateException("AI 응답이 비어 있습니다.");
            }

            Map<Long, Narrative> byId = new HashMap<>();
            for (Narrative n : response.narratives()) {
                if (n != null && n.patternId() != null) {
                    byId.putIfAbsent(n.patternId(), n);
                }
            }

            List<Narrative> results = new ArrayList<>();
            boolean allAi = true;
            for (var p : patterns) {
                Narrative n = byId.get(p.patternId());
                if (n == null || isBlank(n.observation())
                        || isBlank(n.hypothesisQuestion()) || isBlank(n.action())) {
                    n = template(p, persona);
                    allAi = false;
                }
                results.add(n);
            }
            String overall = isBlank(response.overallComment())
                    ? defaultOverall(persona) : response.overallComment();
            return new Result(allAi ? "AI" : "PARTIAL_TEMPLATE", overall, results);
        } catch (RuntimeException ex) {
            log.warn("S05 AI 설명 생성 실패, 템플릿으로 대체: {}", ex.getClass().getSimpleName());
            return new Result("TEMPLATE", defaultOverall(persona),
                    patterns.stream().map(p -> template(p, persona)).toList());
        }
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
        return new Narrative(p.patternId(), observation, question, action);
    }

    private String defaultOverall(AnalysisSession.Persona persona) {
        return switch (persona) {
            case COACH -> "반복되는 소비를 확인하고 작은 실천부터 시작해 보세요.";
            case FRIEND -> "우리 소비 습관을 같이 하나씩 바꿔보자!";
            case GRANDMOTHER -> "작은 습관부터 고쳐나가면 되는 게야.";
        };
    }

    private record CacheKey(Long analysisId, AnalysisSession.Persona persona, List<String> signature) {}

    public record InputPattern(Long patternId, String type, String condition,
                               int observedDays, long sumKrw) {}
    public record PythonRequest(String persona, List<InputPattern> patterns) {}
    public record PythonResponse(String status, String overallComment,
                                 List<Narrative> narratives) {}
    public record Narrative(Long patternId, String observation,
                            String hypothesisQuestion, String action) {}
    public record Result(String status, String overallComment,
                         List<Narrative> narratives) {}
}
