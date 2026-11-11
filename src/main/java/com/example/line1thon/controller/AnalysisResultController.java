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


@RestController
@RequestMapping("/api/analyses")
@RequiredArgsConstructor
public class AnalysisResultController {

    private final PatternResponseService patternResponseService;

    // S05: 소비 패턴 분석 결과 조회 (데모 버전)
    @GetMapping("/{analysisId}/result")
    public ApiResponse<AnalysisResultResponse> getAnalysisResult(
            @PathVariable Long analysisId) {

        // TODO: 실제 snapshot 기반 집계로 교체
        var summary = new AnalysisResultResponse.Summary(
                3,
                60000L,
                "데모 데이터입니다. 실제 분석 결과가 아닙니다."
        );

        // TODO: PatternDetector 결과와 연결
        var card = new AnalysisResultResponse.PatternCard(
                1L,
                "WEEKDAY",
                "목요일 배달 소비",
                3,
                60000L,
                0L,
                "NORMAL",
                "목요일에 배달 소비가 3일 관찰됐어요.",
                "목요일에 배달을 주문하는 이유가 있을까요?",
                "이번 주에는 배달 주문을 한 번 줄여보세요."
        );

        var result = new AnalysisResultResponse(
                analysisId,
                null,
                "DEMO",
                summary,
                List.of(card),
                "DEMO_DATA"
        );

        return ApiResponse.ok(result);
    }


    // S05-1: 소비 패턴 카드 상세 조회 (데모 버전)
    @GetMapping("/{analysisId}/patterns/{patternId}")
    public ApiResponse<PatternDetailResponse> getPatternDetail(
            @PathVariable Long analysisId,
            @PathVariable Long patternId) {

        // 데모에서는 패턴 ID 1번만 제공
        if (!patternId.equals(1L)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "해당 소비 패턴을 찾을 수 없습니다."
            );
        }

        // TODO: 실제 snapshot 거래 내역으로 교체
        var evidenceTransactions = List.of(
                new PatternDetailResponse.EvidenceTransaction(
                        1L, "2026-10-01", "22:00",
                        "배민", 20000L
                ),
                new PatternDetailResponse.EvidenceTransaction(
                        2L, "2026-10-08", "21:30",
                        "쿠팡이츠", 15000L
                ),
                new PatternDetailResponse.EvidenceTransaction(
                        3L, "2026-10-15", "22:15",
                        "배민", 25000L
                )
        );


        // DB에 저장된 소비 원인 태그 조회
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


        var detail = new PatternDetailResponse(
                analysisId,
                patternId,
                "WEEKDAY",
                "목요일 배달 소비",
                3,
                60000L,
                0L,
                "NORMAL",
                "목요일에 배달 소비가 3일 관찰됐어요.",
                "목요일에 배달을 주문하는 이유가 있을까요?",
                "이번 주에는 배달 주문을 한 번 줄여보세요.",
                evidenceTransactions,
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


    // S05: 말투 변경에 따른 설명 재생성 (데모 버전)
    @PostMapping("/{analysisId}/narratives")
    public ApiResponse<NarrativeRegenerateResponse> regenerateNarratives(
            @PathVariable Long analysisId,
            @RequestBody NarrativeRegenerateRequest request) {

        if (request == null || request.persona() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "말투를 선택해 주세요."
            );
        }

        // TODO: 실제 패턴 데이터 및 AI 문장 생성 연결
        PatternNarrative narrative = switch (request.persona()) {

            case COACH -> new PatternNarrative(
                    1L,
                    "목요일 배달 소비",
                    "목요일에 배달 소비가 3일 관찰됐어요.",
                    "목요일에 배달을 주문하는 이유가 있을까요?",
                    "이번 주에는 배달 주문을 한 번 줄여보세요."
            );

            case FRIEND -> new PatternNarrative(
                    1L,
                    "목요일마다 배달을 시켰네!",
                    "목요일에 배달 주문한 날이 3일이나 있네!",
                    "목요일에는 유독 바쁜 일이 있었어?",
                    "이번 주에는 한 번만 직접 만들어 먹어볼까?"
            );

            case GRANDMOTHER -> new PatternNarrative(
                    1L,
                    "목요일마다 배달을 시켰구나!",
                    "목요일에 배달을 시킨 날이 3일이나 되는구나.",
                    "그날마다 무슨 사정이 있었던 게냐?",
                    "이번 주엔 배달을 한 번만 줄여보려무나."
            );
        };

        String overallComment = switch (request.persona()) {
            case COACH -> "반복되는 소비를 확인하고 작은 실천부터 시작해 보세요.";
            case FRIEND -> "우리 소비 습관을 같이 하나씩 바꿔보자!";
            case GRANDMOTHER -> "작은 습관부터 고쳐나가면 되는 게야.";
        };

        var response = new NarrativeRegenerateResponse(
                analysisId,
                request.persona().name(),
                "DEMO_TEMPLATE",
                overallComment,
                List.of(narrative)
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
