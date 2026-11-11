
package com.horizon.ai.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.horizon.ai.analyzer.HealthReport;
import com.horizon.ai.performance.PerfSample;
import com.horizon.ai.utils.Settings;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Điều phối gọi AI:
 * - Giới hạn tần suất
 * - Giới hạn request/giờ
 * - Timeout
 * - Retry có backoff
 * - Circuit breaker
 *
 * NGUYÊN TẮC:
 * - AI chỉ nhận snapshot JSON đã thu thập.
 * - Không truy cập và không điều khiển World.
 * - Mọi lỗi AI chỉ làm kết quả rỗng.
 * - API Key ưu tiên Environment Variable,
 *   sau đó fallback sang config.yml.
 * - Tuyệt đối không ghi API Key ra log.
 *
 * LUỒNG:
 * - analyze() và isDue() gọi trên Main Thread.
 * - Callback hoàn tất chạy trên luồng async.
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
    public record MachineSummary(
            String id,
            String world,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            String state,
            String severity,
            boolean correlated,
            boolean eligible,
            double confidence,
            double[] activity,
            double[] mspt,
            int pistons,
            int hoppers,
            int dispensers,
            int observers,
            int tnt,
            int redstoneParts,
            boolean boundaryClear,
            List<String> evidence
    ) {
    }

    public record AnalysisInput(
            long generatedAt,
            List<PerfSample> performance,
            List<MachineSummary> machines,
            int activeMachines,
            long totalRedstoneEvents
    ) {
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

        this.breaker = new CircuitBreaker(
                settings.cbFailureThreshold,
                settings.cbOpenSeconds * 1000L
        );

        this.provider = createProvider();
    }

    /**
     * Khởi tạo AI Provider.
     *
     * Thứ tự lấy API Key:
     * 1. Environment Variable.
     * 2. config.yml (ai.api-key).
     *
     * Không bao giờ ghi API Key ra log.
     */
    private AIProvider createProvider() {

        if (!settings.aiEnabled) {
            return null;
        }

        String key = null;

        // 1. Ưu tiên Environment Variable
        if (settings.aiKeyEnv != null
                && !settings.aiKeyEnv.isBlank()) {

            try {
                key = System.getenv(settings.aiKeyEnv);

            } catch (SecurityException e) {
                log.warning(
                        "[HorizonAI] Không thể truy cập Environment Variable."
                );
            }
        }

        // 2. Fallback sang config.yml
        if (key == null || key.isBlank()) {
            key = settings.aiApiKey;
        }

        // 3. Kiểm tra API Key
        if (key == null || key.isBlank()) {
            log.warning(
                    "[HorizonAI] Chưa cấu hình API Key. "
                    + "AI bị tắt, hệ thống cục bộ vẫn hoạt động."
            );

            return null;
        }

        // 4. Kiểm tra Provider
        if (!"openai".equals(settings.aiProvider)
                && !"openai-compatible".equals(settings.aiProvider)) {

            log.warning(
                    "[HorizonAI] Provider AI không được hỗ trợ."
            );

            return null;
        }

        // 5. Khởi tạo Provider
        try {
            return new OpenAIProvider(
                    settings.aiEndpoint,
                    settings.aiModel,
                    key.trim(),
                    settings.aiTimeoutSeconds,
                    mapper
            );

        } catch (IllegalArgumentException e) {

            log.warning(
                    "[HorizonAI] Cấu hình AI không hợp lệ: "
                    + e.getMessage()
            );

            return null;
        }
    }

    public boolean isEnabled() {
        return provider != null;
    }

    public boolean isDue(long nowMillis) {
        return provider != null
                && nowMillis - lastRequestMillis
                >= settings.aiIntervalSeconds * 1000L;
    }

    public Optional<HealthReport> latest() {
        return Optional.ofNullable(latest);
    }

    public String statusLabel() {

        if (provider == null) {
            return "TẮT (cục bộ)";
        }

        return switch (breaker.state()) {

            case CLOSED ->
                    "BẬT (" + provider.name() + ":" + settings.aiModel + ")";

            case OPEN ->
                    "TẠM DỪNG (circuit breaker)";

            case HALF_OPEN ->
                    "THỬ LẠI";
        };
    }

    /**
     * Gửi snapshot để phân tích.
     * Trả về future rỗng nếu bị giới hạn,
     * lỗi hoặc phản hồi không hợp lệ.
     *
     * Gọi trên Main Thread.
     */
    public CompletableFuture<Optional<HealthReport>> analyze(
            AnalysisInput input
    ) {

        long now = System.currentTimeMillis();

        if (provider == null
                || !breaker.allowRequest(now)
                || !allowRate(now)) {

            return CompletableFuture.completedFuture(
                    Optional.empty()
            );
        }

        final String userJson;

        try {
            userJson = mapper.writeValueAsString(input);

        } catch (Exception e) {

            log.warning(
                    "[HorizonAI] Không thể chuyển snapshot thành JSON: "
                    + e.getClass().getSimpleName()
            );

            return CompletableFuture.completedFuture(
                    Optional.empty()
            );
        }

        if (userJson.length() > MAX_INPUT_CHARS) {

            log.warning(
                    "[HorizonAI] Snapshot gửi AI quá lớn, "
                    + "bỏ qua lần phân tích này."
            );

            return CompletableFuture.completedFuture(
                    Optional.empty()
            );
        }

        lastRequestMillis = now;

        return callWithRetry(userJson, 0).handle((content, ex) -> {

            long done = System.currentTimeMillis();

            if (ex != null || content == null) {

                breaker.onFailure(done);

                if (ex == null) {
                    log.warning(
                            "[HorizonAI] AI không khả dụng: "
                            + "Phản hồi rỗng."
                    );
                } else {
                    log.warning(
                            "[HorizonAI] AI không khả dụng: "
                            + describeError(ex)
                    );
                }

                return Optional.<HealthReport>empty();
            }

            try {

                HealthReport report = parser.parse(content, done);

                breaker.onSuccess();

                latest = report;

                return Optional.of(report);

            } catch (Exception e) {

                breaker.onFailure(done);

                log.warning(
                        "[HorizonAI] Phản hồi AI không đúng schema: "
                        + describeError(e)
                );

                return Optional.<HealthReport>empty();
            }
        });
    }

    /**
     * Truy xuất nguyên nhân gốc của lỗi.
     * Giúp tránh chỉ hiển thị CompletionException
     * hoặc ExecutionException.
     */
    private static Throwable unwrapError(Throwable error) {

        Throwable current = error;

        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null
                && current.getCause() != current) {

            current = current.getCause();
        }

        return current;
    }

    /**
     * Chuyển lỗi thành chuỗi an toàn để ghi log.
     * Không ghi API Key.
     */
    private static String describeError(Throwable error) {

        Throwable root = unwrapError(error);

        String message = root.getMessage();

        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }

        // Tránh ghi dữ liệu quá dài vào console.
        if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }

        return root.getClass().getSimpleName() + ": " + message;
    }

    /**
     * Retry với exponential backoff.
     */
    private CompletableFuture<String> callWithRetry(
            String user,
            int attempt
    ) {

        return provider.complete(SYSTEM_PROMPT, user)
                .orTimeout(
                        settings.aiTimeoutSeconds + 5L,
                        TimeUnit.SECONDS
                )
                .exceptionallyCompose(ex -> {

                    if (attempt >= settings.aiMaxRetries) {
                        return CompletableFuture.failedFuture(ex);
                    }

                    long backoffSeconds = 1L << attempt;

                    return CompletableFuture
                            .supplyAsync(
                                    () -> null,
                                    CompletableFuture.delayedExecutor(
                                            backoffSeconds,
                                            TimeUnit.SECONDS
                                    )
                            )
                            .thenCompose(
                                    x -> callWithRetry(user, attempt + 1)
                            );
                });
    }

    /**
     * Giới hạn số request trong một giờ gần nhất.
     * Gọi trên Main Thread.
     */
    private boolean allowRate(long now) {

        while (!requestTimes.isEmpty()
                && now - requestTimes.peekFirst() > HOUR_MS) {

            requestTimes.pollFirst();
        }

        if (requestTimes.size()
                >= settings.aiMaxRequestsPerHour) {

            return false;
        }

        requestTimes.addLast(now);

        return true;
    }
}
