package com.horizon.ai.lut;

/**
 * Hồ sơ chấm điểm đầy đủ của LUT: bảng điểm, ngưỡng, cờ an toàn và giới hạn khối lượng công việc.
 * Bất biến. Khi /horizonai lut reload, một profile mới được dựng từ config mới.
 */
public record LUTProfile(
        LUTWeights weights,
        LUTThresholds thresholds,
        LUTSafety safety,
        int maxMachinesPerWindow,
        double maxAnalysisMsPerWindow) {

    public LUTProfile {
        if (weights == null) {
            weights = LUTWeights.defaults();
        }
        if (thresholds == null) {
            thresholds = LUTThresholds.defaults();
        }
        if (safety == null) {
            safety = LUTSafety.defaults();
        }
        maxMachinesPerWindow = Math.max(1, Math.min(256, maxMachinesPerWindow));
        maxAnalysisMsPerWindow = Double.isFinite(maxAnalysisMsPerWindow)
                ? Math.max(0.5, Math.min(50.0, maxAnalysisMsPerWindow)) : 5.0;
    }

    public static LUTProfile defaults() {
        return new LUTProfile(LUTWeights.defaults(), LUTThresholds.defaults(), LUTSafety.defaults(), 30, 5.0);
    }
}
