package com.example.line1thon.controller;


import com.example.line1thon.dto.PatternResponseRequest;
import com.example.line1thon.entity.PatternResponse;
import com.example.line1thon.service.PatternResponseService;
import lombok.RequiredArgsConstructor;
import com.example.line1thon.dto.AnalysisResultResponse;
import com.example.line1thon.global.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import com.example.line1thon.dto.PatternDetailResponse;
import com.example.line1thon.dto.NarrativeRegenerateRequest;
import java.util.List;
import com.example.line1thon.service.AnalysisPatternService;
import com.example.line1thon.service.AiNarrativeService;

import com.example.line1thon.repository.TransactionRepository;
import com.example.line1thon.repository.AnalysisSessionRepository;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;

import org.springframework.web.server.ResponseStatusException;





@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisResultController {

    private final PatternResponseService patternResponseService;
    private final AnalysisPatternService analysisPatternService;
    private final TransactionRepository transactionRepository;
    private final AiNarrativeService aiNarrativeService;
    private final AnalysisSessionRepository sessionRepository;


    // S05: 실제 거래 내역 기반 소비 패턴 분석 결과 및 AI 설명 조회
    @GetMapping("/{analysisId}/result")
    public ApiResponse<AnalysisResultResponse> getAnalysisResult(
            @PathVariable Long analysisId) {

        var analysis = analysisPatternService.analyze(analysisId);
        var persona = sessionRepository.findById(analysisId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "분석 세션이 없습니다."))
                .getPersona();
        var generated = aiNarrativeService.generateCached(
                analysisId, analysis.patterns(), persona);

        var cards = analysis.patterns().stream()
                .map(pattern -> {
                    var text = generated.narratives().stream()
                            .filter(n -> pattern.patternId().equals(n.patternId()))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "설명 생성 결과가 누락되었습니다."));
                    return new AnalysisResultResponse.PatternCard(
                            pattern.patternId(),
                            pattern.type(),
                            pattern.condition(),
                            pattern.observedDays(),
                            pattern.sumKrw(),
                            pattern.chainedSumKrw(),
                            pattern.evidenceGrade(),
                            text.observation(),
                            text.hypothesisQuestion(),
                            text.action()
                    );
                }).toList();

        var summary = new AnalysisResultResponse.Summary(
                analysis.transactionCount(),
                analysis.totalExpenseKrw(),
                analysis.transactionCount() == 0
                        ? "저장된 소비 내역이 없습니다."
                        : generated.overallComment()
        );

        var result = new AnalysisResultResponse(
                analysisId,
                null,
                analysis.sessionStatus(),
                summary,
                cards,
                analysis.transactionCount() == 0 ? "NO_DATA" : null
        );
        return ApiResponse.ok(result);
    }

    // S05-1: 실제 소비 패턴 상세 조회
    @GetMapping("/{analysisId}/patterns/{patternId}")
    public ApiResponse<PatternDetailResponse> getPatternDetail(
            @PathVariable Long analysisId,
            @PathVariable Long patternId) {

        // 1. 분석 결과 조회
        var analysis = analysisPatternService.analyze(analysisId);

        // 2. 요청한 패턴 찾기
        var pattern = analysis.patterns().stream()
                .filter(p -> p.patternId().equals(patternId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "해당 소비 패턴을 찾을 수 없습니다."
                ));


        // 3. 패턴의 근거 거래 조회
        var transactions = transactionRepository
                .findAllById(pattern.evidenceTransactionIds())
                .stream()
                .map(t -> new PatternDetailResponse.EvidenceTransaction(
                        t.getId(),
                        t.getDate(),
                        t.getTime(),
                        t.getStoreName(),
                        t.getAmount() == null ? 0L : t.getAmount()
                ))
                .toList();

        // 4. 사용자가 저장한 원인 태그 조회
        PatternResponse savedResponse =
                patternResponseService.findResponse(
                        analysisId, patternId
                );

        List<String> causeTags = savedResponse == null
                ? List.of()
                : savedResponse.getCauseTags();

        String responseStatus = savedResponse == null
                ? "SKIPPED"
                : savedResponse.getResponseStatus();

        // 5. 상세 응답 생성
        var detail = new PatternDetailResponse(
                analysisId,
                patternId,
                pattern.type(),
                pattern.condition(),
                pattern.observedDays(),
                pattern.sumKrw(),
                pattern.chainedSumKrw(),
                pattern.evidenceGrade(),
                pattern.condition() + "이(가) "
                        + pattern.observedDays() + "일 관찰됐어요.",
                "이러한 소비가 반복된 이유가 있을까요?",
                "다음 소비에서는 지출을 한 번 점검해 보세요.",
                transactions,
                causeTags,
                responseStatus,
                savedResponse == null ? null : savedResponse.getCustomReason(),
                savedResponse == null ? 0L : savedResponse.getRevision()
        );

        return ApiResponse.ok(detail);
    }



    // S05-1: 소비 원인 태그 저장 및 수정
    @PutMapping("/{analysisId}/patterns/{patternId}/response")
    public ApiResponse<PatternSaveResponse> savePatternResponse(
            @PathVariable Long analysisId,
            @PathVariable Long patternId,
            @RequestBody PatternResponseRequest request) {

        PatternResponse saved = patternResponseService.saveOrUpdate(
                analysisId,
                patternId,
                request
        );

        PatternSaveResponse response = new PatternSaveResponse(
                saved.getAnalysisId(),
                saved.getPatternId(),
                saved.getCauseTags(),
                saved.getCustomReason(),
                saved.getResponseStatus(),
                saved.getRevision()
        );

        return ApiResponse.ok("원인 태그가 저장되었습니다.", response);
    }

    // 프론트에 전달할 저장 결과
    public record PatternSaveResponse(
            Long analysisId,
            Long patternId,
            List<String> causeTags,
            String customReason,
            String responseStatus,
            Long revision
    ) {}



    // S05: 실제 소비 패턴 기반 AI 말투별 설명 재생성
    @PostMapping("/{analysisId}/narratives")
    public ApiResponse<NarrativeRegenerateResponse> regenerateNarratives(
            @PathVariable Long analysisId,
            @RequestBody NarrativeRegenerateRequest request) {

        if (request == null || request.persona() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "말투를 선택해 주세요.");
        }

        var analysis = analysisPatternService.analyze(analysisId);
        var generated = aiNarrativeService.regenerate(
                analysisId, analysis.patterns(), request.persona());

        var narratives = analysis.patterns().stream()
                .map(pattern -> {
                    var text = generated.narratives().stream()
                            .filter(n -> pattern.patternId().equals(n.patternId()))
                            .findFirst()
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "설명 생성 결과가 누락되었습니다."));
                    return new PatternNarrative(
                            pattern.patternId(),
                            pattern.condition(),
                            text.observation(),
                            text.hypothesisQuestion(),
                            text.action()
                    );
                })
                .toList();

        var response = new NarrativeRegenerateResponse(
                analysisId,
                request.persona().name(),
                generated.status(),
                generated.overallComment(),
                narratives
        );
        return ApiResponse.ok(response);
    }

    // 말투 변경 API 응답
    public record NarrativeRegenerateResponse(
            Long analysisId,
            String persona,
            String status,
            String overallComment,
            List<PatternNarrative> narratives
    ) {}

    // 카드별 설명 문장
    public record PatternNarrative(
            Long patternId,
            String title,
            String observation,
            String hypothesisQuestion,
            String action
    ) {}



}
