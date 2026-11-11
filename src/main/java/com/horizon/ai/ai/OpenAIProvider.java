package com.horizon.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Provider cho OpenAI Chat Completions và mọi API tương thích.
 * API key được đọc từ biến môi trường (ai.api-key-env) bởi AIAnalyzer, không bao giờ ghi ra log.
 */
public final class OpenAIProvider implements AIProvider {

    private final HttpClient client;
    private final URI uri;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public OpenAIProvider(String endpoint, String model, String apiKey, int timeoutSeconds, ObjectMapper mapper) {
        URI parsed = URI.create(endpoint);
        boolean local = "localhost".equalsIgnoreCase(parsed.getHost()) || "127.0.0.1".equals(parsed.getHost());
        if (!"https".equalsIgnoreCase(parsed.getScheme()) && !local) {
            throw new IllegalArgumentException("Endpoint AI phải dùng HTTPS (trừ localhost)");
        }
        this.uri = parsed;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public CompletableFuture<String> complete(String systemPrompt, String userJson) {
        final String body;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0.1);
            root.set("response_format", mapper.createObjectNode().put("type", "json_object"));
            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userJson);
            body = mapper.writeValueAsString(root);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() / 100 != 2) {
                throw new CompletionException(new IOException("AI API trả về HTTP " + resp.statusCode()));
            }
            try {
                JsonNode root = mapper.readTree(resp.body());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                if (content.isBlank()) {
                    throw new IOException("Phản hồi AI rỗng");
                }
                return content;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }
}
