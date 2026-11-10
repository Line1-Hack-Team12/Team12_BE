package com.example.line1thon.repository;

import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {
    List<UploadedFile> findBySession(AnalysisSession session);
    List<UploadedFile> findBySessionAndStatus(AnalysisSession session, UploadedFile.ProcessStatus status);
}