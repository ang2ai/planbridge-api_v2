package com.planbridge.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude API 호출 서비스 (외부망 테스트용).
 * 사내 서버 배포 시 AzureOpenAI로 교체 예정.
 */
@Service
@Slf4j
public class AiService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public AiService(RestTemplateBuilder builder,
                     @Value("${planbridge.anthropic.api-key:}") String apiKey,
                     @Value("${planbridge.anthropic.model:claude-3-5-sonnet-20241022}") String model,
                     @Value("${planbridge.anthropic.max-tokens:4096}") int maxTokens) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofMinutes(3))
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @SuppressWarnings("unchecked")
    public String call(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("ANTHROPIC_API_KEY 미설정 - 목업 응답 반환");
            return buildMockResponse(userPrompt);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            log.debug("Anthropic API 호출: model={}", model);
            ResponseEntity<Map> response = restTemplate.postForEntity(ANTHROPIC_API_URL, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> content =
                        (List<Map<String, Object>>) response.getBody().get("content");
                if (content != null && !content.isEmpty()) {
                    String text = (String) content.get(0).get("text");
                    log.debug("Anthropic API 응답 수신: {} chars", text != null ? text.length() : 0);
                    return text;
                }
            }
            throw new RuntimeException("Anthropic API 응답이 비어있습니다");

        } catch (Exception e) {
            log.error("Anthropic API 호출 실패: {}", e.getMessage());
            throw new RuntimeException("AI 분석 실패: " + e.getMessage(), e);
        }
    }

    private String buildMockResponse(String userPrompt) {
        String title = userPrompt.length() > 50 ? userPrompt.substring(0, 50) + "..." : userPrompt;
        return "{\n" +
               "  \"summary\": \"ANTHROPIC_API_KEY가 설정되지 않아 목업 분석 결과를 반환합니다.\",\n" +
               "  \"todos\": [\n" +
               "    {\n" +
               "      \"title\": \"변경 요청 분석 TODO (API KEY 필요)\",\n" +
               "      \"prompt\": \"" + title.replace("\"", "'") + "\",\n" +
               "      \"targetFiles\": [],\n" +
               "      \"complexity\": \"MODERATE\"\n" +
               "    }\n" +
               "  ]\n" +
               "}";
    }
}
