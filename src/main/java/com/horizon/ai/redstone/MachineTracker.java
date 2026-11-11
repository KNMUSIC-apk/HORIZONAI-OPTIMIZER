package com.horizon.ai.redstone;

import com.horizon.ai.storage.Database;
import com.horizon.ai.utils.Region;
import com.horizon.ai.utils.Settings;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry các máy Redstone. Mỗi máy có ID riêng, ranh giới, lịch sử và thống kê.
 * ID được sinh từ world + góc min của hộp, nên ổn định qua các lần khởi động.
 *
 * LUỒNG: chỉ dùng trên Main Thread.
 */
public final class MachineTracker {

    /** Số cửa sổ liên tiếp không hoạt động trước khi máy được bỏ khỏi registry (giải phóng bộ nhớ). */
    private static final int STALE_WINDOWS = 30;
    /** Chu kỳ quét lại thành phần máy (theo số cửa sổ). */
    private static final int RESCAN_EVERY_WINDOWS = 6;
    /** Không quét vùng quá lớn trên Main Thread. */
    private static final long MAX_SCAN_VOLUME = 16_384L;

    private final Settings settings;
    private final Database db;
    private final Map<String, Machine> machines = new LinkedHashMap<>();

    public MachineTracker(Settings settings, Database db) {
        this.settings = settings;
        this.db = db;
    }

    public Collection<Machine> all() {
        return machines.values();
    }

    public Optional<Machine> get(String id) {
        return Optional.ofNullable(machines.get(id));
    }

    public int size() {
        return machines.size();
    }

    /**
     * Khớp một cụm hoạt động với máy đã biết (cùng world và hộp giao nhau), hoặc tạo máy mới.
     * Trả về null nếu đã đạt giới hạn số máy theo dõi.
     */
    public Machine match(MachineIdentifier.Cluster cluster, int windowIndex, long nowMillis, boolean allowCreate) {
        Region r = cluster.region();
        for (Machine m : machines.values()) {
            if (m.world.equals(cluster.world()) && m.region.expand(1).intersects(r)) {
                m.region = r;
                m.lastSeenWindow = windowIndex;
                return m;
            }
        }
        if (!allowCreate || machines.size() >= settings.maxTrackedMachines) {
            return null;
        }
        String id = newId(cluster.world(), r);
        Machine m = new Machine(id, cluster.world(), r, nowMillis);
        m.lastSeenWindow = windowIndex;
        machines.put(id, m);
        m.log("Phát hiện máy mới, vùng " + describe(r));
        return m;
    }

    /** Quét đếm thành phần trong hộp máy (chỉ đọc, không đổi block). Giới hạn thể tích và chỉ chunk đã tải. */
    public void scanComponents(Machine m, World world, int windowIndex) {
        Region r = m.region;
        m.lastScanWindow = windowIndex;
        if (r.volume() > MAX_SCAN_VOLUME) {
            m.components.partial = true;
            return;
        }
        Machine.Components c = new Machine.Components();
        for (int x = r.minX(); x <= r.maxX(); x++) {
            for (int z = r.minZ(); z <= r.maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    c.partial = true;
                    continue;
                }
                for (int y = r.minY(); y <= r.maxY(); y++) {
                    countMaterial(world.getBlockType(x, y, z), c);
                }
            }
        }
        m.components.pistons = c.pistons;
        m.components.hoppers = c.hoppers;
        m.components.dispensers = c.dispensers;
        m.components.observers = c.observers;
        m.components.tnt = c.tnt;
        m.components.redstoneParts = c.redstoneParts;
        m.components.partial = c.partial;
    }

    private static void countMaterial(Material mat, Machine.Components c) {
        switch (mat) {
            case PISTON, STICKY_PISTON -> c.pistons++;
            case HOPPER -> c.hoppers++;
            case DISPENSER, DROPPER -> c.dispensers++;
            case OBSERVER -> c.observers++;
            case TNT -> c.tnt++;
            case REDSTONE_WIRE, REPEATER, COMPARATOR, REDSTONE_TORCH, REDSTONE_WALL_TORCH,
                 REDSTONE_BLOCK, LEVER, STONE_BUTTON, OAK_BUTTON, TARGET -> c.redstoneParts++;
            default -> { }
        }
    }

    /** Sau mỗi cửa sổ: máy không xuất hiện thì vẫn ghi mẫu 0 để tương quan có đủ dữ liệu, và dọn máy cũ. */
    public void endWindow(int windowIndex, double mspt) {
        var it = machines.values().iterator();
        while (it.hasNext()) {
            Machine m = it.next();
            if (m.lastSeenWindow != windowIndex && m.state != Machine.State.ISOLATED) {
                m.addSeries(0, mspt);
            }
            if (windowIndex - m.lastSeenWindow > STALE_WINDOWS && m.state != Machine.State.ISOLATED
                    && m.state != Machine.State.PENDING_ISOLATION) {
                it.remove();
            }
        }
    }

    public boolean needsRescan(Machine m, int windowIndex) {
        return windowIndex - m.lastScanWindow >= RESCAN_EVERY_WINDOWS;
    }

    /** Khôi phục máy đang cách ly từ DB sau khi server khởi động lại. */
    public Machine restoreIsolated(Database.IsolationRecord rec, long nowMillis) {
        Region r = new Region(rec.world(), rec.minX(), rec.minY(), rec.minZ(), rec.maxX(), rec.maxY(), rec.maxZ());
        Machine m = new Machine(rec.machineId(), rec.world(), r, nowMillis);
        m.state = Machine.State.ISOLATED;
        m.isolationSnapshot = rec.snapshot() == null ? "" : rec.snapshot();
        m.isolationCount = 1;
        m.log("Khôi phục cách ly sau khi khởi động lại server");
        machines.put(m.id, m);
        return m;
    }

    public void remove(String id) {
        machines.remove(id);
    }

    /** ID ổn định: world + góc min. Nếu trùng với vùng khác, thêm hậu tố để tránh nhầm lẫn. */
    private String newId(String world, Region r) {
        String base = String.format("HZ-%08X", (world + ":" + r.minX() + ":" + r.minY() + ":" + r.minZ()).hashCode());
        String id = base;
        int suffix = 1;
        while (machines.containsKey(id)) {
            id = base + "-" + suffix++;
        }
        return id;
    }

    public static String describe(Region r) {
        return r.world() + " [" + r.minX() + "," + r.minY() + "," + r.minZ() + " → "
                + r.maxX() + "," + r.maxY() + "," + r.maxZ() + "]";
    }
}
