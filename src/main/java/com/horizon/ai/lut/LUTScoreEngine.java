package com.horizon.ai.lut;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tính điểm rủi ro 0-100 theo 8 nhóm độc lập, mỗi nhóm có trần cố định. Tổng các trần = 100.
 *
 * Vì sao dùng trần theo nhóm: ΔMSPT, Pearson và độ ổn định đều đo cùng một hiện tượng (máy hoạt động
 * đi cùng lag). Nếu cộng thẳng sẽ đếm trùng bằng chứng. Mỗi nhóm được cộng nhiều nhất bằng trần của nó.
 *
 * Nhóm (trần):
 *  1. Cường độ sự kiện      (10): >= threshold +6; >= 2x threshold +10
 *  2. Bất thường hoạt động  (22): tra bảng tỉ lệ / baseline (chỉ khi >= threshold) + 2 nếu >= 3 cửa sổ liên tiếp cao
 *  3. Clock ổn định         (12): clock đều và đang hoạt động; chỉ là dấu hiệu hoạt động, không chứng minh gây lag
 *  4. Hiệu năng             (30): tra bảng ΔMSPT + bonus MSPT server >= mspt-critical
 *  5. Tương quan            (10): Pearson >= correlation-minimum
 *  6. Cấu tạo               (4):  có piston/hopper/dispenser (+2); >= 20 linh kiện redstone (+2)
 *  7. Máy đồng thời         (4):  >= 1 máy khác cùng hoạt động (+2); >= 3 (+4)
 *  8. Ổn định lịch sử       (8):  suy giảm MSPT nhất quán giữa hai nửa lịch sử (round(consistency x 8))
 *
 * Điểm không bao giờ âm, không bao giờ vượt 100, NaN/Infinity được xử lý trước khi cộng.
 * Điểm này KHÔNG tự cho phép cách ly: xem {@link LUTDecisionEngine}.
 */
public final class LUTScoreEngine {

    static final int INTENSITY_CAP = 10;
    static final int ACTIVITY_CAP = 22;
    static final int CLOCK_CAP = 12;
    static final int PERFORMANCE_CAP = 30;
    static final int CORRELATION_CAP = 10;
    static final int COMPOSITION_CAP = 4;
    static final int CONCURRENCY_CAP = 4;
    static final int STABILITY_CAP = 8;

    private LUTScoreEngine() {
    }

    public static LUTScore score(LUTFeatures f, LUTProfile p) {
        LUTWeights w = p.weights();
        LUTThresholds t = p.thresholds();
        int threshold = t.eventThreshold();
        List<String> rules = new ArrayList<>();

        int intensity = 0;
        if (f.eventsNow() >= threshold) {
            intensity = f.eventsNow() >= 2L * threshold ? 10 : 6;
        }
        intensity = Math.min(INTENSITY_CAP, intensity);
        if (intensity > 0) {
            rules.add(String.format(Locale.ROOT, "Cường độ: %d sự kiện/cửa sổ (ngưỡng %d): +%d",
                    f.eventsNow(), threshold, intensity));
        }

        int activity = 0;
        if (f.eventsNow() >= threshold) {
            activity = MachineLUT.eventRatioPoints(f.eventRatio(), w, t);
            if (activity > 0) {
                rules.add(String.format(Locale.ROOT, "Tỉ lệ so với baseline %.2fx: +%d", f.eventRatio(), activity));
            }
            if (f.consecutiveHighWindows() >= 3) {
                activity += 2;
                rules.add("Hoạt động cao liên tiếp " + f.consecutiveHighWindows() + " cửa sổ: +2");
            }
        }
        activity = Math.min(ACTIVITY_CAP, activity);

        int clock = f.stableClock() ? Math.min(CLOCK_CAP, w.stableClockPoints()) : 0;
        if (clock > 0) {
            rules.add(String.format(Locale.ROOT, "Clock đều %.2f Hz (CV %.2f): +%d", f.clockHz(), f.clockCv(), clock));
        }

        int performance = 0;
        if (f.deltaValid()) {
            performance = MachineLUT.msptDeltaPoints(f.msptDelta(), w, t);
            if (performance > 0) {
                rules.add(String.format(Locale.ROOT, "ΔMSPT khi hoạt động %.1f ms: +%d", f.msptDelta(), performance));
            }
        }
        if (f.serverMspt() >= t.criticalMspt()) {
            performance += w.criticalMsptPoints();
            rules.add(String.format(Locale.ROOT, "MSPT server %.1f ms >= ngưỡng nguy hiểm: +%d",
                    f.serverMspt(), w.criticalMsptPoints()));
        }
        performance = Math.min(PERFORMANCE_CAP, performance);

        int correlation = 0;
        if (f.correlationValid() && f.correlation() >= t.correlationMinimum()) {
            correlation = Math.min(CORRELATION_CAP, w.correlationPoints());
            rules.add(String.format(Locale.ROOT, "Pearson r=%.2f >= %.2f: +%d",
                    f.correlation(), t.correlationMinimum(), correlation));
        }

        int composition = 0;
        LUTInput.Composition c = f.composition();
        if (c.containable() > 0) {
            composition += 2;
        }
        if (c.redstoneParts() >= 20) {
            composition += 2;
        }
        composition = Math.min(COMPOSITION_CAP, composition);

        int concurrency = f.otherActive() >= 3 ? 4 : f.otherActive() >= 1 ? 2 : 0;
        concurrency = Math.min(CONCURRENCY_CAP, concurrency);
        if (concurrency > 0) {
            rules.add(f.otherActive() + " máy khác cùng hoạt động: +" + concurrency);
        }

        int stability = 0;
        if (f.deltaValid() && f.msptDelta() > 0) {
            stability = (int) Math.round(f.deltaConsistency() * STABILITY_CAP);
            stability = Math.min(STABILITY_CAP, stability);
            if (stability > 0) {
                rules.add(String.format(Locale.ROOT, "Suy giảm MSPT nhất quán giữa hai nửa lịch sử: +%d", stability));
            }
        }

        int sum = intensity + activity + clock + performance + correlation + composition + concurrency + stability;
        int total = Math.max(0, Math.min(100, sum));
        return new LUTScore(total, intensity, activity, clock, performance, correlation,
                composition, concurrency, stability, List.copyOf(rules));
    }
}
