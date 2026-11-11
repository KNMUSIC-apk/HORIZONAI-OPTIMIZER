package com.horizon.ai.redstone;

import com.horizon.ai.utils.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghi nhận sự kiện Redstone theo block với giới hạn bộ nhớ cứng.
 *
 * LUỒNG: chỉ được gọi trên Main Thread (event Bukkit và task đồng bộ). Snapshot được
 * "rút" (drain) trên Main Thread rồi chuyển sang luồng async để phân tích.
 *
 * Với mỗi block: số lần kích hoạt và thống kê Welford của khoảng thời gian giữa các lần,
 * dùng để phát hiện chu kỳ (clock) mà không cần lưu toàn bộ timestamp.
 */
public final class ActivityTracker {

    public record Snapshot(List<MachineIdentifier.Point> points, long totalEvents, int droppedBlocks,
                           Map<Long, Integer> chunkEvents, double windowSeconds) {
    }

    private static final class BlockActivity {
        int count;
        long firstNanos;
        long lastNanos;
        int intervals;
        double mean;
        double m2;
    }

    private final int maxBlocks;
    private final int maxChunks;
    private final Map<BlockPos, BlockActivity> blocks = new HashMap<>();
    private final Map<Long, Integer> chunks = new HashMap<>();
    private long totalEvents;
    private int droppedBlocks;
    private long windowStartNanos = System.nanoTime();

    public ActivityTracker(int maxBlocks, int maxChunks) {
        this.maxBlocks = maxBlocks;
        this.maxChunks = maxChunks;
    }

    /** Gọi trên Main Thread. Hàm này O(1), không quét world. */
    public void record(BlockPos pos, long nowNanos) {
        totalEvents++;
        long ck = pos.chunkKey();
        if (chunks.containsKey(ck) || chunks.size() < maxChunks) {
            chunks.merge(ck, 1, Integer::sum);
        }

        BlockActivity a = blocks.get(pos);
        if (a == null) {
            if (blocks.size() >= maxBlocks) {
                droppedBlocks++;
                return;
            }
            a = new BlockActivity();
            a.firstNanos = nowNanos;
            blocks.put(pos, a);
        } else {
            double intervalSec = (nowNanos - a.lastNanos) / 1_000_000_000.0;
            a.intervals++;
            double delta = intervalSec - a.mean;
            a.mean += delta / a.intervals;
            a.m2 += delta * (intervalSec - a.mean);
        }
        a.count++;
        a.lastNanos = nowNanos;
    }

    /** Lấy snapshot và reset cửa sổ. Gọi trên Main Thread. */
    public Snapshot drain(long nowNanos) {
        double windowSeconds = Math.max(0.001, (nowNanos - windowStartNanos) / 1_000_000_000.0);
        List<MachineIdentifier.Point> points = new ArrayList<>(blocks.size());
        for (Map.Entry<BlockPos, BlockActivity> e : blocks.entrySet()) {
            BlockActivity a = e.getValue();
            double freq = a.count > 1
                    ? (a.count - 1) / ((a.lastNanos - a.firstNanos) / 1_000_000_000.0)
                    : 0.0;
            double cv = Double.MAX_VALUE;
            if (a.intervals >= 2 && a.mean > 0) {
                cv = Math.sqrt(a.m2 / (a.intervals - 1)) / a.mean;
            }
            points.add(new MachineIdentifier.Point(e.getKey(), a.count, freq, cv, a.intervals));
        }
        Snapshot s = new Snapshot(List.copyOf(points), totalEvents, droppedBlocks,
                Map.copyOf(chunks), windowSeconds);
        blocks.clear();
        chunks.clear();
        totalEvents = 0;
        droppedBlocks = 0;
        windowStartNanos = nowNanos;
        return s;
    }

    public int trackedBlocks() {
        return blocks.size();
    }
}
