package com.example.line1thon.dto;

import com.example.line1thon.entity.AnalysisSession;

public record NarrativeRegenerateRequest(
        AnalysisSession.Persona persona
) {
}
