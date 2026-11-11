package com.horizon.ai;

import com.horizon.ai.chunk.ChunkHealthScanner;
import com.horizon.ai.commands.HorizonCommand;
import com.horizon.ai.events.IsolationListener;
import com.horizon.ai.events.RedstoneEventListener;
import com.horizon.ai.lut.LUTEngine;
import com.horizon.ai.optimizer.ActionValidator;
import com.horizon.ai.optimizer.WarningService;
import com.horizon.ai.performance.PerformanceMonitor;
import com.horizon.ai.redstone.ActivityTracker;
import com.horizon.ai.redstone.ActorTracker;
import com.horizon.ai.redstone.MachineIsolation;
import com.horizon.ai.redstone.MachineTracker;
import com.horizon.ai.redstone.RedstoneGuardian;
import com.horizon.ai.storage.Database;
import com.horizon.ai.utils.Settings;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Điểm vào của HorizonAI Optimizer.
 *
 * Kiến trúc (hoàn toàn offline, không cần API key hay Internet):
 *  - Thu thập (events + PerformanceMonitor) → phân cụm (async) → lịch sử máy (Main Thread)
 *    → Advanced Machine LUT (Main Thread, thuần tính toán) → Level 1 (cảnh báo) / Level 2 (chờ Admin)
 *    → Level 3 (cách ly có thể hoàn tác, chỉ qua MachineIsolation).
 *  - DB bất đồng bộ; mất kết nối là không có vấn đề gì vì không có kết nối nào.
 *  - Reload: dựng lại toàn bộ thành phần từ config mới; các máy đang cách ly được nạp lại từ DB.
 */
public final class HorizonAI extends JavaPlugin {

    private Settings settings;
    private Database db;
    private PerformanceMonitor perf;
    private ActivityTracker activity;
    private ActorTracker actors;
    private MachineTracker tracker;
    private MachineIsolation isolation;
    private WarningService warnings;
    private ActionValidator validator;
    private LUTEngine lut;
    private RedstoneGuardian guardian;
    private ChunkHealthScanner chunks;
    private BukkitTask maintenance;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings initial = new Settings(getConfig(), getLogger());
        try {
            db = new Database(new File(getDataFolder(), initial.databaseFile), getLogger());
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Không thể mở database. HorizonAI sẽ tắt.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        boot();

        HorizonCommand cmd = new HorizonCommand(this);
        getCommand("horizonai").setExecutor(cmd);
        getCommand("horizonai").setTabCompleter(cmd);

        Database database = db;
        int retention = settings.retentionDays;
        maintenance = getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> database.purgeOlderThan(retention), 20L * 60, 20L * 60 * 60 * 24);

        getLogger().info("HorizonAI đã bật. Advanced Machine LUT: "
                + (settings.lutEnabled ? "BẬT (cục bộ, không cần API key)" : "TẮT (chỉ lệnh thủ công)"));
    }

    @Override
    public void onDisable() {
        stopTasks();
        if (maintenance != null) {
            maintenance.cancel();
        }
        if (db != null) {
            db.close();
        }
    }

    /** Tải lại config và dựng lại toàn bộ thành phần. Các máy đang cách ly được khôi phục từ DB. */
    public void reloadHorizon() {
        reloadConfig();
        stopTasks();
        boot();
    }

    private void boot() {
        settings = new Settings(getConfig(), getLogger());
        activity = new ActivityTracker(settings.maxAnalyzedBlocks, 4096);
        actors = new ActorTracker(4096);
        tracker = new MachineTracker(settings, db);
        isolation = new MachineIsolation(settings, db, tracker);
        warnings = new WarningService(settings, getLogger());
        validator = new ActionValidator(settings, tracker);
        lut = new LUTEngine(settings.lutProfile);
        perf = new PerformanceMonitor(this, settings, db);
        guardian = new RedstoneGuardian(this, settings, getLogger(), perf, activity, actors, tracker,
                isolation, warnings, validator, lut);
        chunks = new ChunkHealthScanner(this, settings, warnings);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new IsolationListener(isolation), this);
        pm.registerEvents(new RedstoneEventListener(settings, activity, actors), this);

        perf.start();
        guardian.start();
        chunks.start();
        loadIsolationsAsync();
    }

    private void stopTasks() {
        if (guardian != null) {
            guardian.stop();
        }
        if (perf != null) {
            perf.stop();
        }
        if (chunks != null) {
            chunks.stop();
        }
        HandlerList.unregisterAll(this);
    }

    /** Nạp các máy đang cách ly từ DB (bất đồng bộ), áp dụng lại trên Main Thread. */
    private void loadIsolationsAsync() {
        MachineTracker t = tracker;
        MachineIsolation iso = isolation;
        db.loadIsolations().thenAccept(recs -> getServer().getScheduler().runTask(this, () -> {
            long now = System.currentTimeMillis();
            for (Database.IsolationRecord rec : recs) {
                t.restoreIsolated(rec, now);
            }
            iso.refresh();
            if (!recs.isEmpty()) {
                getLogger().info("Đã khôi phục " + recs.size() + " máy đang cách ly từ lần chạy trước.");
            }
        }));
    }

    public Settings settings() {
        return settings;
    }

    public Database db() {
        return db;
    }

    public PerformanceMonitor perf() {
        return perf;
    }

    public ActivityTracker activity() {
        return activity;
    }

    public ActorTracker actors() {
        return actors;
    }

    public MachineTracker tracker() {
        return tracker;
    }

    public MachineIsolation isolation() {
        return isolation;
    }

    public WarningService warnings() {
        return warnings;
    }

    public ActionValidator validator() {
        return validator;
    }

    public LUTEngine lut() {
        return lut;
    }

    public RedstoneGuardian guardian() {
        return guardian;
    }
}
