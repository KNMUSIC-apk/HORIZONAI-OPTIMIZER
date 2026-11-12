package com.horizon.ai.lut;

import com.horizon.ai.utils.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phân loại mức nghiêm trọng và quyết định Level 0/1/2.
 *
 * Nguyên tắc: điểm số KHÔNG bao giờ đủ để cách ly. Level 2 chỉ đạt khi mọi cổng sau đều thỏa:
 *  1. Điểm >= high-score (HIGH trở lên).
 *  2. Đủ mẫu: n >= min-samples.
 *  3. Tương quan hợp lệ: Pearson >= correlation-minimum VÀ ΔMSPT >= mspt-delta-minimum.
 *  4. Ranh giới rõ ràng.
 *  5. Quét thành phần đầy đủ (không có chunk chưa tải).
 *  6. Có ít nhất một piston/hopper/dispenser để cách ly an toàn.
 *  7. Không có máy khác cùng hoạt động (không còn nguyên nhân cạnh tranh chưa giải quyết).
 *  8. Độ tin cậy >= minimum-confidence.
 *
 * Tương quan KHÔNG được coi là bằng chứng nhân quả; nó chỉ là một cổng cần có.
 */
public final class LUTDecisionEngine {

    private LUTDecisionEngine() {
    }

    /** 0-24 NORMAL, 25-44 LOW, >= warning MEDIUM, >= high HIGH, >= critical CRITICAL. */
    public static Severity severityOf(int score, LUTThresholds t) {
        if (score >= t.criticalScore()) {
            return Severity.CRITICAL;
        }
        if (score >= t.highScore()) {
            return Severity.HIGH;
        }
        if (score >= t.warningScore()) {
            return Severity.MEDIUM;
        }
        if (score >= LUTThresholds.LOW_SCORE) {
            return Severity.LOW;
        }
        return Severity.NORMAL;
    }

    public static boolean isCorrelated(LUTFeatures f, LUTThresholds t) {
        return f.correlationValid()
                && f.correlation() >= t.correlationMinimum()
                && f.deltaValid()
                && f.msptDelta() >= t.msptDeltaMinimum();
    }

    public static LUTDecision decide(LUTScore score, double confidence, LUTFeatures f, LUTProfile p) {
        LUTThresholds t = p.thresholds();
        LUTSafety s = p.safety();
        Severity severity = severityOf(score.total(), t);
        boolean correlated = isCorrelated(f, t);

        List<String> blockers = new ArrayList<>();
        if (score.total() < t.highScore()) {
            blockers.add(String.format(Locale.ROOT, "Điểm %d dưới ngưỡng cách ly %d", score.total(), t.highScore()));
        }
        if (f.sampleCount() < t.minSamples()) {
            blockers.add(String.format(Locale.ROOT, "Chưa đủ mẫu (%d/%d)", f.sampleCount(), t.minSamples()));
        }
        if (!correlated) {
            blockers.add("Chưa có tương quan hoạt động–MSPT hợp lệ với ΔMSPT đủ lớn");
        }
        if (!f.boundaryClear()) {
            blockers.add("Ranh giới máy không rõ ràng");
        }
        if (!f.scanComplete()) {
            blockers.add("Quét thành phần chưa đầy đủ (chunk chưa tải hoặc vượt giới hạn)");
        }
        if (f.composition().containable() == 0) {
            blockers.add("Không có piston/hopper/dispenser để cách ly an toàn");
        }
        if (f.otherActive() > 0) {
            blockers.add(f.otherActive() + " máy khác cùng hoạt động: nguyên nhân chưa được xác định");
        }
        if (confidence < t.minimumConfidence()) {
            blockers.add(String.format(Locale.ROOT, "Độ tin cậy %.2f dưới mức tối thiểu %.2f",
                    confidence, t.minimumConfidence()));
        }

        boolean eligible = blockers.isEmpty();
        // Cảnh báo (Level 1) khi: mức MEDIUM trở lên có tương quan, HOẶC hoạt động bùng nổ kéo dài
        // (>= 3 cửa sổ liên tiếp và sự kiện >= 2x ngưỡng). Burst chỉ cảnh báo, KHÔNG đủ để cách ly.
        boolean burst = f.consecutiveHighWindows() >= 3 && f.eventsNow() >= 2L * t.eventThreshold();
        int level;
        if (eligible) {
            level = 2;
        } else if ((severity.atLeast(Severity.MEDIUM) && correlated) || burst) {
            level = 1;
        } else {
            level = 0;
        }
        return new LUTDecision(severity, level, correlated, eligible,
                eligible && s.autoIsolationAllowed(), List.copyOf(blockers));
    }
}
