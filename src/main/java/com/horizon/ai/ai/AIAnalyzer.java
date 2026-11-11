package com.horizon.ai.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.horizon.ai.analyzer.HealthReport;
import com.horizon.ai.performance.PerfSample;
import com.horizon.ai.utils.Settings;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Điều phối gọi AI: giới hạn tần suất, giới hạn request/giờ, timeout, retry có backoff, circuit breaker.
 *
 * NGUYÊN TẮC:
 *  - AI chỉ nhận snapshot JSON đã thu thập. Không truy cập và không điều khiển World.
 *  - Mọi lỗi AI chỉ làm kết quả rỗng; hệ thống phát hiện cục bộ vẫn chạy bình thường.
 *  - API key lấy từ biến môi trường, không bao giờ ghi ra log.
 *
 * LUỒNG: analyze() và isDue() gọi trên Main Thread. Các callback hoàn tất chạy trên luồng async.
 */
public final class AIAnalyzer {

    static final String SYSTEM_PROMPT = """
            You are HorizonAI, a performance analyst for a Minecraft Paper/Purpur 1.21 server.
            You receive ONLY a JSON snapshot of already-collected metrics. You cannot access, modify,
            or command the server or the world.
            Rules:
            1. High Redstone activity alone is NOT proof of lag. Mark a machine "causal": true only when
               the snapshot shows activity correlated with MSPT (see correlated, msptDelta, confidence)
               and no other explanation is evident. Otherwise set causal to false and explain why.
            2. Distinguish correlation from verified cause. Say when evidence is insufficient.
            3. ISOLATE_MACHINE may be suggested only for machines with eligible=true, and it is always
               queued for administrator approval.
            4. Allowed action types: WARN_PLAYER, NOTIFY_ADMIN, ISOLATE_MACHINE, SUGGEST_OPTIMIZATION.
               Use machine_id values from the snapshot only. Never invent IDs.
            5. Write summary, causes, recommendations and explanations in Vietnamese.
            6. Respond with ONLY a JSON object with keys:
               severity (NORMAL|LOW|MEDIUM|HIGH|CRITICAL), summary (string), causes (string array),
               recommendations (string array),
               machines (array of {id, causal (boolean), severity, explanation}),
               actions (array of {type, machine_id, reason}).
            """;

    private static final int MAX_INPUT_CHARS = 60_000;
    private static final long HOUR_MS = 3_600_000L;

    /** Tóm tắt một máy gửi cho AI. KHÔNG chứa tên người chơi. */
    public record MachineSummary(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                 String state, String severity, boolean correlated, boolean eligible,
                                 double confidence, double[] activity, double[] mspt,
                                 int pistons, int hoppers, int dispensers, int observers, int tnt,
                                 int redstoneParts, boolean boundaryClear, List<String> evidence) {
    }

    public record AnalysisInput(long generatedAt, List<PerfSample> performance, List<MachineSummary> machines,
                                int activeMachines, long totalRedstoneEvents) {
    }

    private final Settings settings;
    private final Logger log;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AIResponseParser parser = new AIResponseParser(mapper);
    private final AIProvider provider;
    private final CircuitBreaker breaker;
    private final ArrayDeque<Long> requestTimes = new ArrayDeque<>();
    private long lastRequestMillis;
    private volatile HealthReport latest;

    public AIAnalyzer(Settings settings, Logger log) {
        this.settings = settings;
        this.log = log;
        this.breaker = new CircuitBreaker(settings.cbFailureThreshold, settings.cbOpenSeconds * 1000L);
        this.provider = createProvider();
    }

    private AIProvider createProvider() {
        if (!settings.aiEnabled) {
            return null;
        }
        String key = System.getenv(settings.aiKeyEnv);
        if (key == null || key.isBlank()) {
            log.warning("[HorizonAI] Không tìm thấy API key trong biến môi trường " + settings.aiKeyEnv
                    + ". AI bị tắt, hệ thống cục bộ vẫn hoạt động.");
            return null;
        }
        if (!"openai".equals(settings.aiProvider) && !"openai-compatible".equals(settings.aiProvider)) {
            log.warning("[HorizonAI] Provider AI không được hỗ trợ: " + settings.aiProvider);
            return null;
        }
        try {
            return new OpenAIProvider(settings.aiEndpoint, settings.aiModel, key, settings.aiTimeoutSeconds, mapper);
        } catch (IllegalArgumentException e) {
            log.warning("[HorizonAI] Cấu hình AI không hợp lệ: " + e.getMessage());
            return null;
        }
    }

    public boolean isEnabled() {
        return provider != null;
    }

    public boolean isDue(long nowMillis) {
        return provider != null && nowMillis - lastRequestMillis >= settings.aiIntervalSeconds * 1000L;
    }

    public Optional<HealthReport> latest() {
        return Optional.ofNullable(latest);
    }

    public String statusLabel() {
        if (provider == null) {
            return "TẮT (cục bộ)";
        }
        return switch (breaker.state()) {
            case CLOSED -> "BẬT (" + provider.name() + ":" + settings.aiModel + ")";
            case OPEN -> "TẠM DỪNG (circuit breaker)";
            case HALF_OPEN -> "THỬ LẠI";
        };
    }

    /**
     * Gửi một snapshot để phân tích. Trả về future rỗng nếu bị giới hạn, lỗi, hoặc phản hồi không hợp lệ.
     * Gọi trên Main Thread.
     */
    public CompletableFuture<Optional<HealthReport>> analyze(AnalysisInput input) {
        long now = System.currentTimeMillis();
        if (provider == null || !breaker.allowRequest(now) || !allowRate(now)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final String userJson;
        try {
            userJson = mapper.writeValueAsString(input);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (userJson.length() > MAX_INPUT_CHARS) {
            log.warning("[HorizonAI] Snapshot gửi AI quá lớn, bỏ qua lần phân tích này.");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        lastRequestMillis = now;

        return callWithRetry(userJson, 0).handle((content, ex) -> {
            long done = System.currentTimeMillis();
            if (ex != null || content == null) {
                breaker.onFailure(done);
                log.warning("[HorizonAI] AI không khả dụng: " + (ex == null ? "rỗng" : ex.getClass().getSimpleName()));
                return Optional.<HealthReport>empty();
            }
            try {
                HealthReport r = parser.parse(content, done);
                breaker.onSuccess();
                latest = r;
                return Optional.of(r);
            } catch (Exception e) {
                breaker.onFailure(done);
                log.warning("[HorizonAI] Phản hồi AI không đúng schema, đã bỏ qua.");
                return Optional.<HealthReport>empty();
            }
        });
    }

    private CompletableFuture<String> callWithRetry(String user, int attempt) {
        return provider.complete(SYSTEM_PROMPT, user)
                .orTimeout(settings.aiTimeoutSeconds + 5L, TimeUnit.SECONDS)
                .exceptionallyCompose(ex -> {
                    if (attempt >= settings.aiMaxRetries) {
                        return CompletableFuture.failedFuture(ex);
                    }
                    long backoffSeconds = 1L << attempt;
                    return CompletableFuture
                            .supplyAsync(() -> null, CompletableFuture.delayedExecutor(backoffSeconds, TimeUnit.SECONDS))
                            .thenCompose(x -> callWithRetry(user, attempt + 1));
                });
    }

    /** Giới hạn số request trong một giờ gần nhất. Gọi trên Main Thread. */
    private boolean allowRate(long now) {
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() > HOUR_MS) {
            requestTimes.pollFirst();
        }
        if (requestTimes.size() >= settings.aiMaxRequestsPerHour) {
            return false;
        }
        requestTimes.addLast(now);
        return true;
    }
}
