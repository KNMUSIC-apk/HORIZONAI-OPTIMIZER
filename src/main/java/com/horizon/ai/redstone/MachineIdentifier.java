package com.horizon.ai.redstone;

import com.horizon.ai.utils.BlockPos;
import com.horizon.ai.utils.Region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gom các block Redstone hoạt động thành "cụm" (candidate machine) bằng BFS theo khoảng cách Chebyshev.
 * Thuần Java, không phụ thuộc Bukkit, nên có thể kiểm thử đơn vị.
 *
 * Nguyên tắc an toàn:
 *  - Cụm quá rộng (vượt maxSpan) được đánh dấu oversized: không bao giờ được cách ly.
 *  - Hai cụm có hộp mở rộng gần nhau (trong sharedMargin) được đánh dấu ambiguous:
 *    có thể dùng chung nguồn tín hiệu, nên chỉ cảnh báo và yêu cầu Admin kiểm tra.
 */
public final class MachineIdentifier {

    private MachineIdentifier() {
    }

    /** Một block hoạt động, kèm thống kê chu kỳ của riêng block đó trong cửa sổ hiện tại. */
    public record Point(BlockPos pos, int events, double frequencyHz, double intervalCv, int samples) {
    }

    public record Cluster(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          List<Point> points, int events, double clockFrequencyHz, double clockIntervalCv,
                          boolean oversized, boolean ambiguous) {

        public Region region() {
            return new Region(world, minX, minY, minZ, maxX, maxY, maxZ);
        }

        public Region expandedRegion(int margin) {
            return region().expand(margin);
        }

        public boolean isContainable() {
            return !oversized && !ambiguous;
        }
    }

    public static List<Cluster> identify(Collection<Point> input, int linkDistance, int maxSpan, int sharedMargin) {
        Map<BlockPos, Point> index = new HashMap<>(input.size() * 2);
        for (Point p : input) {
            index.put(p.pos(), p);
        }

        List<List<Point>> groups = new ArrayList<>();
        Map<BlockPos, Boolean> visited = new HashMap<>(input.size() * 2);
        for (Point start : input) {
            if (visited.containsKey(start.pos())) {
                continue;
            }
            List<Point> group = new ArrayList<>();
            Deque<Point> queue = new ArrayDeque<>();
            queue.add(start);
            visited.put(start.pos(), true);
            while (!queue.isEmpty()) {
                Point cur = queue.poll();
                group.add(cur);
                BlockPos c = cur.pos();
                for (int dx = -linkDistance; dx <= linkDistance; dx++) {
                    for (int dy = -linkDistance; dy <= linkDistance; dy++) {
                        for (int dz = -linkDistance; dz <= linkDistance; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            BlockPos n = new BlockPos(c.world(), c.x() + dx, c.y() + dy, c.z() + dz);
                            Point np = index.get(n);
                            if (np != null && !visited.containsKey(n)) {
                                visited.put(n, true);
                                queue.add(np);
                            }
                        }
                    }
                }
            }
            groups.add(group);
        }

        List<Cluster> clusters = new ArrayList<>(groups.size());
        for (List<Point> g : groups) {
            clusters.add(buildCluster(g, maxSpan));
        }

        // Đánh dấu ambiguous cho các cụm có hộp mở rộng chồng/gần nhau
        boolean[] ambiguous = new boolean[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            for (int j = i + 1; j < clusters.size(); j++) {
                Cluster a = clusters.get(i);
                Cluster b = clusters.get(j);
                if (a.world().equals(b.world()) && a.expandedRegion(sharedMargin).intersects(b.region())) {
                    ambiguous[i] = true;
                    ambiguous[j] = true;
                }
            }
        }
        List<Cluster> result = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            result.add(new Cluster(c.world(), c.minX(), c.minY(), c.minZ(), c.maxX(), c.maxY(), c.maxZ(),
                    c.points(), c.events(), c.clockFrequencyHz(), c.clockIntervalCv(),
                    c.oversized(), ambiguous[i]));
        }
        return result;
    }

    private static Cluster buildCluster(List<Point> g, int maxSpan) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int events = 0;
        Point clock = null;
        for (Point p : g) {
            BlockPos b = p.pos();
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            minZ = Math.min(minZ, b.z());
            maxX = Math.max(maxX, b.x());
            maxY = Math.max(maxY, b.y());
            maxZ = Math.max(maxZ, b.z());
            events += p.events();
            // Điểm có tần số cao nhất và đủ mẫu được xem là ứng viên clock
            if (p.samples() >= 5 && (clock == null || p.frequencyHz() > clock.frequencyHz())) {
                clock = p;
            }
        }
        boolean oversized = (maxX - minX + 1) > maxSpan || (maxY - minY + 1) > maxSpan || (maxZ - minZ + 1) > maxSpan;
        String world = g.get(0).pos().world();
        return new Cluster(world, minX, minY, minZ, maxX, maxY, maxZ, List.copyOf(g), events,
                clock == null ? 0.0 : clock.frequencyHz(),
                clock == null ? Double.MAX_VALUE : clock.intervalCv(),
                oversized, false);
    }
}
