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

import com.example.line1thon.repository.TransactionRepository;
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


    // S05: 실제 거래 내역 기반 소비 패턴 분석 결과 조회
    @GetMapping("/{analysisId}/result")
    public ApiResponse<AnalysisResultResponse> getAnalysisResult(
            @PathVariable Long analysisId) {

        // 1. DB 거래 조회 및 F08 패턴 탐지
        var analysis = analysisPatternService.analyze(analysisId);

        // 2. 탐지된 패턴을 프론트 응답 형태로 변환
        var cards = analysis.patterns().stream()
                .map(pattern -> new AnalysisResultResponse.PatternCard(
                        pattern.patternId(),
                        pattern.type(),
                        pattern.condition(),
                        pattern.observedDays(),
                        pattern.sumKrw(),
                        pattern.chainedSumKrw(),
                        pattern.evidenceGrade(),
                        pattern.condition() + "이(가) "
                                + pattern.observedDays() + "일 관찰됐어요.",
                        "이러한 소비가 반복된 이유가 있을까요?",
                        "다음 소비에서는 지출을 한 번 점검해 보세요."
                ))
                .toList();

        // 3. 실제 지출 건수 및 총금액
        var summary = new AnalysisResultResponse.Summary(
                analysis.transactionCount(),
                analysis.totalExpenseKrw(),
                analysis.transactionCount() == 0
                        ? "저장된 소비 내역이 없습니다."
                        : "총 " + analysis.transactionCount()
                        + "건의 지출을 분석했어요."
        );

        // 4. 최종 응답 생성
        var result = new AnalysisResultResponse(
                analysisId,
                null,
                analysis.sessionStatus(),
                summary,
                cards,
                analysis.transactionCount() == 0
                        ? "NO_DATA"
                        : null
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
                responseStatus
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



    // S05: 실제 소비 패턴 기반 말투별 설명 생성
    @PostMapping("/{analysisId}/narratives")
    public ApiResponse<NarrativeRegenerateResponse> regenerateNarratives(
            @PathVariable Long analysisId,
            @RequestBody NarrativeRegenerateRequest request) {

        if (request == null || request.persona() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "말투를 선택해 주세요."
            );
        }

        // 1. 실제 분석 결과 가져오기
        var analysis = analysisPatternService.analyze(analysisId);

        // 2. 탐지된 모든 패턴에 맞춰 문장 생성
        var narratives = analysis.patterns().stream()
                .map(pattern -> {

                    String condition = pattern.condition();
                    int days = pattern.observedDays();

                    return switch (request.persona()) {

                        case COACH -> new PatternNarrative(
                                pattern.patternId(),
                                condition,
                                condition + " 패턴이 " + days + "일 관찰됐어요.",
                                "이 소비가 반복된 이유가 있을까요?",
                                "다음에는 지출을 한 번 점검해 보세요."
                        );

                        case FRIEND -> new PatternNarrative(
                                pattern.patternId(),
                                condition,
                                condition + " 패턴이 " + days + "일이나 나타났네!",
                                "혹시 특별한 이유가 있었어?",
                                "다음에는 한 번 더 생각하고 소비해 보자!"
                        );

                        case GRANDMOTHER -> new PatternNarrative(
                                pattern.patternId(),
                                condition,
                                condition + " 패턴이 " + days + "일이나 있었구나.",
                                "무슨 사정이 있었던 게냐?",
                                "앞으로는 조금씩 살펴보면 좋겠구나."
                        );
                    };
                })
                .toList();

        // 3. 말투별 전체 요약 문장
        String overallComment = switch (request.persona()) {
            case COACH -> "반복되는 소비를 확인하고 작은 실천부터 시작해 보세요.";
            case FRIEND -> "우리 소비 습관을 같이 하나씩 바꿔보자!";
            case GRANDMOTHER -> "작은 습관부터 고쳐나가면 되는 게야.";
        };

        // 4. 최종 응답 생성
        var response = new NarrativeRegenerateResponse(
                analysisId,
                request.persona().name(),
                "TEMPLATE",
                overallComment,
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
