package com.horizon.ai.chunk;

/**
 * Cấu hình tối ưu thực thể (lut.entities). Compact constructor kẹp giá trị vào khoảng hợp lệ,
 * đảm bảo target luôn nhỏ hơn max (để sau khi dọn còn chỗ thở).
 */
public record EntityOptimizerConfig(
        boolean enabled,
        int scanIntervalSeconds,
        int chunksPerCycle,
        int boatMax,
        int boatTarget,
        int arrowMax,
        int arrowTarget,
        int snowballMax,
        int snowballTarget,
        boolean removeSnowmenWithSnowballs) {

    public EntityOptimizerConfig {
        scanIntervalSeconds = clamp(scanIntervalSeconds, 1, 300, 5);
        chunksPerCycle = clamp(chunksPerCycle, 1, 256, 16);
        boatMax = clamp(boatMax, 1, 1000, 8);
        boatTarget = clamp(boatTarget, 0, boatMax - 1, 4);
        arrowMax = clamp(arrowMax, 1, 1000, 40);
        arrowTarget = clamp(arrowTarget, 0, arrowMax - 1, 20);
        snowballMax = clamp(snowballMax, 1, 1000, 40);
        snowballTarget = clamp(snowballTarget, 0, snowballMax - 1, 20);
    }

    public static EntityOptimizerConfig defaults() {
        return new EntityOptimizerConfig(true, 5, 16, 8, 4, 40, 20, 40, 20, true);
    }

    private static int clamp(int v, int min, int max, int def) {
        int hi = Math.max(min, max);
        return Math.max(min, Math.min(hi, v));
    }
}
