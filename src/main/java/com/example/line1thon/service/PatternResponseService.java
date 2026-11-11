package com.example.line1thon.service;

import com.example.line1thon.dto.PatternResponseRequest;
import com.example.line1thon.entity.PatternResponse;
import com.example.line1thon.repository.PatternResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PatternResponseService {

    private final PatternResponseRepository repository;

    // 기능 명세에 정의된 원인 태그
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "overtime",
            "rain",
            "stress",
            "habit",
            "no_specific_reason",
            "custom"
    );

    private static final Set<String> ALLOWED_STATUSES = Set.of(
            "ANSWERED",
            "UNKNOWN",
            "NOT_APPLICABLE",
            "SKIPPED"
    );

    // S05-1: 원인 태그 저장 및 수정
    @Transactional
    public PatternResponse saveOrUpdate(
            Long analysisId,
            Long patternId,
            PatternResponseRequest request) {

        // 1. 요청값 검증
        if (analysisId == null || analysisId <= 0
                || patternId == null || patternId <= 0
                || request == null
                || request.expectedRevision() == null
                || request.expectedRevision() < 0) {
            throw badRequest("잘못된 요청입니다.");
        }

        // TODO: 실제 패턴 DB와 연결하여 존재 여부 확인
        // 현재 데모에는 패턴 1번만 있음
        if (!patternId.equals(1L)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "해당 소비 패턴을 찾을 수 없습니다."
            );
        }

        String status = request.responseStatus();

        if (status == null
                || !ALLOWED_STATUSES.contains(status)) {
            throw badRequest("올바르지 않은 응답 상태입니다.");
        }

        List<String> tags = request.causeTags() == null
                ? List.of()
                : request.causeTags();

        // 2. 원인 태그 검증
        if (tags.stream().anyMatch(tag ->
                tag == null || !ALLOWED_TAGS.contains(tag))) {
            throw badRequest("허용되지 않은 원인 태그입니다.");
        }

        if (new HashSet<>(tags).size() != tags.size()) {
            throw badRequest("중복된 원인 태그가 있습니다.");
        }

        String customReason = request.customReason();

        if (customReason != null) {
            customReason = customReason.trim();
            if (customReason.isEmpty()) {
                customReason = null;
            }
        }

        if (customReason != null
                && customReason.length() > 100) {
            throw badRequest("직접 입력은 100자 이하여야 합니다.");
        }

        if ("ANSWERED".equals(status)) {
            if (tags.isEmpty()) {
                throw badRequest("원인 태그를 선택해 주세요.");
            }

            if (tags.contains("custom") && customReason == null) {
                throw badRequest("직접 입력한 원인을 작성해 주세요.");
            }

            if (!tags.contains("custom") && customReason != null) {
                throw badRequest("직접 입력 태그가 필요합니다.");
            }

        } else {
            // 모르겠음 / 해당 없음 / 건너뛰기는 태그 없음
            if (!tags.isEmpty() || customReason != null) {
                throw badRequest("이 상태에서는 원인 태그를 저장할 수 없습니다.");
            }
        }

        // 3. 기존 응답 조회
        PatternResponse entity = repository
                .findByAnalysisIdAndPatternId(analysisId, patternId)
                .orElse(null);

        // 4. 수정 버전 확인
        long currentRevision = entity == null
                ? 0L
                : entity.getRevision();

        if (request.expectedRevision() != currentRevision) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "다른 수정이 먼저 저장되었습니다."
            );
        }

        // 5. 처음 저장하는 경우 새 객체 생성
        if (entity == null) {
            entity = new PatternResponse(analysisId, patternId);
        }

        // 6. 내용 업데이트 후 DB 저장
        entity.update(tags, customReason, status);

        return repository.saveAndFlush(entity);
    }


    // S05-1: 저장된 소비 원인 태그 조회
    @Transactional(readOnly = true)
    public PatternResponse findResponse(
            Long analysisId,
            Long patternId) {

        return repository
                .findByAnalysisIdAndPatternId(analysisId, patternId)
                .orElse(null);
    }


    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
