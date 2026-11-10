package com.example.line1thon.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("소비 습관 분석 서비스 API (1호선톤 Team12)")
                        .description("소비 영수증 캡처 분석 및 소비 습관 진단/플랜 제공 API 명세서")
                        .version("v1.1"));
    }
}