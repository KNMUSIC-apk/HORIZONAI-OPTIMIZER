
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
 * Provider cho OpenAI Chat Completions và Gemini OpenAI-compatible API.
 */
public final class OpenAIProvider implements AIProvider {

    private final HttpClient client;
    private final URI uri;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public OpenAIProvider(
            String endpoint,
            String model,
            String apiKey,
            int timeoutSeconds,
            ObjectMapper mapper
    ) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Endpoint AI không được để trống");
        }

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model AI không được để trống");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API Key không được để trống");
        }

        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeout phải lớn hơn 0");
        }

        URI parsed = URI.create(endpoint.trim());

        boolean local =
                "localhost".equalsIgnoreCase(parsed.getHost())
                || "127.0.0.1".equals(parsed.getHost());

        if (!"https".equalsIgnoreCase(parsed.getScheme()) && !local) {
            throw new IllegalArgumentException(
                    "Endpoint AI phải sử dụng HTTPS"
            );
        }

        this.uri = parsed;
        this.model = model.trim();
        this.apiKey = apiKey.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.mapper = mapper;

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public CompletableFuture<String> complete(
            String systemPrompt,
            String userJson
    ) {
        final String body;

        try {
            ObjectNode root = mapper.createObjectNode();

            root.put("model", model);
            root.put("temperature", 0.1);

            // Yêu cầu AI trả về JSON.
            root.set(
                    "response_format",
                    mapper.createObjectNode()
                            .put("type", "json_object")
            );

            ArrayNode messages = root.putArray("messages");

            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);

            messages.addObject()
                    .put("role", "user")
                    .put("content", userJson);

            body = mapper.writeValueAsString(root);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        final HttpRequest request;

        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new IOException("Không thể tạo AI request: " + e.getMessage(), e)
            );
        }

        return client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {

            int status = response.statusCode();
            String responseBody = response.body();

            // Hiển thị nội dung lỗi thực tế từ API.
            if (status < 200 || status >= 300) {

                String errorBody = responseBody == null
                        ? ""
                        : responseBody.trim();

                // Tránh log quá dài.
                if (errorBody.length() > 1200) {
                    errorBody = errorBody.substring(0, 1200)
                            + "... (đã cắt)";
                }

                if (errorBody.isBlank()) {
                    errorBody = "API không trả về nội dung lỗi.";
                }

                throw new CompletionException(
                        new IOException(
                                "AI API HTTP " + status + ": " + errorBody
                        )
                );
            }

            try {
                JsonNode result = mapper.readTree(responseBody);

                JsonNode choices = result.path("choices");

                if (!choices.isArray() || choices.isEmpty()) {
                    throw new IOException(
                            "AI API không trả về choices: " + responseBody
                    );
                }

                JsonNode message = choices
                        .path(0)
                        .path("message");

                String content = message
                        .path("content")
                        .asText("");

                if (content.isBlank()) {
                    throw new IOException(
                            "AI trả về nội dung rỗng hoặc không đúng định dạng."
                    );
                }

                return content;

            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }
}
