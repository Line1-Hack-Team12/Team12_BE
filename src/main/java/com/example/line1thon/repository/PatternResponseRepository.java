package com.example.line1thon.repository;

import com.example.line1thon.entity.PatternResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatternResponseRepository
        extends JpaRepository<PatternResponse, Long> {

    // 분석 ID와 패턴 ID로 저장된 원인 응답 찾기
    Optional<PatternResponse> findByAnalysisIdAndPatternId(
            Long analysisId,
            Long patternId
    );
    // 세션(분석 ID)의 전체 원인 응답 목록 조회
    List<PatternResponse> findByAnalysisId(Long analysisId);
}
