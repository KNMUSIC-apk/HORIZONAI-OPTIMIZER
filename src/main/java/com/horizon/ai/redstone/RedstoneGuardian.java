package com.horizon.ai.redstone;

import com.horizon.ai.ai.AIAnalyzer;
import com.horizon.ai.analyzer.HealthReport;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Bộ điều phối AI Redstone Limiter: mỗi cửa sổ thời gian thực hiện
 *  1. Rút snapshot hoạt động (Main Thread, O(số block đã giới hạn)).
 *  2. Phân cụm block (luồng async, không chạm Bukkit API).
 *  3. Phân tích từng máy, quyết định Level 1 (cảnh báo) và Level 2 (cách ly) (Main Thread).
 *  4. Định kỳ gọi AI với snapshot đã thu thập; AI chỉ có thể PHỦ QUYẾT cách ly, không thể kích hoạt.
 */
public final class RedstoneGuardian {

    private static final long ACTIVE_LOOKUP_MS = 5 * 60_000L;
    private static final long AI_VETO_FRESH_MS = 15 * 60_000L;
    private static final int AI_SERIES_TAIL = 12;
    private static final int AI_MAX_MACHINES = 30;
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
    private final RedstoneAnalyzer analyzer;
    private final AIAnalyzer ai;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private int windowIndex;
    private int scanBudget;
    private BukkitTask task;

    public RedstoneGuardian(Plugin plugin, Settings settings, Logger log, PerformanceMonitor perf,
                            ActivityTracker activity, ActorTracker actors, MachineTracker tracker,
                            MachineIsolation isolation, WarningService warnings, ActionValidator validator,
                            RedstoneAnalyzer analyzer, AIAnalyzer ai) {
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
        this.analyzer = analyzer;
        this.ai = ai;
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

        for (Pair p : pairs) {
            int others = activeCount - (p.cluster().events() >= activeFloor ? 1 : 0);
            analyze(p, mspt, nowMs, wi, others);
        }
        tracker.endWindow(wi, mspt);
        for (Pair p : pairs) {
            decide(p.machine(), nowMs, wi);
        }
        isolation.refresh();
        maybeRunAi(nowMs);
    }

    private void analyze(Pair p, double mspt, long nowMs, int wi, int otherActive) {
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

        RedstoneAnalyzer.MachineStats stats = new RedstoneAnalyzer.MachineStats(
                c.events(), base, c.clockFrequencyHz(), c.clockIntervalCv(),
                m.seriesActivity(), m.seriesMspt(), mspt, otherActive,
                c.isContainable(), m.containableParts() > 0, m.containableParts());
        RedstoneAnalyzer.Verdict v = analyzer.analyze(stats);

        Severity prev = m.severity;
        m.severity = v.severity();
        m.confidence = v.confidence();
        m.eligible = v.isolationEligible();
        m.correlated = v.correlated();
        m.evidence = v.evidence();
        m.baselineEvents = base + 0.1 * (events - base);

        if (v.severity() != prev) {
            m.log("Mức " + prev + " → " + v.severity()
                    + String.format(Locale.ROOT, " (r=%.2f, ΔMSPT=%.1f)", v.correlation(), v.msptDelta()));
        }
        if (v.severity().atLeast(Severity.MEDIUM)) {
            Region r = m.region;
            m.activatorName = actors.activatorIn(r, 2, ACTIVE_LOOKUP_MS).map(ActorTracker.Actor::name).orElse(null);
            m.builderName = actors.builderIn(r, 2, ACTIVE_LOOKUP_MS).map(ActorTracker.Actor::name).orElse(null);
        }
    }

    /** Quyết định Level 1 / Level 2 cho một máy vừa được phân tích. Main Thread. */
    private void decide(Machine m, long nowMs, int wi) {
        if (m.state == Machine.State.ISOLATED) {
            return;
        }
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
        if (m.correlated && s.atLeast(Severity.MEDIUM)
                && (m.state == Machine.State.OBSERVED || m.state == Machine.State.WARNED)) {
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

        // LEVEL 2: chuyển sang chờ cách ly khi đã cảnh báo đủ số lần và đủ điều kiện nghiêm ngặt
        if (m.state == Machine.State.WARNED && m.warningCount >= settings.warningsBeforeIsolation && m.eligible) {
            if (aiVetoes(m, nowMs)) {
                m.log("AI không xác nhận nguyên nhân: không chuyển sang Level 2");
                return;
            }
            m.state = Machine.State.PENDING_ISOLATION;
            m.pendingSinceMillis = nowMs;
            m.log("Đủ điều kiện Level 2, " + (settings.autoIsolate ? "sẽ tự cách ly sau trì hoãn" : "chờ Admin duyệt"));
            String action = settings.autoIsolate
                    ? "Sẽ tự động cách ly sau " + settings.isolationDelaySeconds + " giây."
                    : "Duyệt bằng: /horizonai redstone isolate " + m.id;
            warnings.notifyAdmins("§cMáy §f" + m.id + " §cđủ điều kiện cách ly Level 2. " + action,
                    "pending:" + m.id, nowMs, alertCooldown);
        }

        if (m.state == Machine.State.PENDING_ISOLATION) {
            if (!(m.eligible && s.atLeast(Severity.HIGH))) {
                m.state = Machine.State.WARNED;
                m.pendingSinceMillis = 0;
                m.log("Điều kiện không còn đủ, hủy chờ cách ly");
                return;
            }
            if (settings.autoIsolate && nowMs - m.pendingSinceMillis >= settings.isolationDelaySeconds * 1000L) {
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

    private boolean aiVetoes(Machine m, long nowMs) {
        Optional<HealthReport> r = ai.latest();
        if (r.isEmpty() || nowMs - r.get().createdMillis() > AI_VETO_FRESH_MS) {
            return false;
        }
        Boolean confirmed = r.get().machineConfirmed().get(m.id);
        return confirmed != null && !confirmed;
    }

    private void maybeRunAi(long nowMs) {
        if (!ai.isEnabled() || !ai.isDue(nowMs)) {
            return;
        }
        AIAnalyzer.AnalysisInput input = snapshotForAi(nowMs);
        ai.analyze(input).thenAccept(opt -> opt.ifPresent(report ->
                Bukkit.getScheduler().runTask(plugin, () -> onAiReport(report))));
    }

    /** Main Thread: chỉ thực thi những hành động đã qua ActionValidator. */
    public void onAiReport(HealthReport report) {
        long now = System.currentTimeMillis();
        for (ActionValidator.Result r : validator.validateAll(report.actions(), true)) {
            switch (r.decision()) {
                case EXECUTE -> execute(r.action(), now);
                case QUEUE_FOR_ADMIN -> warnings.notifyAdmins("§eAI đề xuất cách ly máy §f" + r.action().machineId()
                        + "§e (chờ Admin duyệt): /horizonai redstone isolate " + r.action().machineId(),
                        "ai-queue:" + r.action().machineId(), now, settings.adminAlertCooldownSeconds * 1000L);
                case SUGGEST_ONLY -> log.fine("[HorizonAI] Đề xuất (không tự thực hiện): " + r.action().type());
                case REJECT -> log.fine("[HorizonAI] Từ chối đề xuất AI: " + r.why());
            }
        }
    }

    private void execute(ActionValidator.ProposedAction a, long now) {
        switch (a.type().toUpperCase(Locale.ROOT)) {
            case "WARN_PLAYER" -> tracker.get(a.machineId()).ifPresent(m -> warnings.warnNearby(m, now));
            case "NOTIFY_ADMIN" -> warnings.notifyAdmins("§6[AI] §f" + a.reason(), "ai-note:" + a.reason().hashCode(),
                    now, settings.adminAlertCooldownSeconds * 1000L);
            default -> { }
        }
    }

    /** Main Thread. Snapshot đã thu thập, không chứa tên người chơi. */
    public AIAnalyzer.AnalysisInput snapshotForAi(long nowMs) {
        List<AIAnalyzer.MachineSummary> summaries = new ArrayList<>();
        for (Machine m : tracker.all()) {
            if (summaries.size() >= AI_MAX_MACHINES) {
                break;
            }
            double[] act = m.seriesActivity();
            double[] msp = m.seriesMspt();
            summaries.add(new AIAnalyzer.MachineSummary(
                    m.id, m.world, m.region.minX(), m.region.minY(), m.region.minZ(),
                    m.region.maxX(), m.region.maxY(), m.region.maxZ(),
                    m.state.name(), m.severity.name(), m.correlated, m.eligible, m.confidence,
                    tail(act), tail(msp),
                    m.components.pistons, m.components.hoppers, m.components.dispensers,
                    m.components.observers, m.components.tnt, m.components.redstoneParts,
                    m.boundaryClear, List.copyOf(m.evidence)));
        }
        List<PerfSample> perfTail = perf.recent(60);
        int active = (int) tracker.all().stream().filter(m -> m.severity.atLeast(Severity.LOW)).count();
        return new AIAnalyzer.AnalysisInput(nowMs, perfTail, summaries, active, 0L);
    }

    private static double[] tail(double[] a) {
        int from = Math.max(0, a.length - AI_SERIES_TAIL);
        double[] out = new double[a.length - from];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }
}
