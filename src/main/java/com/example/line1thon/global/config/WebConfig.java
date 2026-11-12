package com.example.line1thon.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // 모든 API 엔드포인트에 대해
                .allowedOriginPatterns(
                        "http://localhost:3000", // React 로컬 기본 포트
                        "http://localhost:5173", // Vite 로컬 기본 포트
                        "http://localhost:8080",
                        "https://*.vercel.app",
                        "https://*.netlify.app"
                        // 배포 후 프론트엔드 도메인이 생기면 여기에 추가 (예: "https://your-frontend.vercel.app")
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS") // 허용할 HTTP 메서드
                .allowedHeaders("*") // 모든 HTTP 요청 헤더 허용
                .allowCredentials(true) // 쿠키 및 인증 헤더(Authorization) 포함 허용
                .maxAge(3600); // 프리플라이트(Preflight) 요청 캐싱 시간(초)
    }
}