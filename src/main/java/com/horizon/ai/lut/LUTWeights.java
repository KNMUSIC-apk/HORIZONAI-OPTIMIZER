package com.horizon.ai.lut;

/**
 * Bảng điểm (score table) của LUT. Mỗi trường là số điểm cộng khi điều kiện tương ứng được thỏa.
 * Giá trị được kẹp vào [0, 30]; trần của từng nhóm điểm được cố định trong {@link LUTScoreEngine}.
 *
 * Mặc định khớp bảng ví dụ trong đặc tả:
 *  - Tỉ lệ sự kiện / baseline: >=1.0x +8, >=2.0x +14, >=3.0x +20
 *  - ΔMSPT: >=5 ms +8, >=10 ms +14, >=15 ms +20 (ngưỡng = mspt-delta-minimum x 1, x 2, x 3)
 *  - Clock ổn định lặp lại: +12
 *  - MSPT server >= mspt-critical: +12
 *  - Pearson >= correlation-minimum: +10
 */
public record LUTWeights(
        int eventRateLowPoints,
        int eventRateMediumPoints,
        int eventRateHighPoints,
        int msptDeltaLowPoints,
        int msptDeltaMediumPoints,
        int msptDeltaHighPoints,
        int stableClockPoints,
        int criticalMsptPoints,
        int correlationPoints) {

    public static final int MAX_POINTS = 30;

    public LUTWeights {
        eventRateLowPoints = clampPoints(eventRateLowPoints);
        eventRateMediumPoints = clampPoints(eventRateMediumPoints);
        eventRateHighPoints = clampPoints(eventRateHighPoints);
        msptDeltaLowPoints = clampPoints(msptDeltaLowPoints);
        msptDeltaMediumPoints = clampPoints(msptDeltaMediumPoints);
        msptDeltaHighPoints = clampPoints(msptDeltaHighPoints);
        stableClockPoints = clampPoints(stableClockPoints);
        criticalMsptPoints = clampPoints(criticalMsptPoints);
        correlationPoints = clampPoints(correlationPoints);
    }

    public static LUTWeights defaults() {
        return new LUTWeights(8, 14, 20, 8, 14, 20, 12, 12, 10);
    }

    private static int clampPoints(int v) {
        return Math.max(0, Math.min(MAX_POINTS, v));
    }
}
