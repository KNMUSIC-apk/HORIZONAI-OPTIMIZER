package com.horizon.ai.redstone;

import com.horizon.ai.storage.Database;
import com.horizon.ai.utils.Region;
import com.horizon.ai.utils.Settings;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cách ly Level 2 có thể hoàn tác, chỉ trong hộp của ĐÚNG một máy.
 *
 * PHẠM VI TÁC ĐỘNG:
 *  - Piston / sticky piston: không đẩy/kéo (BlockPistonExtend/Retract bị hủy).
 *  - Dispenser / dropper: không phun/thả (BlockDispense bị hủy).
 *  - Hopper: không chuyển item vào/ra hopper trong hộp (InventoryMoveItem bị hủy).
 *
 * KHÔNG LÀM: không đổi/xóa block, không xóa item hay inventory, không tắt Redstone theo chunk/world/server.
 *
 * GIỚI HẠN PAPER API (phải nói rõ): BlockRedstoneEvent không thể hủy, nên không chặn được sự lan truyền
 * tín hiệu của dust/repeater/observer. Máy KHÔNG có piston/hopper/dispenser sẽ không bị cách ly tự động;
 * plugin chỉ cảnh báo và yêu cầu Admin kiểm tra.
 *
 * LUỒNG: mọi thay đổi trạng thái chạy trên Main Thread. Danh sách vùng cho listener là bản bất biến.
 */
public final class MachineIsolation {

    public static final long RESTORE_COOLDOWN_MS = 10 * 60 * 1000L;
    public static final int MAX_ISOLATIONS_PER_MACHINE = 5;

    public enum Outcome {
        OK, ALREADY_ISOLATED, NOT_ISOLATED, AMBIGUOUS_BOUNDARY, PARTIAL_SCAN,
        NO_CONTAINABLE_PARTS, COOLDOWN, TOO_MANY_ISOLATIONS
    }

    private final Settings settings;
    private final Database db;
    private final MachineTracker tracker;
    private final AtomicLong blockedActions = new AtomicLong();
    private volatile List<Region> activeRegions = List.of();

    public MachineIsolation(Settings settings, Database db, MachineTracker tracker) {
        this.settings = settings;
        this.db = db;
        this.tracker = tracker;
    }

    /** Dùng bởi IsolationListener. Đọc danh sách bất biến nên an toàn trên mọi luồng đọc. */
    public boolean isBlocked(String world, int x, int y, int z) {
        List<Region> regs = activeRegions;
        if (regs.isEmpty()) {
            return false;
        }
        for (Region r : regs) {
            if (r.contains(world, x, y, z)) {
                blockedActions.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    public long blockedActions() {
        return blockedActions.get();
    }

    public int activeCount() {
        return activeRegions.size();
    }

    /** Chỉ kiểm tra điều kiện, không thay đổi gì. */
    public Outcome canIsolate(Machine m, long nowMillis) {
        if (m.state == Machine.State.ISOLATED) {
            return Outcome.ALREADY_ISOLATED;
        }
        if (!m.boundaryClear || m.state == Machine.State.AMBIGUOUS) {
            return Outcome.AMBIGUOUS_BOUNDARY;
        }
        if (m.components.partial) {
            return Outcome.PARTIAL_SCAN;
        }
        if (m.containableParts() <= 0) {
            return Outcome.NO_CONTAINABLE_PARTS;
        }
        if (m.lastRestoredMillis > 0 && nowMillis - m.lastRestoredMillis < RESTORE_COOLDOWN_MS) {
            return Outcome.COOLDOWN;
        }
        if (m.isolationCount >= MAX_ISOLATIONS_PER_MACHINE) {
            return Outcome.TOO_MANY_ISOLATIONS;
        }
        return Outcome.OK;
    }

    /**
     * Cách ly máy. {@code adminApproved=true} chỉ bỏ qua cooldown và giới hạn số lần,
     * KHÔNG bỏ qua ranh giới không rõ, quét chưa đủ hay máy không có thành phần cách ly được.
     */
    public Outcome isolate(Machine m, String actor, long nowMillis, boolean adminApproved) {
        Outcome o = canIsolate(m, nowMillis);
        if (o == Outcome.ALREADY_ISOLATED) {
            return o;
        }
        if (o != Outcome.OK) {
            boolean overridable = adminApproved && (o == Outcome.COOLDOWN || o == Outcome.TOO_MANY_ISOLATIONS);
            if (!overridable) {
                return o;
            }
        }

        String snapshot = snapshotOf(m);
        m.state = Machine.State.ISOLATED;
        m.isolationCount++;
        m.isolationSnapshot = snapshot;
        m.pendingSinceMillis = 0;
        m.log("Cách ly bởi " + actor + ": " + snapshot);
        refresh();

        Region r = m.region;
        db.saveIsolation(new Database.IsolationRecord(m.id, r.world(), r.minX(), r.minY(), r.minZ(),
                r.maxX(), r.maxY(), r.maxZ(), "ISOLATED", nowMillis, actor, snapshot));
        if (settings.auditEnabled) {
            db.audit(actor, "ISOLATE", m.id, snapshot);
        }
        return Outcome.OK;
    }

    /**
     * Khôi phục máy: gỡ chặn và quét lại thành phần để so sánh với snapshot lúc cách ly.
     * Cách ly không đổi block nên không có gì cần hoàn tác trong world.
     */
    public Outcome restore(Machine m, String actor, long nowMillis, World world, int windowIndex) {
        if (m.state != Machine.State.ISOLATED) {
            return Outcome.NOT_ISOLATED;
        }
        m.state = Machine.State.OBSERVED;
        m.lastRestoredMillis = nowMillis;
        m.warningCount = 0;
        refresh();

        String before = m.isolationSnapshot;
        String after = "không quét được (world chưa tải)";
        if (world != null) {
            tracker.scanComponents(m, world, windowIndex);
            after = snapshotOf(m);
        }
        String note = "Khôi phục bởi " + actor + ". Trước: " + before + " | Sau: " + after;
        m.log(note);
        db.deleteIsolation(m.id);
        if (settings.auditEnabled) {
            db.audit(actor, "RESTORE", m.id, note);
        }
        return Outcome.OK;
    }

    /** Gọi sau mỗi thay đổi trạng thái máy, và khi khởi động để nạp lại vùng đã cách ly. */
    public void refresh() {
        List<Region> regs = new ArrayList<>();
        for (Machine m : tracker.all()) {
            if (m.state == Machine.State.ISOLATED) {
                regs.add(m.region);
            }
        }
        activeRegions = List.copyOf(regs);
    }

    private static String snapshotOf(Machine m) {
        Machine.Components c = m.components;
        return "pistons=" + c.pistons + ",hoppers=" + c.hoppers + ",dispensers=" + c.dispensers
                + ",observers=" + c.observers + ",tnt=" + c.tnt + ",redstone=" + c.redstoneParts
                + ",region=" + MachineTracker.describe(m.region);
    }
}
