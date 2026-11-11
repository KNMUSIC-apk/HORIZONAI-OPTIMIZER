package com.horizon.ai.utils;

/** Vị trí block không phụ thuộc Bukkit, dùng trong các cấu trúc dữ liệu phân tích (thread-safe vì bất biến). */
public record BlockPos(String world, int x, int y, int z) {

    public long chunkKey() {
        return ((long) (x >> 4) & 0xFFFFFFFFL) << 32 | ((long) (z >> 4) & 0xFFFFFFFFL);
    }

    public long distanceSquared(BlockPos o) {
        long dx = x - o.x;
        long dy = y - o.y;
        long dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
