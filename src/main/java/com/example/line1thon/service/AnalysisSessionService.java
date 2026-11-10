package com.example.line1thon.service;

import com.example.line1thon.dto.ConfirmLowConfidenceRequest;
import com.example.line1thon.dto.ReviewSummaryResponse;
import com.example.line1thon.dto.SessionDetailResponse;
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

        // S02 4단계 표시
        List<SessionDetailResponse.StepDto> steps = List.of(
                SessionDetailResponse.StepDto.builder().stepNumber(1).name("캡처 읽기").status("COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(2).name("가맹점 분류")
                        .status(session.getStatus() == AnalysisSession.SessionStatus.EXTRACTING ? "IN_PROGRESS" : "COMPLETED").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(3).name("중복 정리").status("WAITING").build(),
                SessionDetailResponse.StepDto.builder().stepNumber(4).name("패턴 후보 찾기").status("WAITING").build()
        );

        // 파일별 상태 표시
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

    // 데모: 파일 실패 예시 버튼 클릭
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

    // 데모: 완료 상태 보기 버튼 클릭
    @Transactional
    public void simulateComplete(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    // 실패 화면: [성공한 7장으로 검토] -> S03 검토로 이동
    @Transactional
    public void proceedWithSuccessful(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        // 실패한 파일은 세션에서 삭제(제외) 처리
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles);
        session.updateStatus(AnalysisSession.SessionStatus.REVIEWING);
    }

    // 실패 화면: [실패한 파일만 다시 시도] -> S02 진행 화면으로 복귀
    @Transactional
    public void retryFailedFiles(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        for (UploadedFile f : failedFiles) {
            f.markSuccess(); // 재시도 진행
        }
        session.updateStatus(AnalysisSession.SessionStatus.EXTRACTING); // S02 진행 화면으로 복귀
    }

    // 실패 화면: [캡처 교체하기] -> 실패 파일 삭제 후 S01 화면으로 복귀
    @Transactional
    public void resetForReplace(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        List<UploadedFile> failedFiles = fileRepository.findBySessionAndStatus(session, UploadedFile.ProcessStatus.FAILED);
        fileRepository.deleteAll(failedFiles); // 실패한 파일 제거
        session.updateStatus(AnalysisSession.SessionStatus.UPLOADED); // S01 캡처 화면으로 복귀
    }

    public AnalysisSession findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다. id=" + sessionId));
    }
    // S03: 검토 화면 데이터 조회
    public ReviewSummaryResponse getReviewSummary(Long sessionId) {
        AnalysisSession session = findSession(sessionId);

        // 디자인 화면과 100% 동일한 목업 데이터 생성
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
                .canConfirm(false) // 아직 확인 전이므로 체크박스 비활성화
                .build();
    }

    // S03: 확신 낮은 행 확인 처리
    @Transactional
    public boolean confirmLowConfidenceItems(Long sessionId, ConfirmLowConfidenceRequest request) {
        // 실제 운영 시 DB Transaction 엔티티 금액 업데이트 로직
        // 현재는 디자인 연동을 위해 확인 성공(true)으로 반환
        return true;
    }

    // S03: 맞아요 체크 후 [계속] 클릭 (최종 확정)
    @Transactional
    public void confirmAndComplete(Long sessionId) {
        AnalysisSession session = findSession(sessionId);
        session.updateStatus(AnalysisSession.SessionStatus.COMPLETED);
    }
}