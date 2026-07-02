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
 * Azure OpenAI Chat Completions API 호출 서비스.
 * RestTemplate으로 직접 HTTP 통신 (외부 SDK 없음).
 */
@Service
@Slf4j
public class AiService {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String endpoint;    // https://xxx.openai.azure.com
    private final String deployment;  // gpt-4o
    private final String apiVersion;  // 2024-02-01
    private final int maxTokens;

    public AiService(RestTemplateBuilder builder,
                     @Value("${planbridge.azure-openai.api-key:}") String apiKey,
                     @Value("${planbridge.azure-openai.endpoint:}") String endpoint,
                     @Value("${planbridge.azure-openai.deployment:gpt-4o}") String deployment,
                     @Value("${planbridge.azure-openai.api-version:2024-02-01}") String apiVersion,
                     @Value("${planbridge.azure-openai.max-tokens:4096}") int maxTokens) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofMinutes(3))
                .build();
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.deployment = deployment;
        this.apiVersion = apiVersion;
        this.maxTokens = maxTokens;
    }

    @SuppressWarnings("unchecked")
    public String call(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("AZURE_OPENAI_API_KEY 미설정 - 목업 응답 반환");
            return buildMockResponse(userPrompt);
        }

        String url = endpoint + "/openai/deployments/" + deployment
                + "/chat/completions?api-version=" + apiVersion;

        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userPrompt)
        ));
        body.put("max_tokens", maxTokens);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            log.debug("Azure OpenAI 호출: deployment={}", deployment);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

            if (response.getBody() != null) {
                List<Map<String, Object>> choices =
                        (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) message.get("content");
                    log.debug("Azure OpenAI 응답 수신: {} chars", content != null ? content.length() : 0);
                    return content;
                }
            }
            throw new RuntimeException("Azure OpenAI 응답이 비어있습니다");

        } catch (Exception e) {
            log.error("Azure OpenAI 호출 실패: {}", e.getMessage());
            throw new RuntimeException("AI 분석 실패: " + e.getMessage(), e);
        }
    }

    private String buildMockResponse(String userPrompt) {
        String title = userPrompt.length() > 50 ? userPrompt.substring(0, 50) + "..." : userPrompt;
        return "{\n" +
               "  \"summary\": \"AZURE_OPENAI_API_KEY가 설정되지 않아 목업 분석 결과를 반환합니다.\",\n" +
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
