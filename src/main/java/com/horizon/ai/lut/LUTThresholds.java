package com.horizon.ai.lut;

/**
 * Ngưỡng của LUT. Compact constructor kẹp mọi giá trị vào khoảng hợp lệ và sửa thứ tự ngưỡng,
 * để LUT không bao giờ chạy với cấu hình vô nghĩa. Settings đã ghi log cảnh báo trước khi kẹp.
 *
 * Quy ước điểm mức nghiêm trọng: 0-24 NORMAL, 25-44 LOW (cố định), 45-64 MEDIUM, 65-79 HIGH, 80-100 CRITICAL.
 * warningScore, highScore, criticalScore cấu hình ranh giới MEDIUM, HIGH, CRITICAL.
 */
public record LUTThresholds(
        double eventRateLow,
        double eventRateMedium,
        double eventRateHigh,
        double correlationMinimum,
        double msptDeltaMinimum,
        double criticalMspt,
        int warningScore,
        int highScore,
        int criticalScore,
        int minSamples,
        int historySize,
        double minimumConfidence,
        double clockMinFrequencyHz,
        double clockMaxIntervalCv,
        int eventThreshold) {

    public static final int LOW_SCORE = 25;

    public LUTThresholds {
        eventRateLow = finitePositive(eventRateLow, 1.0);
        eventRateMedium = Math.max(eventRateLow, finitePositive(eventRateMedium, 2.0));
        eventRateHigh = Math.max(eventRateMedium, finitePositive(eventRateHigh, 3.0));
        correlationMinimum = clamp(correlationMinimum, 0.10, 0.99, 0.50);
        msptDeltaMinimum = finitePositive(msptDeltaMinimum, 5.0);
        criticalMspt = finitePositive(criticalMspt, 60.0);
        warningScore = clampInt(warningScore, LOW_SCORE + 1, 99);
        highScore = clampInt(highScore, warningScore + 1, 99);
        criticalScore = clampInt(criticalScore, highScore + 1, 100);
        minSamples = clampInt(minSamples, 4, 120);
        historySize = clampInt(historySize, 4, 120);
        minimumConfidence = clamp(minimumConfidence, 0.0, 1.0, 0.70);
        clockMinFrequencyHz = clamp(clockMinFrequencyHz, 0.1, 1000.0, 2.0);
        clockMaxIntervalCv = clamp(clockMaxIntervalCv, 0.01, 10.0, 0.25);
        eventThreshold = clampInt(eventThreshold, 10, 1_000_000);
    }

    public static LUTThresholds defaults() {
        return new LUTThresholds(1.0, 2.0, 3.0, 0.50, 5.0, 60.0,
                45, 65, 80, 6, 120, 0.70, 2.0, 0.25, 400);
    }

    private static double finitePositive(double v, double def) {
        return Double.isFinite(v) && v > 0 ? v : def;
    }

    private static double clamp(double v, double min, double max, double def) {
        if (!Double.isFinite(v)) {
            return def;
        }
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
