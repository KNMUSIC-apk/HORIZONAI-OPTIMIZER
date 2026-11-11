package com.horizon.ai.redstone;

import com.horizon.ai.lut.LUTDecision;
import com.horizon.ai.lut.LUTEngine;
import com.horizon.ai.lut.LUTInput;
import com.horizon.ai.lut.LUTProfile;
import com.horizon.ai.lut.LUTReportBuilder;
import com.horizon.ai.lut.LUTResult;
import com.horizon.ai.optimizer.ActionValidator;
import com.horizon.ai.optimizer.WarningService;
import com.horizon.ai.performance.PerfSample;
import com.horizon.ai.performance.PerformanceMonitor;
import com.horizon.ai.utils.Region;
import com.horizon.ai.utils.Settings;
import com.horizon.ai.utils.Severity;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bộ điều phối Redstone mỗi cửa sổ thời gian:
 *  1. Rút snapshot hoạt động (Main Thread, số block bị giới hạn).
 *  2. Phân cụm block thành máy (luồng async, không chạm Bukkit API).
 *  3. Cập nhật lịch sử máy, quét thành phần khi cần (Main Thread).
 *  4. Chấm điểm bằng Advanced Machine LUT (Main Thread, thuần tính toán, có giới hạn số máy và thời gian).
 *  5. Quyết định Level 1 (cảnh báo) và Level 2 (chờ cách ly). Level 3 chỉ qua MachineIsolation.
 *
 * Không có AI, không có mạng. LUT không bao giờ tự thay đổi block.
 */
public final class RedstoneGuardian {

    private static final long ACTIVE_LOOKUP_MS = 5 * 60_000L;
    /** Số lần quét thành phần tối đa mỗi cửa sổ, để không làm lag Main Thread. */
    private static final int MAX_SCANS_PER_WINDOW = 2;

    private record Pair(Machine machine, MachineIdentifier.Cluster cluster) {
    }

    private final Plugin plugin;
    private final Settings settings;
    private final Logger log;
    private final PerformanceMonitor perf;
    private final ActivityTracker activity;
    private final ActorTracker actors;
    private final MachineTracker tracker;
    private final MachineIsolation isolation;
    private final WarningService warnings;
    private final ActionValidator validator;
    private final LUTEngine lut;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private int windowIndex;
    private int scanBudget;
    private BukkitTask task;

    public RedstoneGuardian(Plugin plugin, Settings settings, Logger log, PerformanceMonitor perf,
                            ActivityTracker activity, ActorTracker actors, MachineTracker tracker,
                            MachineIsolation isolation, WarningService warnings, ActionValidator validator,
                            LUTEngine lut) {
        this.plugin = plugin;
        this.settings = settings;
        this.log = log;
        this.perf = perf;
        this.activity = activity;
        this.actors = actors;
        this.tracker = tracker;
        this.isolation = isolation;
        this.warnings = warnings;
        this.validator = validator;
        this.lut = lut;
    }

    public void start() {
        long period = settings.rsWindowSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    public int windowIndex() {
        return windowIndex;
    }

    public LUTProfile profile() {
        return lut.profile();
    }

    /** Main Thread. */
    private void tick() {
        long nowMs = System.currentTimeMillis();
        int wi = ++windowIndex;
        ActivityTracker.Snapshot snap = activity.drain(System.nanoTime());
        PerfSample ps = perf.latest();
        double mspt = ps == null ? 0.0 : ps.mspt();
        perf.updateRedstoneActivity((int) Math.min(Integer.MAX_VALUE, snap.totalEvents()), snap.chunkEvents().size());

        if (snap.points().isEmpty()) {
            applyWindow(List.of(), mspt, nowMs, wi);
        } else if (inFlight.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<MachineIdentifier.Cluster> clusters;
                try {
                    clusters = MachineIdentifier.identify(snap.points(), settings.clusterDistance,
                            settings.boundaryScanRadius * 2 + 1, settings.sharedSourceMargin);
                } catch (RuntimeException e) {
                    log.warning("[HorizonAI] Lỗi khi phân cụm Redstone: " + e.getMessage());
                    clusters = List.of();
                }
                List<MachineIdentifier.Cluster> result = clusters;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        applyWindow(result, mspt, nowMs, wi);
                    } finally {
                        inFlight.set(false);
                    }
                });
            });
        }
        // Nếu phân tích trước còn đang chạy, cửa sổ này bị bỏ qua có chủ đích (không làm trễ Main Thread).
    }

    private void applyWindow(List<MachineIdentifier.Cluster> clusters, double mspt, long nowMs, int wi) {
        int activeFloor = Math.max(10, settings.rsThresholdEvents / 4);
        int createFloor = Math.max(5, settings.rsThresholdEvents / 20);
        int activeCount = (int) clusters.stream().filter(c -> c.events() >= activeFloor).count();
        scanBudget = MAX_SCANS_PER_WINDOW;

        List<Pair> pairs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MachineIdentifier.Cluster c : clusters) {
            // Chỉ tạo máy mới cho cụm đủ lớn, tránh nhiễu làm tốn bộ nhớ và thời gian quét
            Machine m = tracker.match(c, wi, nowMs, c.events() >= createFloor);
            if (m != null && seen.add(m.id)) {
                pairs.add(new Pair(m, c));
            }
        }

        LUTProfile profile = lut.profile();
        long budgetNanos = (long) (profile.maxAnalysisMsPerWindow() * 1_000_000L);
        long start = System.nanoTime();
        int limit = profile.maxMachinesPerWindow();
        int offset = pairs.isEmpty() ? 0 : wi % pairs.size(); // luân phiên điểm bắt đầu để không máy nào bị bỏ đói
        List<Pair> analyzed = new ArrayList<>();

        for (int k = 0; k < pairs.size(); k++) {
            Pair p = pairs.get((offset + k) % pairs.size());
            boolean runLut = settings.lutEnabled
                    && analyzed.size() < limit
                    && System.nanoTime() - start < budgetNanos;
            int others = activeCount - (p.cluster().events() >= activeFloor ? 1 : 0);
            analyze(p, mspt, nowMs, wi, others, runLut);
            if (runLut) {
                analyzed.add(p);
            }
        }
        tracker.endWindow(wi, mspt);
        for (Pair p : analyzed) {
            decide(p.machine(), nowMs, wi);
        }
        isolation.refresh();
    }

    /**
     * Cập nhật lịch sử máy trong cửa sổ hiện tại. Lịch sử luôn được ghi (để giữ cặp activity/MSPT đồng bộ),
     * còn LUT chỉ chạy khi nằm trong giới hạn số máy và ngân sách thời gian của cửa sổ.
     */
    private void analyze(Pair p, double mspt, long nowMs, int wi, int otherActive, boolean runLut) {
        Machine m = p.machine();
        MachineIdentifier.Cluster c = p.cluster();

        m.boundaryClear = c.isContainable();
        if (!c.isContainable() && (m.state == Machine.State.OBSERVED || m.state == Machine.State.WARNED)) {
            m.state = Machine.State.AMBIGUOUS;
        } else if (c.isContainable() && m.state == Machine.State.AMBIGUOUS) {
            m.state = Machine.State.OBSERVED;
        }

        m.totalEvents += c.events();
        if (c.events() > 0) {
            m.lastActiveMillis = nowMs;
        }
        if (scanBudget > 0 && tracker.needsRescan(m, wi)) {
            World w = Bukkit.getWorld(m.world);
            if (w != null) {
                scanBudget--;
                tracker.scanComponents(m, w, wi);
            }
        }

        double events = c.events();
        double base = m.baselineEvents < 0 ? events : m.baselineEvents;
        m.addSeries(events, mspt);

        if (runLut) {
            LUTInput in = new LUTInput(
                    m.id, m.world, m.seriesActivity(), m.seriesMspt(), base,
                    c.events(), settings.rsWindowSeconds,
                    c.clockFrequencyHz(), c.clockIntervalCv(), mspt, otherActive,
                    c.isContainable(),
                    m.lastScanWindow > 0 && !m.components.partial,
                    new LUTInput.Composition(m.components.pistons, m.components.hoppers, m.components.dispensers,
                            m.components.observers, m.components.tnt, m.components.redstoneParts));
            applyLut(m, lut.analyze(in));
        }

        m.baselineEvents = base + 0.1 * (events - base);
        if (m.severity.atLeast(Severity.MEDIUM)) {
            Region r = m.region;
            m.activatorName = actors.activatorIn(r, 2, ACTIVE_LOOKUP_MS).map(ActorTracker.Actor::name).orElse(null);
            m.builderName = actors.builderIn(r, 2, ACTIVE_LOOKUP_MS).map(ActorTracker.Actor::name).orElse(null);
        }
    }

    /** Ghi kết quả LUT vào máy. Không thay đổi block. */
    private void applyLut(Machine m, LUTResult r) {
        Severity prev = m.severity;
        m.lastLut = r;
        m.severity = r.severity();
        m.confidence = r.confidence();
        m.eligible = r.decision().isolationEligible();
        m.correlated = r.decision().correlated();
        m.evidence = LUTReportBuilder.evidence(r, lut.profile().thresholds());
        if (m.severity != prev) {
            m.log("Mức " + prev + " → " + m.severity
                    + String.format(java.util.Locale.ROOT, " (r=%.2f, ΔMSPT=%.1f)",
                    r.features().correlation(), r.features().msptDelta()));
        }
    }

    /** Quyết định Level 1 / Level 2 cho một máy vừa được LUT phân tích. Main Thread. */
    private void decide(Machine m, long nowMs, int wi) {
        if (m.state == Machine.State.ISOLATED) {
            return;
        }
        LUTResult r = m.lastLut;
        if (r == null) {
            return;
        }
        LUTDecision d = r.decision();
        Severity s = m.severity;
        long alertCooldown = settings.adminAlertCooldownSeconds * 1000L;

        if (m.state == Machine.State.AMBIGUOUS) {
            if (s.atLeast(Severity.MEDIUM) && m.correlated) {
                warnings.notifyAdmins("§eMáy §f" + m.id + " §ecó tương quan với lag nhưng ranh giới không rõ. "
                        + "Vui lòng Admin kiểm tra thủ công.", "ambiguous:" + m.id, nowMs, alertCooldown);
            }
            return;
        }

        // LEVEL 1: cảnh báo riêng cho người chơi gần máy
        if (d.level() >= 1 && (m.state == Machine.State.OBSERVED || m.state == Machine.State.WARNED)) {
            int before = m.warningCount;
            warnings.warnNearby(m, nowMs);
            if (m.warningCount > before) {
                m.state = Machine.State.WARNED;
            }
        }
        // Trở lại bình thường sau một thời gian dài không vi phạm
        if (m.state == Machine.State.WARNED && !s.atLeast(Severity.MEDIUM)
                && nowMs - m.lastWarningMillis > 3L * settings.warnCooldownSeconds * 1000L) {
            m.state = Machine.State.OBSERVED;
            m.warningCount = 0;
            m.log("Hoạt động trở lại bình thường");
        }
        if (s.atLeast(Severity.HIGH) && m.correlated && settings.adminAlertEnabled
                && s.atLeast(settings.adminAlertMinLevel)) {
            warnings.notifyAdmins("§c[" + s + "] §fMáy §e" + m.id + " §fcó tương quan với lag. Mã: "
                    + m.id, "alert:" + m.id, nowMs, alertCooldown);
        }

        // LEVEL 2: đã cảnh báo đủ số lần VÀ LUT xác nhận đủ mọi cổng bằng chứng.
        // Hàng đợi Admin luôn đi qua ActionValidator: ISOLATE_MACHINE → QUEUE_FOR_ADMIN.
        if (m.state == Machine.State.WARNED && m.warningCount >= settings.warningsBeforeIsolation && m.eligible) {
            ActionValidator.Result v = validator.validate(
                    new ActionValidator.ProposedAction("ISOLATE_MACHINE", m.id, "LUT Level 2"));
            boolean auto = d.autoIsolationAllowed();
            m.state = Machine.State.PENDING_ISOLATION;
            m.pendingSinceMillis = nowMs;
            m.log("Đủ điều kiện Level 2 (" + v.decision() + "), "
                    + (auto ? "sẽ tự cách ly sau trì hoãn" : "chờ Admin duyệt"));
            String action = auto
                    ? "Sẽ tự động cách ly sau " + settings.isolationDelaySeconds + " giây."
                    : "Duyệt bằng: /horizonai redstone isolate " + m.id;
            warnings.notifyAdmins("§cMáy §f" + m.id + " §cđủ điều kiện cách ly Level 2. " + action,
                    "pending:" + m.id, nowMs, alertCooldown);
        }

        if (m.state == Machine.State.PENDING_ISOLATION) {
            if (!m.eligible) {
                m.state = Machine.State.WARNED;
                m.pendingSinceMillis = 0;
                m.log("Điều kiện không còn đủ, hủy chờ cách ly");
                return;
            }
            // Tự cách ly chỉ khi cấu hình cho phép (đã loại trừ require-admin-approval) và đã hết trì hoãn.
            if (d.autoIsolationAllowed() && nowMs - m.pendingSinceMillis >= settings.isolationDelaySeconds * 1000L) {
                World w = Bukkit.getWorld(m.world);
                if (w != null) {
                    tracker.scanComponents(m, w, wi);
                }
                MachineIsolation.Outcome o = isolation.isolate(m, "AUTO", nowMs, false);
                if (o == MachineIsolation.Outcome.OK) {
                    warnings.notifyAdmins("§cĐã tự động cách ly máy §f" + m.id + "§c (Level 2). "
                            + "Khôi phục: /horizonai redstone restore " + m.id, "isolated:" + m.id, nowMs, alertCooldown);
                } else {
                    m.log("Không thể tự động cách ly: " + o);
                    m.pendingSinceMillis = 0;
                    m.state = o == MachineIsolation.Outcome.AMBIGUOUS_BOUNDARY
                            ? Machine.State.AMBIGUOUS : Machine.State.WARNED;
                }
            }
        }
    }
}
