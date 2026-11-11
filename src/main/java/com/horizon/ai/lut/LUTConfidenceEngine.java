package com.horizon.ai.lut;

/**
 * Độ tin cậy 0.0-1.0, tách hoàn toàn khỏi điểm rủi ro. Điểm cao với độ tin cậy thấp là trường hợp
 * "nghi ngờ nhưng chưa đủ bằng chứng", và không bao giờ đủ điều kiện cách ly.
 *
 * Công thức (tổng trọng số = 1.00):
 *   conf = 0.20 x sample + 0.25 x correlation + 0.15 x delta + 0.15 x consistency
 *        + 0.10 x stability + 0.15 x concurrency
 *
 *   sample       = 0 nếu n < min-samples, ngược lại min(1, n / 60)
 *   correlation  = (r - correlation-minimum) / (1 - correlation-minimum), kẹp [0, 1]; 0 nếu r không hợp lệ
 *   delta        = min(1, ΔMSPT / (2 x mspt-delta-minimum)); 0 nếu ΔMSPT <= 0
 *   consistency  = tỉ lệ hai nửa lịch sử có ΔMSPT >= mspt-delta-minimum (0, 0.5, 1)
 *   stability    = 1 / (1 + CV hoạt động)
 *   concurrency  = 1 / (1 + số máy khác cùng hoạt động)
 *
 * Cổng cứng: ranh giới không rõ HOẶC quét thành phần chưa đầy đủ → độ tin cậy = 0.
 */
public final class LUTConfidenceEngine {

    static final int IDEAL_SAMPLES = 60;

    private LUTConfidenceEngine() {
    }

    public static double confidence(LUTFeatures f, LUTThresholds t) {
        if (!f.boundaryClear() || !f.scanComplete()) {
            return 0.0;
        }
        double sample = f.sampleCount() >= t.minSamples()
                ? LUTStats.clamp((double) f.sampleCount() / IDEAL_SAMPLES, 0.0, 1.0) : 0.0;

        double corr = 0.0;
        if (f.correlationValid() && f.correlation() >= t.correlationMinimum()) {
            corr = LUTStats.clamp(
                    (f.correlation() - t.correlationMinimum()) / (1.0 - t.correlationMinimum()), 0.0, 1.0);
        }

        double delta = 0.0;
        if (f.deltaValid() && f.msptDelta() > 0) {
            delta = LUTStats.clamp(f.msptDelta() / (2.0 * t.msptDeltaMinimum()), 0.0, 1.0);
        }

        double consistency = LUTStats.clamp(f.deltaConsistency(), 0.0, 1.0);
        double stability = LUTStats.clamp(f.stability(), 0.0, 1.0);
        double concurrency = 1.0 / (1.0 + Math.max(0, f.otherActive()));

        double conf = 0.20 * sample + 0.25 * corr + 0.15 * delta
                + 0.15 * consistency + 0.10 * stability + 0.15 * concurrency;
        return LUTStats.clamp(conf, 0.0, 1.0);
    }
}
