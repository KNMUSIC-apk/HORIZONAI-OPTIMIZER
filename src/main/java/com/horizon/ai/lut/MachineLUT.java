package com.horizon.ai.lut;

/**
 * Bảng tra (lookup table) đa chiều: ánh xạ một giá trị đặc trưng sang số điểm theo các bậc.
 * Mọi bậc đều đọc từ {@link LUTWeights} và {@link LUTThresholds}, không có hằng số ẩn.
 *
 * Bảng mặc định:
 *  - Tỉ lệ sự kiện / baseline: >= low +8, >= medium +14, >= high +20
 *  - ΔMSPT: >= 1x ngưỡng +8, >= 2x +14, >= 3x +20 (mặc định ngưỡng 5 ms → 5/10/15 ms)
 */
public final class MachineLUT {

    private MachineLUT() {
    }

    /** Điểm theo tỉ lệ sự kiện so với baseline. Bậc cao nhất đạt được mới được tính. */
    public static int eventRatioPoints(double ratio, LUTWeights w, LUTThresholds t) {
        if (ratio >= t.eventRateHigh()) {
            return w.eventRateHighPoints();
        }
        if (ratio >= t.eventRateMedium()) {
            return w.eventRateMediumPoints();
        }
        if (ratio >= t.eventRateLow()) {
            return w.eventRateLowPoints();
        }
        return 0;
    }

    /** Điểm theo chênh MSPT khi máy hoạt động so với nhàn rỗi. Bậc: 1x, 2x, 3x ngưỡng msptDeltaMinimum. */
    public static int msptDeltaPoints(double delta, LUTWeights w, LUTThresholds t) {
        double m = t.msptDeltaMinimum();
        if (delta >= 3.0 * m) {
            return w.msptDeltaHighPoints();
        }
        if (delta >= 2.0 * m) {
            return w.msptDeltaMediumPoints();
        }
        if (delta >= m) {
            return w.msptDeltaLowPoints();
        }
        return 0;
    }
}
