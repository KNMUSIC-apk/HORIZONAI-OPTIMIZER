package com.horizon.ai.redstone;

import com.horizon.ai.lut.LUTResult;
import com.horizon.ai.utils.Region;
import com.horizon.ai.utils.Severity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Trạng thái của một máy Redstone được theo dõi.
 * Chỉ được đọc/ghi trên Main Thread (MachineTracker, RedstoneGuardian, lệnh).
 */
public final class Machine {

    public enum State {
        OBSERVED,           // đang theo dõi, chưa có vi phạm
        WARNED,             // đã gửi cảnh báo Level 1
        PENDING_ISOLATION,  // đủ điều kiện Level 2, chờ trì hoãn/Admin duyệt
        ISOLATED,           // đang bị cách ly (có thể hoàn tác)
        AMBIGUOUS           // ranh giới không rõ: chỉ cảnh báo, không cách ly
    }

    /** Ghi nhận khi quét thành phần máy (chỉ đếm, không thay đổi block). */
    public static final class Components {
        public int pistons;
        public int hoppers;
        public int dispensers;
        public int observers;
        public int tnt;
        public int redstoneParts;
        public boolean partial; // true nếu một phần vùng chưa tải chunk và không được quét
    }

    private static final int MAX_SERIES = 120;
    private static final int MAX_LOG = 50;

    public final String id;
    public final Components components = new Components();
    public final Deque<double[]> series = new ArrayDeque<>();
    public final List<String> journal = new ArrayList<>();

    public String world;
    public Region region;
    public State state = State.OBSERVED;
    public long firstSeenMillis;
    public long lastActiveMillis;
    public long totalEvents;
    public double baselineEvents = -1;
    public int lastSeenWindow;
    public int lastScanWindow = -1000;

    public int warningCount;
    public long lastWarningMillis;
    /** Thời điểm bắt đầu đợi tự cách ly (lần cảnh báo đầu tiên trong đợt hiện tại), 0 nếu chưa có. */
    public long warnStartMillis;
    public int isolationCount;
    public long lastRestoredMillis;
    public long pendingSinceMillis;
    public String isolationSnapshot = "";

    public boolean boundaryClear = true;
    public boolean correlated;
    public Severity severity = Severity.NORMAL;
    public double confidence;
    public boolean eligible;
    public List<String> evidence = List.of();
    public String builderName;
    public String activatorName;
    /** Kết quả LUT gần nhất (null nếu chưa phân tích). Chỉ đọc/ghi trên Main Thread. */
    public LUTResult lastLut;

    public Machine(String id, String world, Region region, long nowMillis) {
        this.id = id;
        this.world = world;
        this.region = region;
        this.firstSeenMillis = nowMillis;
        this.lastActiveMillis = nowMillis;
    }

    public void addSeries(double events, double mspt) {
        series.addLast(new double[]{events, mspt});
        while (series.size() > MAX_SERIES) {
            series.removeFirst();
        }
    }

    public double[] seriesActivity() {
        double[] out = new double[series.size()];
        int i = 0;
        for (double[] p : series) {
            out[i++] = p[0];
        }
        return out;
    }

    public double[] seriesMspt() {
        double[] out = new double[series.size()];
        int i = 0;
        for (double[] p : series) {
            out[i++] = p[1];
        }
        return out;
    }

    public void log(String entry) {
        journal.add(entry);
        if (journal.size() > MAX_LOG) {
            journal.remove(0);
        }
    }

    public int containableParts() {
        return components.pistons + components.hoppers + components.dispensers;
    }

    public boolean isActiveState() {
        return state == State.ISOLATED;
    }
}
