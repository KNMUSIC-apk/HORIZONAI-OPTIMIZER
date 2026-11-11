package com.horizon.ai.performance;

import com.horizon.ai.storage.Database;
import com.horizon.ai.utils.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Lấy mẫu TPS/MSPT/CPU/RAM/chunk/entity định kỳ trên Main Thread (chi phí rất thấp).
 * Lưu lịch sử trong bộ nhớ có giới hạn, và ghi định kỳ xuống DB bất đồng bộ.
 *
 * Phát hiện Lag Spike: so MSPT với baseline EWMA. Baseline không cập nhật khi đang spike,
 * để một đợt lag dài không "học" thành trạng thái bình thường.
 */
public final class PerformanceMonitor {

    private static final double EWMA_ALPHA = 0.05;
    private static final int DB_EVERY_N_SAMPLES = 6;

    private final Plugin plugin;
    private final Settings settings;
    private final Database db;
    private final List<PerfSample> history = new ArrayList<>();
    private BukkitTask task;
    private double mspt = 0.0;
    private double tps = 20.0;
    private double baselineMspt = -1;
    private boolean spiking;
    private long spikeCount;
    private int samplesSinceDb;
    private volatile int redstoneEventsLastWindow;
    private volatile int chunksActiveLastWindow;

    public PerformanceMonitor(Plugin plugin, Settings settings, Database db) {
        this.plugin = plugin;
        this.settings = settings;
        this.db = db;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample,
                settings.sampleIntervalTicks, settings.sampleIntervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    /** Được gọi từ RedstoneGuardian sau mỗi cửa sổ để hiển thị trong báo cáo. */
    public void updateRedstoneActivity(int events, int activeChunks) {
        this.redstoneEventsLastWindow = events;
        this.chunksActiveLastWindow = activeChunks;
    }

    /** Gọi trên Main Thread. */
    private void sample() {
        double rawMspt = Bukkit.getAverageTickTime();
        double[] tpsArr = Bukkit.getTPS();
        mspt = rawMspt > 0 ? rawMspt : mspt;
        tps = tpsArr.length > 0 ? Math.min(20.0, tpsArr[0]) : 20.0;

        int loadedChunks = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            loadedChunks += w.getLoadedChunks().length;
            entities += w.getEntities().size();
        }

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        double cpu = -1;
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            cpu = sun.getProcessCpuLoad();
        }

        // Lag spike detection
        if (baselineMspt < 0) {
            baselineMspt = mspt;
        }
        boolean nowSpike = mspt - baselineMspt >= settings.lagSpikeDelta;
        if (nowSpike && !spiking) {
            spikeCount++;
        }
        spiking = nowSpike;
        if (!spiking) {
            baselineMspt = baselineMspt + EWMA_ALPHA * (mspt - baselineMspt);
        }

        PerfSample s = new PerfSample(System.currentTimeMillis(), tps, mspt, cpu, usedMb, maxMb,
                Bukkit.getOnlinePlayers().size(), loadedChunks, entities,
                redstoneEventsLastWindow, chunksActiveLastWindow);
        synchronized (history) {
            history.add(s);
            while (history.size() > settings.historySize) {
                history.remove(0);
            }
        }

        if (++samplesSinceDb >= DB_EVERY_N_SAMPLES) {
            samplesSinceDb = 0;
            db.savePerfSample(tps, mspt, cpu, usedMb, s.players(), loadedChunks, entities, redstoneEventsLastWindow);
        }
    }

    public PerfSample latest() {
        synchronized (history) {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
    }

    /** Bản sao lịch sử gần nhất, an toàn để dùng từ luồng khác. */
    public List<PerfSample> recent(int n) {
        synchronized (history) {
            int from = Math.max(0, history.size() - n);
            return new ArrayList<>(history.subList(from, history.size()));
        }
    }

    public boolean isSpiking() {
        return spiking;
    }

    public long spikeCount() {
        return spikeCount;
    }

    public double baselineMspt() {
        return baselineMspt < 0 ? mspt : baselineMspt;
    }
}
