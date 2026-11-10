package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer monthsCount; // 1, 2, 3

    @Column(nullable = false, length = 7)
    private String lastMonth; // "2026-10"

    private String region; // "서울" (선택)

    private Integer payday; // 1~31 (선택)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Persona persona;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    private LocalDateTime createdAt;

    @Builder
    public AnalysisSession(Integer monthsCount, String lastMonth, String region, Integer payday, Persona persona) {
        this.monthsCount = monthsCount;
        this.lastMonth = lastMonth;
        this.region = region;
        this.payday = payday;
        this.persona = persona != null ? persona : Persona.COACH;
        this.status = SessionStatus.UPLOADED;
        this.createdAt = LocalDateTime.now();
    }

    public void updateStatus(SessionStatus status) {
        this.status = status;
    }

    public void updatePersona(Persona persona) {
        this.persona = persona;
    }

    public enum Persona {
        COACH, FRIEND, GRANDMOTHER
    }

    public enum SessionStatus {
        UPLOADED, EXTRACTING, PARTIAL_FAILED, REVIEWING, COMPLETED
    }
}