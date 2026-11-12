package com.example.line1thon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "uploaded_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private AnalysisSession session;

    @Column(nullable = false, length = 7)
    private String slotMonth;

    @Column(nullable = false)
    private String originalFileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessStatus status;

    private String failReason;

    @Builder
    public UploadedFile(AnalysisSession session, String slotMonth, String originalFileName) {
        this.session = session;
        this.slotMonth = slotMonth;
        this.originalFileName = originalFileName;
        this.status = ProcessStatus.PENDING;
    }

    public void markSuccess() {
        this.status = ProcessStatus.SUCCESS;
        this.failReason = null;
    }

    public void markFailed(String reason) {
        this.status = ProcessStatus.FAILED;
        this.failReason = reason;
    }

    public void replaceFile(String newFileName) {
        this.originalFileName = newFileName;
        this.status = ProcessStatus.SUCCESS;
        this.failReason = null;
    }

    public enum ProcessStatus {
        PENDING, SUCCESS, FAILED
    }
}