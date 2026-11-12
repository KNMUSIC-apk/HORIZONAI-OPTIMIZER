package com.horizon.ai.chunk;

import com.horizon.ai.lut.EntityCleanupPlanner;
import com.horizon.ai.utils.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Tameable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Tối ưu thực thể theo chunk: thuyền, mũi tên, cầu tuyết.
 *
 * - Khi số lượng trong MỘT chunk vượt max, xóa bớt về target (không xóa thêm).
 * - Khi xóa cầu tuyết trong chunk và remove-snowmen-with-snowballs = true, xóa luôn người tuyết trong chunk đó.
 * - KHÔNG BAO GIỜ xóa: người chơi, thực thể có tên tùy chỉnh, thực thể đang có người ngồi/bị buộc dây/đã thuần,
 *   thực thể đang cưỡi, và thực thể trong thế giới/vùng whitelist.
 * - Chỉ đọc chunk đã tải; không tải chunk mới. Xóa tối đa theo planner, luôn trên Main Thread.
 *
 * LUỒNG: Main Thread (API Bukkit yêu cầu).
 */
public final class EntityOptimizer {

    private static final long LOG_COOLDOWN_MS = 60_000L;

    private final Plugin plugin;
    private final Settings settings;
    private final Logger logger;
    private int cursor;
    private BukkitTask task;

    private long removedBoats;
    private long removedArrows;
    private long removedSnowballs;
    private long removedSnowmen;

    private int pendingBoats;
    private int pendingArrows;
    private int pendingSnowballs;
    private int pendingSnowmen;
    private long lastLogMs;

    public EntityOptimizer(Plugin plugin, Settings settings, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = logger;
    }

    public void start() {
        EntityOptimizerConfig cfg = settings.entityOptimizer;
        if (!cfg.enabled()) {
            return;
        }
        long ticks = 20L * cfg.scanIntervalSeconds();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::cycle, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Tóm tắt số đã xóa từ lúc bật, dùng cho /horizonai lut status. */
    public String summary() {
        return "Đã xóa: thuyền " + removedBoats + ", mũi tên " + removedArrows
                + ", cầu tuyết " + removedSnowballs + ", người tuyết " + removedSnowmen;
    }

    private void cycle() {
        EntityOptimizerConfig cfg = settings.entityOptimizer;
        List<Chunk> all = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (settings.isWorldWhitelisted(w.getName())) {
                continue;
            }
            for (Chunk c : w.getLoadedChunks()) {
                all.add(c);
            }
        }
        int n = all.size();
        if (n == 0) {
            return;
        }
        int budget = Math.min(cfg.chunksPerCycle(), n);
        for (int i = 0; i < budget; i++) {
            optimizeChunk(all.get((cursor + i) % n), cfg);
        }
        cursor = (cursor + budget) % n;
        flushLog();
    }

    private void optimizeChunk(Chunk chunk, EntityOptimizerConfig cfg) {
        List<Boat> boats = new ArrayList<>();
        List<Arrow> arrows = new ArrayList<>();
        List<Snowball> snowballs = new ArrayList<>();
        List<Snowman> snowmen = new ArrayList<>();
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Boat b) {
                boats.add(b);
            } else if (e instanceof Arrow a) {
                arrows.add(a);
            } else if (e instanceof Snowball s) {
                snowballs.add(s);
            } else if (e instanceof Snowman sm) {
                snowmen.add(sm);
            }
        }

        int b = trim(boats, cfg.boatMax(), cfg.boatTarget());
        int a = trim(arrows, cfg.arrowMax(), cfg.arrowTarget());
        int s = trim(snowballs, cfg.snowballMax(), cfg.snowballTarget());
        int sm = 0;
        if (s > 0 && cfg.removeSnowmenWithSnowballs()) {
            sm = trimAll(snowmen);
        }

        removedBoats += b;
        removedArrows += a;
        removedSnowballs += s;
        removedSnowmen += sm;
        pendingBoats += b;
        pendingArrows += a;
        pendingSnowballs += s;
        pendingSnowmen += sm;
    }

    /** Xóa bớt theo planner; ưu tiên thực thể sống lâu nhất. Trả về số đã xóa. */
    private int trim(List<? extends Entity> all, int max, int target) {
        int want = EntityCleanupPlanner.removeCount(all.size(), max, target);
        if (want == 0) {
            return 0;
        }
        List<Entity> candidates = new ArrayList<>();
        for (Entity e : all) {
            if (!isProtected(e)) {
                candidates.add(e);
            }
        }
        candidates.sort(Comparator.comparingInt(Entity::getTicksLived).reversed());
        int done = 0;
        for (Entity e : candidates) {
            if (done >= want) {
                break;
            }
            if (e.isValid()) {
                e.remove();
                done++;
            }
        }
        return done;
    }

    /** Xóa mọi người tuyết không được bảo vệ trong chunk. */
    private int trimAll(List<? extends Entity> all) {
        int done = 0;
        for (Entity e : all) {
            if (!isProtected(e) && e.isValid()) {
                e.remove();
                done++;
            }
        }
        return done;
    }

    private boolean isProtected(Entity e) {
        if (isProtectedLocation(e)) {
            return true;
        }
        if (e.getCustomName() != null) {
            return true;
        }
        if (!e.getPassengers().isEmpty() || e.getVehicle() != null) {
            return true;
        }
        if (e instanceof LivingEntity le && le.isLeashed()) {
            return true;
        }
        if (e instanceof Tameable t && t.isTamed()) {
            return true;
        }
        return false;
    }

    private boolean isProtectedLocation(Entity e) {
        return settings.isInWhitelistedRegion(e.getWorld().getName(),
                e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ());
    }

    private void flushLog() {
        int total = pendingBoats + pendingArrows + pendingSnowballs + pendingSnowmen;
        if (total == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLogMs < LOG_COOLDOWN_MS) {
            return;
        }
        lastLogMs = now;
        logger.info("[HorizonAI] Tối ưu thực thể đã xóa: thuyền " + pendingBoats + ", mũi tên " + pendingArrows
                + ", cầu tuyết " + pendingSnowballs + ", người tuyết " + pendingSnowmen + " (trong chunk quá tải).");
        pendingBoats = 0;
        pendingArrows = 0;
        pendingSnowballs = 0;
        pendingSnowmen = 0;
    }
}
