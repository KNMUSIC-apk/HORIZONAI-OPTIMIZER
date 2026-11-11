package com.horizon.ai.chunk;

import com.horizon.ai.optimizer.WarningService;
import com.horizon.ai.utils.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Quét tăng dần (incremental) các chunk đã tải: entity, item, mob farm, hopper.
 *
 * - Mỗi chu kỳ chỉ đọc tối đa chunk.chunks-per-cycle chunk, luân phiên qua toàn bộ chunk.
 * - CHỈ CẢNH BÁO. Không xóa entity, item, inventory hay block (xem optimization.suggestions-only).
 * - Chỉ đọc dữ liệu đã tải; không tải chunk mới.
 *
 * LUỒNG: Main Thread (API Bukkit yêu cầu).
 */
public final class ChunkHealthScanner {

    private static final long CHUNK_WARN_COOLDOWN_MS = 10 * 60_000L;
    private static final long CYCLE_TICKS = 100L;

    private final Plugin plugin;
    private final Settings settings;
    private final WarningService warnings;
    private int cursor;
    private BukkitTask task;

    public ChunkHealthScanner(Plugin plugin, Settings settings, WarningService warnings) {
        this.plugin = plugin;
        this.settings = settings;
        this.warnings = warnings;
    }

    public void start() {
        if (!settings.chunkAnalysisEnabled) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::cycle, CYCLE_TICKS, CYCLE_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void cycle() {
        java.util.List<Chunk> all = new java.util.ArrayList<>();
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
        int budget = Math.min(settings.chunksPerCycle, n);
        for (int i = 0; i < budget; i++) {
            analyze(all.get((cursor + i) % n));
        }
        cursor = (cursor + budget) % n;
    }

    private void analyze(Chunk chunk) {
        String world = chunk.getWorld().getName();
        if (settings.isInWhitelistedRegion(world, chunk.getX() * 16, 64, chunk.getZ() * 16)) {
            return;
        }
        Entity[] entities = chunk.getEntities();
        int items = 0;
        Map<EntityType, Integer> mobs = new EnumMap<>(EntityType.class);
        for (Entity e : entities) {
            if (e instanceof Item) {
                items++;
            } else if (e instanceof LivingEntity && !(e instanceof Player)) {
                mobs.merge(e.getType(), 1, Integer::sum);
            }
        }
        int hoppers = 0;
        for (BlockState st : chunk.getTileEntities()) {
            if (st instanceof Hopper) {
                hoppers++;
            }
        }

        String where = world + " chunk(" + chunk.getX() + "," + chunk.getZ() + ")";
        if (entities.length > settings.chunkEntityWarning) {
            warn("entity", where, entities.length + " entity (ngưỡng " + settings.chunkEntityWarning + ")");
        }
        if (entities.length > settings.densityWarning) {
            warn("density", where, "Mật độ entity cao: " + entities.length + " trong vùng 16x16");
        }
        if (items > settings.itemOverflowWarning) {
            warn("items", where, items + " item rơi (ngưỡng " + settings.itemOverflowWarning + ")");
        }
        if (hoppers > settings.chunkHopperWarning) {
            warn("hopper", where, hoppers + " hopper (ngưỡng " + settings.chunkHopperWarning + ")");
        }
        for (Map.Entry<String, Integer> t : settings.mobThresholds.entrySet()) {
            EntityType type = parseType(t.getKey());
            if (type == null) {
                continue;
            }
            int count = mobs.getOrDefault(type, 0);
            if (count > t.getValue()) {
                warn("mob-" + type.name(), where, "Mob farm có dấu hiệu quá tải: " + count + " " + type.name()
                        + " (ngưỡng " + t.getValue() + ")");
            }
        }
    }

    private void warn(String kind, String where, String detail) {
        String key = kind + "@" + where;
        warnings.notifyAdmins("§e[Chunk] §f" + where + "§e: " + detail + ". Chỉ cảnh báo, không tự xóa.",
                key, System.currentTimeMillis(), CHUNK_WARN_COOLDOWN_MS);
    }

    private static EntityType parseType(String name) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
