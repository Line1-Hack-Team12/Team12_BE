package com.example.line1thon.service;

import com.example.line1thon.dto.F03ExtractResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 서비스의 multipart 엔드포인트 호출: 캡처 추출 POST /extract, 은행 파일 파싱 POST /parse.
 * 계약은 Team12_consume 레포의 backend/F03_INTERFACE.md 1~5절.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiExtractClient {

    private static final Pattern DETAIL = Pattern.compile("\"detail\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient aiRestClient;

    /** AI 서비스가 4xx 로 거른 요청. message 는 사용자에게 그대로 보여줄 수 있는 한글 사유. */
    public static class AiRejectedException extends RuntimeException {
        private final int status;

        public AiRejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * POST /extract (슬롯 하나). asset_ids[i] 는 images[i] 의 UploadedFile id.
     * owner_name 은 캡처에 찍힌 본인 이름을 "본인" 으로 바꾸기 위한 것. 모르면 빈 문자열.
     */
    public F03ExtractResponse requestExtract(
            String slotMonth,
            String ownerName,
            List<String> assetIds,
            List<MultipartFile> files
    ) {
        if (files.size() != assetIds.size()) {
            throw new IllegalArgumentException("이미지 파일 개수와 asset_id 개수가 일치해야 합니다.");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("slot_month", slotMonth);
        if (ownerName != null && !ownerName.isBlank()) {
            body.add("owner_name", ownerName.trim());
        }
        try {
            for (int i = 0; i < files.size(); i++) {
                body.add("asset_ids", assetIds.get(i));
                body.add("images", asResource(files.get(i)));
            }
        } catch (IOException e) {
            throw new RuntimeException("업로드 이미지 변환 중 오류가 발생했습니다.", e);
        }

        log.info("AI /extract slotMonth={} 이미지 {}장", slotMonth, files.size());
        return postMultipart("/extract", body);
    }

    /**
     * POST /parse. 은행 앱이 내보낸 거래내역 파일(지금은 토스뱅크 xlsx, 암호 걸림) 하나를 달별 자산으로 돌려받는다.
     * 응답 assets[].asset_id 는 "assetId:YYYY-MM", assets[].slot_month 가 그 달. 각 행은 /extract 행과 같은 필드.
     * months 를 주면 그 달들만 돌아온다 (세션의 슬롯 달). 암호는 AI 서비스가 저장하지 않는다.
     */
    public F03ExtractResponse requestParse(
            MultipartFile file,
            String assetId,
            String ownerName,
            String password,
            List<String> months
    ) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("asset_id", assetId);
        if (ownerName != null && !ownerName.isBlank()) {
            body.add("owner_name", ownerName.trim());
        }
        if (password != null && !password.isEmpty()) {
            body.add("password", password);
        }
        if (months != null) {
            for (String m : months) {
                body.add("months", m);
            }
        }
        try {
            body.add("file", asResource(file));
        } catch (IOException e) {
            throw new RuntimeException("업로드 파일 변환 중 오류가 발생했습니다.", e);
        }

        log.info("AI /parse assetId={} months={}", assetId, months == null ? 0 : months.size());
        return postMultipart("/parse", body);
    }

    private F03ExtractResponse postMultipart(String path, MultiValueMap<String, Object> body) {
        try {
            return aiRestClient.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(F03ExtractResponse.class);
        } catch (RestClientResponseException ex) {
            // 400 입력 오류, 413 너무 큼, 415 지원 안 하는 파일, 422 암호 오류: 본문 detail 이 사용자용 한글 사유
            String raw = ex.getResponseBodyAsString();
            Matcher m = DETAIL.matcher(raw == null ? "" : raw);
            String reason = m.find() ? m.group(1) : "AI 서버가 요청을 거절했습니다 (" + ex.getStatusCode().value() + ")";
            log.warn("AI {} 거절 status={} detail={}", path, ex.getStatusCode().value(), reason);
            throw new AiRejectedException(ex.getStatusCode().value(), reason);
        } catch (RuntimeException ex) {
            log.error("AI {} 통신 실패", path, ex);
            throw new RuntimeException("AI 추출 서버 연동 실패: " + ex.getMessage(), ex);
        }
    }

    private static ByteArrayResource asResource(MultipartFile file) throws IOException {
        return new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        };
    }
}
