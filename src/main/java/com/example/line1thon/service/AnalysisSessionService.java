package com.example.line1thon.service;

import com.example.line1thon.dto.*;
import com.example.line1thon.entity.AnalysisSession;
import com.example.line1thon.entity.UploadedFile;
import com.example.line1thon.repository.AnalysisSessionRepository;
import com.example.line1thon.repository.UploadedFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisSessionService {

    private final AnalysisSessionRepository sessionRepository;
    private final UploadedFileRepository fileRepository;

    @Transactional
    public Long createSessionWithFiles(Integer monthsCount, String lastMonth, String region, Integer payday,
                                       AnalysisSession.Persona persona, Boolean aiConsent,
                                       List<String> slotMonths, List<MultipartFile> files) {
        if (aiConsent == null || !aiConsent) {
            throw new IllegalArgumentException("외부 AI 전송에 동의해야 분석을 시작할 수 있습니다.");
        }

        AnalysisSession session = AnalysisSession.builder()
                .monthsCount(monthsCount)
                .lastMonth(lastMonth)
                .region(region)
                .payday(payday)
                .persona(persona)
                .build();
        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);
        AnalysisSession savedSession = sessionRepository.save(session);

        if (files != null && slotMonths != null) {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String slotMonth = i < slotMonths.size() ? slotMonths.get(i) : lastMonth;

                UploadedFile uploadedFile = UploadedFile.builder()
                        .session(savedSession)
                        .slotMonth(slotMonth)
                        .originalFileName(file.getOriginalFilename())
                        .build();

                uploadedFile.markSuccess();
                fileRepository.save(uploadedFile);
            }
        }

        return savedSession.getId();
    }

    public SessionDetailResponse getSessionStatus(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> files = fileRepository.findBySession(session);

        int totalCount = files.isEmpty() ? 8 : files.size();

        List<UploadedFile> failed = files.stream()
                .filter(f -> f.getStatus() == UploadedFile.ProcessStatus.FAILED)
                .toList();

        List<SessionDetailResponse.StepDto> steps = List.of(
                SessionDetailResponse.StepDto.builder().stepNumber(1).name("캡처 읽기").status("COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(2).name("가맹점 분류")
                        .status(session.getStatus() == AnalysisSession.SessionStatus.EXTRACTING ? "IN_PROGRESS" : "COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(3).name("중복 정리").status("WAITING").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(4).name("패턴 후보 찾기").status("WAITING").build()
        );

        List<SessionDetailResponse.FileStatusDto> fileStatuses = new ArrayList<>();
        if (!files.isEmpty()) {
            for (int i = 0; i < files.size(); i++) {
                UploadedFile f = files.get(i);
                String text = (f.getStatus() == UploadedFile.ProcessStatus.FAILED) ? "실패" : (i <= 2 ? "완료" : "읽는 중");
                fileStatuses.add(SessionDetailResponse.FileStatusDto.builder()
                        .fileId(f.getId())
                        .fileName(f.getOriginalFileName())
                        .statusText(text)
                        .build());
            }
        } else {
            fileStatuses.add(SessionDetailResponse.FileStatusDto.builder().fileId(1L).fileName("8월_01.png").statusText("완료").build());
            fileStatuses.add(SessionDetailResponse.FileStatusDto.builder().fileId(2L).fileName("8월_02.png").statusText("완료").build());
            fileStatuses.add(SessionDetailResponse.FileStatusDto.builder().fileId(3L).fileName("9월_01.png").statusText("읽는 중").build());
        }

        List<SessionDetailResponse.FailedFileDto> failedDtos = failed.stream()
                .map(f -> SessionDetailResponse.FailedFileDto.builder()
                        .fileId(f.getId())
                        .fileName(f.getOriginalFileName())
                        .slotMonth(f.getSlotMonth())
                        .reason(f.getFailReason())
                        .build())
                .collect(Collectors.toList());

        String guide = (session.getStatus() == AnalysisSession.SessionStatus.PARTIAL_FAILED)
                ? "성공한 " + (totalCount - failed.size()) + "장의 거래는 그대로 유지돼요. 다시 시도해도 같은 거래가 중복으로 생기지 않아요."
                : "성공한 파일은 유지하고, 실패한 파일만 다시 시도할 수 있어요.";

        int completed = (session.getStatus() == AnalysisSession.SessionStatus.EXTRACTING) ? 3 : totalCount - failed.size();
        int progress = (int) Math.round(((double) completed / totalCount) * 100);

        return SessionDetailResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .totalCount(totalCount)
                .completedCount(completed)
                .progressPercentage(progress)
                .currentStep(2)
                .steps(steps)
                .fileStatuses(fileStatuses)
                .failedFiles(failedDtos)
                .guideMessage(guide)
                .build();
    }

    @Transactional
    public void simulateFail(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.PARTIAL_FAILED);

        List<UploadedFile> files = fileRepository.findBySession(session);
        if (!files.isEmpty()) {
            files.get(files.size() - 1).markFailed("소비 내역이 아니어서 제외했어요.");
        } else {
            UploadedFile dummyFail = UploadedFile.builder()
                    .session(session)
                    .slotMonth("2026-10")
                    .originalFileName("10월_03.png")
                    .build();
            dummyFail.markFailed("소비 내역이 아니어서 제외했어요.");
            fileRepository.save(dummyFail);
        }
    }

    @Transactional
    public void simulateComplete(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    @Transactional
    public void proceedWithSuccessful(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    @Transactional
    public void retryFailedFiles(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        for (UploadedFile f : failedFiles) {
            f.markSuccess();
        }
        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING);
    }

    @Transactional
    public void resetForReplace(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles);
        session.updateStatus(AnalysisSession.SessionStatus.UPLOADED);
    }

    public ReviewSummaryResponse getReviewSummary(Long sessionId) {
        AnalysisSession session = findSession(sessionId);

        ReviewSummaryResponse.SummaryDto summary = ReviewSummaryResponse.SummaryDto.builder()
                .slotDescription("업로드한 8~10월 기록 기준 · 슬롯 " + session.getMonthsCount() + "개")
                .statusTag("검토 중")
                .totalCount(87)
                .totalExpense(1243500L)
                .build();

        List<ReviewSummaryResponse.LowConfidenceItemDto> queueItems = List.of(
                ReviewSummaryResponse.LowConfidenceItemDto.builder()
                        .transactionId(101L)
                        .storeName("GS25")
                        .date("09.07")
                        .originalAmount(8900L)
                        .suggestedAmount(3900L)
                        .isConfirmed(false)
                        .build(),
                ReviewSummaryResponse.LowConfidenceItemDto.builder()
                        .transactionId(102L)
                        .storeName("배민")
                        .date("10.03")
                        .originalAmount(27000L)
                        .suggestedAmount(21000L)
                        .isConfirmed(false)
                        .build()
        );

        ReviewSummaryResponse.LowConfidenceQueueDto lowConfidence = ReviewSummaryResponse.LowConfidenceQueueDto.builder()
                .count(queueItems.size())
                .badge("확인 필요")
                .items(queueItems)
                .build();

        ReviewSummaryResponse.DuplicateSummaryDto duplicateSummary = ReviewSummaryResponse.DuplicateSummaryDto.builder()
                .message("중복 후보 3건을 같은 거래로 묶었어요")
                .candidateCount(3)
                .build();

        return ReviewSummaryResponse.builder()
                .sessionId(session.getId())
                .summary(summary)
                .lowConfidenceQueue(lowConfidence)
                .duplicateSummary(duplicateSummary)
                .canConfirm(false)
                .build();
    }

    @Transactional
    public ReviewCompletedResponse checkAndGetCompletedSummary(Long sessionId) {
        AnalysisSession session = findSession(sessionId);

        return ReviewCompletedResponse.builder()
                .sessionId(session.getId())
                .slotDescription("업로드한 8~10월 기록 기준")
                .statusTag("검토 중")
                .totalCount(87)
                .totalExpense(1243500L)
                .reviewedSummary(ReviewCompletedResponse.ReviewedItemCounts.builder()
                        .lowConfidenceCount(2)
                        .duplicateCount(3)
                        .requiredValueErrorCount(0)
                        .build())
                .isChecked(true)
                .guideMessage("계속하면 확정한 기록으로 패턴을 계산해요. 원본 이미지 삭제가 시작돼요.")
                .build();
    }

    @Transactional
    public SessionConfirmResponse confirmAndStartAnalysis(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.ANALYZING);

        return SessionConfirmResponse.builder()
                .sessionId(sessionId)
                .snapshotId(1001L)
                .status("ANALYZING")
                .nextStepUrl("/api/sessions/" + sessionId + "/analysis/status")
                .build();
    }

    public FinalAnalysisStatusResponse getFinalAnalysisStatus(Long sessionId) {
        AnalysisSession session = findSession(sessionId);

        List<FinalAnalysisStatusResponse.StepInfo> steps = List.of(
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(1)
                        .name("최종 집계")
                        .detail("지출 87건 · 1,243,500원")
                        .status("COMPLETED")
                        .build(),
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(2)
                        .name("반복 패턴 찾기")
                        .detail("요일 · 늦은 시간 · 연쇄 · 날씨")
                        .status("IN_PROGRESS")
                        .build(),
                FinalAnalysisStatusResponse.StepInfo.builder()
                        .stepNumber(3)
                        .name("설명 문장 쓰기")
                        .detail(session.getPersona() != null ? session.getPersona().name().toLowerCase() + " 말투로" : "코치 말투로")
                        .status("WAITING")
                        .build()
        );

        return FinalAnalysisStatusResponse.builder()
                .sessionId(session.getId())
                .snapshotId(1001L)
                .status("ANALYZING")
                .title("확정한 87건으로 최종 분석하고 있어요")
                .subTitle("이제 숫자는 바뀌지 않아요. 원본 캡처 삭제를 시작했어요.")
                .currentStep(2)
                .steps(steps)
                .guideMessage("완료되면 결과 홈으로 이동해요. 숫자 요약은 먼저 보여드리고, 문장은 준비되는 대로 채워요.")
                .build();
    }

    public AnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));
    }
}