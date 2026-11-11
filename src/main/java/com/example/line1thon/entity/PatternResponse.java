package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "pattern_responses",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"analysis_id", "pattern_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 분석 결과의 패턴인지 식별
    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Column(name = "pattern_id", nullable = false)
    private Long patternId;

    // 사용자가 선택한 원인 태그 목록
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "pattern_response_tags",
            joinColumns = @JoinColumn(name = "pattern_response_id")
    )
    @Column(name = "cause_tag")
    private List<String> causeTags = new ArrayList<>();

    // 사용자가 직접 입력한 원인
    @Column(length = 100)
    private String customReason;

    // ANSWERED, UNKNOWN, NOT_APPLICABLE, SKIPPED
    @Column(nullable = false)
    private String responseStatus;

    // 동시 수정 충돌 방지를 위한 버전
    @Version
    private Long revision;

    public PatternResponse(Long analysisId, Long patternId) {
        this.analysisId = analysisId;
        this.patternId = patternId;
        this.responseStatus = "SKIPPED";
    }

    // 원인 태그 저장 및 수정
    public void update(
            List<String> causeTags,
            String customReason,
            String responseStatus
    ) {
        this.causeTags.clear();

        if (causeTags != null) {
            this.causeTags.addAll(causeTags);
        }

        this.customReason = customReason;
        this.responseStatus = responseStatus;
    }
}
