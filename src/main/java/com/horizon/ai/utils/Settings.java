package com.horizon.ai.utils;

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
 * Snapshot bất biến của config.yml. Mọi giá trị đều được đọc từ config và kẹp (clamp)
 * trong khoảng an toàn, không có ngưỡng nào bị hardcode trong logic.
 */
public final class Settings {

    // monitoring
    public final int sampleIntervalTicks;
    public final int historySize;
    public final double msptWarning;
    public final double msptCritical;
    public final double lagSpikeDelta;

    // ai
    public final boolean aiEnabled;
    public final String aiProvider;
    public final String aiModel;
    public final String aiEndpoint;
    public final String aiKeyEnv;
    public final int aiIntervalSeconds;
    public final int aiMaxRequestsPerHour;
    public final int aiTimeoutSeconds;
    public final int aiMaxRetries;
    public final int cbFailureThreshold;
    public final int cbOpenSeconds;
    public final String aiReportLanguage;

    // redstone
    public final int rsThresholdEvents;
    public final int rsWindowSeconds;
    public final int clusterDistance;
    public final int maxAnalyzedBlocks;
    public final int maxTrackedMachines;
    public final int sharedSourceMargin;
    public final double minMsptCorrelationDelta;
    public final double minCorrelation;
    public final int minSamples;
    public final int warningsBeforeIsolation;
    public final boolean autoIsolate;
    public final int isolationDelaySeconds;
    public final int boundaryScanRadius;
    public final double clockMinFrequencyHz;
    public final double clockMaxIntervalCv;

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
        sampleIntervalTicks = clamp(c.getInt("monitoring.sample-interval-ticks", 40), 20, 1200);
        historySize = clamp(c.getInt("monitoring.history-size", 720), 60, 10_000);
        msptWarning = Math.max(1.0, c.getDouble("monitoring.mspt-warning", 45.0));
        msptCritical = Math.max(msptWarning, c.getDouble("monitoring.mspt-critical", 60.0));
        lagSpikeDelta = Math.max(1.0, c.getDouble("monitoring.lag-spike-delta", 15.0));

        aiEnabled = c.getBoolean("ai.enabled", true);
        aiProvider = c.getString("ai.provider", "openai").toLowerCase(Locale.ROOT);
        aiModel = c.getString("ai.model", "gpt-4o-mini");
        aiEndpoint = c.getString("ai.endpoint", "https://api.openai.com/v1/chat/completions");
        aiKeyEnv = c.getString("ai.api-key-env", "HORIZONAI_AI_KEY");
        aiIntervalSeconds = clamp(c.getInt("ai.analysis-interval-seconds", 300), 60, 86_400);
        aiMaxRequestsPerHour = clamp(c.getInt("ai.max-requests-per-hour", 12), 1, 1000);
        aiTimeoutSeconds = clamp(c.getInt("ai.timeout-seconds", 20), 3, 120);
        aiMaxRetries = clamp(c.getInt("ai.max-retries", 2), 0, 5);
        cbFailureThreshold = clamp(c.getInt("ai.circuit-breaker-failure-threshold", 3), 1, 50);
        cbOpenSeconds = clamp(c.getInt("ai.circuit-breaker-open-seconds", 300), 10, 86_400);
        aiReportLanguage = c.getString("ai.report-language", "vi");

        rsThresholdEvents = clamp(c.getInt("redstone.detection-threshold-events-per-window", 400), 10, 1_000_000);
        rsWindowSeconds = clamp(c.getInt("redstone.window-seconds", 10), 2, 300);
        clusterDistance = clamp(c.getInt("redstone.cluster-distance", 2), 1, 8);
        maxAnalyzedBlocks = clamp(c.getInt("redstone.max-analyzed-blocks", 2048), 64, 65_536);
        maxTrackedMachines = clamp(c.getInt("redstone.max-tracked-machines", 256), 8, 4096);
        sharedSourceMargin = clamp(c.getInt("redstone.shared-source-margin", 6), 1, 32);
        minMsptCorrelationDelta = Math.max(0.0, c.getDouble("redstone.min-mspt-correlation-delta", 5.0));
        minCorrelation = Math.max(0.1, Math.min(0.99, c.getDouble("redstone.min-correlation", 0.5)));
        minSamples = clamp(c.getInt("redstone.min-samples", 12), 4, 120);
        warningsBeforeIsolation = clamp(c.getInt("redstone.warnings-before-isolation", 2), 1, 20);
        autoIsolate = c.getBoolean("redstone.auto-isolate", false);
        isolationDelaySeconds = clamp(c.getInt("redstone.isolation-delay-seconds", 60), 0, 3600);
        boundaryScanRadius = clamp(c.getInt("redstone.boundary-scan-radius", 24), 4, 64);
        clockMinFrequencyHz = Math.max(0.1, c.getDouble("redstone.clock-min-frequency-hz", 2.0));
        clockMaxIntervalCv = Math.max(0.01, c.getDouble("redstone.clock-max-interval-cv", 0.25));

        warnDistance = clamp(c.getInt("redstone.warning.distance", 32), 4, 256);
        warnCooldownSeconds = clamp(c.getInt("redstone.warning.cooldown-seconds", 120), 5, 3600);
        preferActivator = c.getBoolean("redstone.warning.prefer-activator", true);

        chunkEntityWarning = clamp(c.getInt("entity.chunk-entity-warning", 120), 10, 100_000);
        densityRadius = clamp(c.getInt("entity.density-radius", 16), 4, 64);
        densityWarning = clamp(c.getInt("entity.density-warning", 80), 5, 100_000);
        itemOverflowWarning = clamp(c.getInt("entity.item-overflow-warning", 300), 10, 100_000);
        mobThresholds = new HashMap<>();
        if (c.isConfigurationSection("entity.mob-thresholds")) {
            for (String k : c.getConfigurationSection("entity.mob-thresholds").getKeys(false)) {
                mobThresholds.put(k.toUpperCase(Locale.ROOT), Math.max(1, c.getInt("entity.mob-thresholds." + k, 50)));
            }
        }
        chunkHopperWarning = clamp(c.getInt("hopper.chunk-hopper-warning", 200), 10, 100_000);
        hopperTransfersPerSecondWarning = clamp(c.getInt("hopper.transfers-per-second-warning", 40), 1, 100_000);
        chunkAnalysisEnabled = c.getBoolean("chunk.analysis-enabled", true);
        chunksPerCycle = clamp(c.getInt("chunk.chunks-per-cycle", 16), 1, 256);
        activeChunkWarning = clamp(c.getInt("chunk.active-chunk-warning", 64), 4, 10_000);

        // suggestions-only luôn được coi là true cho các thay đổi làm đổi world/inventory
        suggestionsOnly = c.getBoolean("optimization.suggestions-only", true);
        autoActions = upperSet(c.getStringList("optimization.auto-whitelist-actions"));
        approvalActions = upperSet(c.getStringList("optimization.admin-approval-actions"));

        adminAlertEnabled = c.getBoolean("admin-alert.enabled", true);
        adminAlertMinLevel = Severity.parse(c.getString("admin-alert.min-level", "HIGH"), Severity.HIGH);
        adminAlertCooldownSeconds = clamp(c.getInt("admin-alert.cooldown-seconds", 600), 30, 86_400);

        whitelistWorlds = new HashSet<>(c.getStringList("whitelist.worlds"));
        whitelistRegions = new ArrayList<>();
        for (String raw : c.getStringList("whitelist.regions")) {
            Region r = Region.parse(raw);
            if (r == null) {
                log.warning("[HorizonAI] Bỏ qua vùng whitelist không hợp lệ: " + raw);
            } else {
                whitelistRegions.add(r);
            }
        }
        excludedPlayers = new HashSet<>();
        for (String p : c.getStringList("whitelist.excluded-players")) {
            excludedPlayers.add(p.toLowerCase(Locale.ROOT));
        }

        auditEnabled = c.getBoolean("logging.audit-enabled", true);
        debug = c.getBoolean("logging.debug", false);
        databaseFile = c.getString("database.file", "horizonai.db");
        retentionDays = clamp(c.getInt("database.retention-days", 14), 1, 365);
    }

    public boolean isWorldWhitelisted(String world) {
        return whitelistWorlds.contains(world);
    }

    public boolean isInWhitelistedRegion(String world, int x, int y, int z) {
        for (Region r : whitelistRegions) {
            if (r.contains(world, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPlayerExcluded(String name) {
        return excludedPlayers.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Set<String> upperSet(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            out.add(s.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
