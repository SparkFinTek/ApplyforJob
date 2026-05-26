package com.sparkinvesco.jobflow.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Anthropic Messages API.
 *
 * Configuration (application.yml → jobflow.anthropic):
 *   api-key   — read from env var ANTHROPIC_API_KEY. If empty, isConfigured() = false
 *               and ProcessingService falls back to base-resume-as-is mode.
 *   base-url  — https://api.anthropic.com/v1
 *   model     — claude-sonnet-4-6 (good balance for resume tailoring)
 *
 * Hard rule: this client never logs or persists the API key, prompts, or responses
 * by default. Add explicit logging only at debug level if needed.
 */
@Component
public class AnthropicClient {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicClient(
            @Value("${jobflow.anthropic.api-key:}") String apiKey,
            @Value("${jobflow.anthropic.base-url:https://api.anthropic.com/v1}") String baseUrl,
            @Value("${jobflow.anthropic.model:claude-sonnet-4-6}") String model,
            @Value("${jobflow.anthropic.max-tokens:4096}") int maxTokens,
            @Value("${jobflow.anthropic.timeout-seconds:90}") int timeoutSeconds
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;

        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(Math.toIntExact(Duration.ofSeconds(15).toMillis()));
                    setReadTimeout(Math.toIntExact(Duration.ofSeconds(timeoutSeconds).toMillis()));
                }})
                .build();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Send a single user-turn message and return the assistant's text response.
     * The system prompt is set explicitly to keep tailoring constraints front and center.
     *
     * @throws IllegalStateException if the API key isn't configured.
     */
    public String message(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. The tailoring step cannot run; " +
                    "ProcessingService should have fallen back to base-resume mode before reaching here.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", userPrompt
        )));

        try {
            String responseJson = http.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(responseJson);
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                return sb.toString();
            }
            throw new RuntimeException("Unexpected Anthropic response shape: " + responseJson);
        } catch (Exception e) {
            throw new RuntimeException("Anthropic API call failed: " + e.getMessage(), e);
        }
    }

    public String getModel() {
        return model;
    }
}
