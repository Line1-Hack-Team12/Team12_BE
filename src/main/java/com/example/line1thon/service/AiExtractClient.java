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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiExtractClient {

    private final RestClient aiRestClient;

    /**
     * FastAPI의 POST /extract 호출 (단일 슬롯 단위)
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
            body.add("owner_name", ownerName);
        }

        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                String assetId = assetIds.get(i);

                ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                    @Override
                    public String getFilename() {
                        return file.getOriginalFilename();
                    }
                };

                // F03 명세: asset_ids[]와 images[] 반복 추가
                body.add("asset_ids", assetId);
                body.add("images", fileResource);
            }

            log.info("FastAPI /extract 요청 전송: slotMonth={}, 이미지 {}장", slotMonth, files.size());

            return aiRestClient.post()
                    .uri("/extract")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(F03ExtractResponse.class);

        } catch (IOException e) {
            log.error("파일 바이트 읽기 실패", e);
            throw new RuntimeException("업로드 이미지 변환 중 오류가 발생했습니다.", e);
        } catch (Exception e) {
            log.error("AI 서버 통신 실패 (POST /extract)", e);
            throw new RuntimeException("AI 추출 서버 연동 실패: " + e.getMessage(), e);
        }
    }
}