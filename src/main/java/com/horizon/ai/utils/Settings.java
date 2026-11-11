
package com.horizon.ai.utils;

import com.horizon.ai.lut.LUTProfile;
import com.horizon.ai.lut.LUTSafety;
import com.horizon.ai.lut.LUTThresholds;
import com.horizon.ai.lut.LUTWeights;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Snapshot bất biến của config.yml.
 * Mọi giá trị đều được đọc từ config và kẹp trong khoảng an toàn.
 */
public final class Settings {

    // monitoring
    public final int sampleIntervalTicks;
    public final int historySize;
    public final double msptWarning;
    public final double msptCritical;
    public final double lagSpikeDelta;

    // lut (phân tích cục bộ, không dùng AI)
    public final boolean lutEnabled;
    public final LUTProfile lutProfile;

    // redstone
    public final int rsThresholdEvents;
    public final int rsWindowSeconds;
    public final int clusterDistance;
    public final int maxAnalyzedBlocks;
    public final int maxTrackedMachines;
    public final int sharedSourceMargin;
    public final int warningsBeforeIsolation;
    public final int isolationDelaySeconds;
    public final int boundaryScanRadius;

    // redstone warning
    public final int warnDistance;
    public final int warnCooldownSeconds;
    public final boolean preferActivator;

    // entity / hopper / chunk
    public final int chunkEntityWarning;
    public final int densityRadius;
    public final int densityWarning;
    public final int itemOverflowWarning;
    public final Map<String, Integer> mobThresholds;
    public final int chunkHopperWarning;
    public final int hopperTransfersPerSecondWarning;
    public final boolean chunkAnalysisEnabled;
    public final int chunksPerCycle;
    public final int activeChunkWarning;

    // optimization
    public final boolean suggestionsOnly;
    public final Set<String> autoActions;
    public final Set<String> approvalActions;

    // admin alert
    public final boolean adminAlertEnabled;
    public final Severity adminAlertMinLevel;
    public final int adminAlertCooldownSeconds;

    // whitelist
    public final Set<String> whitelistWorlds;
    public final List<Region> whitelistRegions;
    public final Set<String> excludedPlayers;

    // logging / database
    public final boolean auditEnabled;
    public final boolean debug;
    public final String databaseFile;
    public final int retentionDays;

    public Settings(FileConfiguration c, Logger log) {

        // ==================================================
        // MONITORING
        // ==================================================

        sampleIntervalTicks = clamp(
                c.getInt("monitoring.sample-interval-ticks", 40),
                20, 1200
        );

        historySize = clamp(
                c.getInt("monitoring.history-size", 720),
                60, 10_000
        );

        msptWarning = Math.max(
                1.0,
                c.getDouble("monitoring.mspt-warning", 45.0)
        );

        msptCritical = Math.max(
                msptWarning,
                c.getDouble("monitoring.mspt-critical", 60.0)
        );

        lagSpikeDelta = Math.max(
                1.0,
                c.getDouble("monitoring.lag-spike-delta", 15.0)
        );

        // ==================================================
        // REDSTONE
        // ==================================================

        rsThresholdEvents = clamp(
                c.getInt("redstone.detection-threshold-events-per-window", 400),
                10, 1_000_000
        );

        rsWindowSeconds = clamp(
                c.getInt("redstone.window-seconds", 10),
                2, 300
        );

        clusterDistance = clamp(
                c.getInt("redstone.cluster-distance", 2),
                1, 8
        );

        maxAnalyzedBlocks = clamp(
                c.getInt("redstone.max-analyzed-blocks", 2048),
                64, 65_536
        );

        maxTrackedMachines = clamp(
                c.getInt("redstone.max-tracked-machines", 256),
                8, 4096
        );

        sharedSourceMargin = clamp(
                c.getInt("redstone.shared-source-margin", 6),
                1, 32
        );

        warningsBeforeIsolation = clamp(
                c.getInt("redstone.warnings-before-isolation", 2),
                1, 20
        );

        isolationDelaySeconds = clamp(
                c.getInt("redstone.isolation-delay-seconds", 60),
                0, 3600
        );

        boundaryScanRadius = clamp(
                c.getInt("redstone.boundary-scan-radius", 24),
                4, 64
        );

        // ==================================================
        // REDSTONE WARNING
        // ==================================================

        warnDistance = clamp(
                c.getInt("redstone.warning.distance", 32),
                4, 256
        );

        warnCooldownSeconds = clamp(
                c.getInt("redstone.warning.cooldown-seconds", 120),
                5, 3600
        );

        preferActivator = c.getBoolean(
                "redstone.warning.prefer-activator", true
        );

        // ==================================================
        // ENTITY
        // ==================================================

        chunkEntityWarning = clamp(
                c.getInt("entity.chunk-entity-warning", 120),
                10, 100_000
        );

        densityRadius = clamp(
                c.getInt("entity.density-radius", 16),
                4, 64
        );

        densityWarning = clamp(
                c.getInt("entity.density-warning", 80),
                5, 100_000
        );

        itemOverflowWarning = clamp(
                c.getInt("entity.item-overflow-warning", 300),
                10, 100_000
        );

        mobThresholds = new HashMap<>();

        if (c.isConfigurationSection("entity.mob-thresholds")) {
            for (String k : c.getConfigurationSection(
                    "entity.mob-thresholds"
            ).getKeys(false)) {

                mobThresholds.put(
                        k.toUpperCase(Locale.ROOT),
                        Math.max(
                                1,
                                c.getInt("entity.mob-thresholds." + k, 50)
                        )
                );
            }
        }

        // ==================================================
        // HOPPER
        // ==================================================

        chunkHopperWarning = clamp(
                c.getInt("hopper.chunk-hopper-warning", 200),
                10, 100_000
        );

        hopperTransfersPerSecondWarning = clamp(
                c.getInt("hopper.transfers-per-second-warning", 40),
                1, 100_000
        );

        // ==================================================
        // CHUNK
        // ==================================================

        chunkAnalysisEnabled = c.getBoolean(
                "chunk.analysis-enabled", true
        );

        chunksPerCycle = clamp(
                c.getInt("chunk.chunks-per-cycle", 16),
                1, 256
        );

        activeChunkWarning = clamp(
                c.getInt("chunk.active-chunk-warning", 64),
                4, 10_000
        );

        // ==================================================
        // OPTIMIZATION
        // ==================================================

        suggestionsOnly = c.getBoolean(
                "optimization.suggestions-only", true
        );

        autoActions = upperSet(
                c.getStringList("optimization.auto-whitelist-actions")
        );

        approvalActions = upperSet(
                c.getStringList("optimization.admin-approval-actions")
        );

        // ==================================================
        // ADMIN ALERT
        // ==================================================

        adminAlertEnabled = c.getBoolean(
                "admin-alert.enabled", true
        );

        adminAlertMinLevel = Severity.parse(
                c.getString("admin-alert.min-level", "HIGH"),
                Severity.HIGH
        );

        adminAlertCooldownSeconds = clamp(
                c.getInt("admin-alert.cooldown-seconds", 600),
                30, 86_400
        );

        // ==================================================
        // WHITELIST
        // ==================================================

        whitelistWorlds = new HashSet<>(
                c.getStringList("whitelist.worlds")
        );

        whitelistRegions = new ArrayList<>();

        for (String raw : c.getStringList("whitelist.regions")) {

            Region r = Region.parse(raw);

            if (r == null) {
                log.warning(
                        "[HorizonAI] Bỏ qua vùng whitelist không hợp lệ: " + raw
                );
            } else {
                whitelistRegions.add(r);
            }
        }

        excludedPlayers = new HashSet<>();

        for (String p : c.getStringList("whitelist.excluded-players")) {
            excludedPlayers.add(
                    p.toLowerCase(Locale.ROOT)
            );
        }

        // ==================================================
        // LOGGING / DATABASE
        // ==================================================

        auditEnabled = c.getBoolean(
                "logging.audit-enabled", true
        );

        debug = c.getBoolean(
                "logging.debug", false
        );

        databaseFile = c.getString(
                "database.file", "horizonai.db"
        );

        retentionDays = clamp(
                c.getInt("database.retention-days", 14),
                1, 365
        );

        // ==================================================
        // LUT (phân tích cục bộ, không dùng AI)
        // ==================================================

        lutEnabled = boolKey(c, log, "lut.enabled", null, true);

        if (c.isConfigurationSection("ai")) {
            log.warning("[HorizonAI] Mục 'ai' trong config.yml đã bị loại bỏ. Plugin không còn gọi AI và không đọc API key. Có thể xóa mục này.");
        }

        lutProfile = readLut(c, log, rsThresholdEvents, msptCritical);
    }

    public boolean isWorldWhitelisted(String world) {
        return whitelistWorlds.contains(world);
    }

    public boolean isInWhitelistedRegion(
            String world,
            int x,
            int y,
            int z
    ) {

        for (Region r : whitelistRegions) {

            if (r.contains(world, x, y, z)) {
                return true;
            }
        }

        return false;
    }

    public boolean isPlayerExcluded(String name) {
        return excludedPlayers.contains(
                name.toLowerCase(Locale.ROOT)
        );
    }

    /**
     * Đọc số từ config. Nếu khóa không phải số, không hữu hạn hoặc ngoài khoảng: ghi cảnh báo rõ ràng
     * và dùng giá trị an toàn. Không bao giờ im lặng bỏ qua cấu hình sai.
     * legacy: khóa cũ được chấp nhận khi khóa mới chưa được đặt (tương thích ngược).
     */
    private static double numKey(FileConfiguration c, Logger log, String path, String legacy,
                                 double def, double min, double max) {
        String key = path;
        if (!c.isSet(path) && legacy != null && c.isSet(legacy)) {
            key = legacy;
            log.info("[HorizonAI] Đang dùng khóa cũ '" + legacy + "'. Hãy chuyển sang '" + path + "'.");
        }
        if (!c.isSet(key)) {
            return def;
        }
        Object raw = c.get(key);
        if (!(raw instanceof Number n)) {
            log.warning("[HorizonAI] '" + key + "' phải là số, nhận '" + raw + "'. Dùng " + def + ".");
            return def;
        }
        double v = n.doubleValue();
        if (!Double.isFinite(v) || v < min || v > max) {
            double fixed = Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : def;
            log.warning("[HorizonAI] '" + key + "' = " + v + " nằm ngoài [" + min + ", " + max + "]. Dùng " + fixed + ".");
            return fixed;
        }
        return v;
    }

    private static int intKey(FileConfiguration c, Logger log, String path, String legacy,
                              int def, int min, int max) {
        return (int) Math.round(numKey(c, log, path, legacy, def, min, max));
    }

    private static boolean boolKey(FileConfiguration c, Logger log, String path, String legacy, boolean def) {
        String key = path;
        if (!c.isSet(path) && legacy != null && c.isSet(legacy)) {
            key = legacy;
            log.info("[HorizonAI] Đang dùng khóa cũ '" + legacy + "'. Hãy chuyển sang '" + path + "'.");
        }
        if (!c.isSet(key)) {
            return def;
        }
        Object raw = c.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        log.warning("[HorizonAI] '" + key + "' phải là true hoặc false, nhận '" + raw + "'. Dùng " + def + ".");
        return def;
    }

    /** Đọc toàn bộ mục lut.* thành một LUTProfile bất biến. */
    private static LUTProfile readLut(FileConfiguration c, Logger log, int eventThreshold, double msptCritical) {
        LUTWeights w = new LUTWeights(
                intKey(c, log, "lut.weights.event-ratio-low-points", null, 8, 0, 30),
                intKey(c, log, "lut.weights.event-ratio-medium-points", null, 14, 0, 30),
                intKey(c, log, "lut.weights.event-ratio-high-points", null, 20, 0, 30),
                intKey(c, log, "lut.weights.mspt-delta-low-points", null, 8, 0, 30),
                intKey(c, log, "lut.weights.mspt-delta-medium-points", null, 14, 0, 30),
                intKey(c, log, "lut.weights.mspt-delta-high-points", null, 20, 0, 30),
                intKey(c, log, "lut.weights.stable-clock-points", null, 12, 0, 30),
                intKey(c, log, "lut.weights.critical-mspt-points", null, 12, 0, 30),
                intKey(c, log, "lut.weights.correlation-points", null, 10, 0, 30));

        double ratioLow = numKey(c, log, "lut.thresholds.event-ratio-low", null, 1.0, 0.1, 1000);
        double ratioMed = numKey(c, log, "lut.thresholds.event-ratio-medium", null, 2.0, 0.1, 1000);
        double ratioHigh = numKey(c, log, "lut.thresholds.event-ratio-high", null, 3.0, 0.1, 1000);
        if (!(ratioLow <= ratioMed && ratioMed <= ratioHigh)) {
            log.warning("[HorizonAI] lut.thresholds.event-ratio-* phải tăng dần (low <= medium <= high). Dùng 1.0 / 2.0 / 3.0.");
            ratioLow = 1.0;
            ratioMed = 2.0;
            ratioHigh = 3.0;
        }

        int warn = intKey(c, log, "lut.scoring.warning-score", null, 45, 26, 98);
        int high = intKey(c, log, "lut.scoring.high-score", null, 65, 27, 99);
        int crit = intKey(c, log, "lut.scoring.critical-score", null, 80, 28, 100);
        if (!(warn < high && high < crit)) {
            log.warning("[HorizonAI] lut.scoring phải thỏa warning-score < high-score < critical-score. Dùng 45 / 65 / 80.");
            warn = 45;
            high = 65;
            crit = 80;
        }

        LUTThresholds t = new LUTThresholds(
                ratioLow, ratioMed, ratioHigh,
                numKey(c, log, "lut.thresholds.correlation-minimum", "redstone.min-correlation", 0.5, 0.10, 0.99),
                numKey(c, log, "lut.thresholds.mspt-delta-minimum", "redstone.min-mspt-correlation-delta", 5.0, 0.5, 500),
                msptCritical,
                warn, high, crit,
                intKey(c, log, "lut.analysis.min-samples", "redstone.min-samples", 12, 4, 120),
                Math.min(
                        intKey(c, log, "lut.analysis.history-size", null, 120, 4, 120),
                        intKey(c, log, "lut.performance.max-history-samples", null, 120, 4, 120)),
                numKey(c, log, "lut.analysis.minimum-confidence", null, 0.70, 0.0, 1.0),
                numKey(c, log, "lut.thresholds.clock-min-frequency-hz", "redstone.clock-min-frequency-hz", 2.0, 0.1, 1000),
                numKey(c, log, "lut.thresholds.clock-max-interval-cv", "redstone.clock-max-interval-cv", 0.25, 0.01, 10),
                eventThreshold);

        // Các cờ bằng chứng luôn bắt buộc. Đặt false chỉ được ghi cảnh báo, không có tác dụng.
        for (String k : new String[]{"require-correlation", "require-clear-boundary", "require-complete-scan"}) {
            if (!boolKey(c, log, "lut.safety." + k, null, true)) {
                log.warning("[HorizonAI] lut.safety." + k + " = false bị bỏ qua: cổng này luôn bắt buộc để cách ly an toàn.");
            }
        }
        boolean requireAdmin = boolKey(c, log, "lut.safety.require-admin-approval", null, true);
        boolean autoIsolate = boolKey(c, log, "lut.safety.auto-isolate", "redstone.auto-isolate", false);
        if (autoIsolate && requireAdmin) {
            log.warning("[HorizonAI] lut.safety.auto-isolate = true bị vô hiệu vì require-admin-approval = true.");
        }
        if (autoIsolate && !requireAdmin) {
            log.warning("[HorizonAI] CẢNH BÁO: tự cách ly máy được BẬT (require-admin-approval = false). Máy sẽ bị cách ly sau trì hoãn mà không cần Admin.");
        }
        LUTSafety safety = new LUTSafety(true, true, true, requireAdmin, autoIsolate);

        int maxMachines = intKey(c, log, "lut.performance.max-machines-per-window", null, 30, 1, 256);
        double maxMs = numKey(c, log, "lut.performance.max-analysis-ms-per-window", null, 5.0, 0.5, 50);

        return new LUTProfile(w, t, safety, maxMachines, maxMs);
    }

    private static Set<String> upperSet(List<String> in) {

        Set<String> out = new HashSet<>();

        for (String s : in) {
            out.add(
                    s.trim().toUpperCase(Locale.ROOT)
            );
        }

        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
