package com.example.line1thon.dto;

import java.util.List;

// S05-1: 소비 원인 태그 저장 및 수정 요청
public record PatternResponseRequest(
        List<String> causeTags,
        String customReason,
        String responseStatus,
        Long expectedRevision
) {
}
