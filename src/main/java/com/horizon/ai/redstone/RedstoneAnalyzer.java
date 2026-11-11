package com.horizon.ai.redstone;

import com.horizon.ai.utils.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ phân tích kết hợp: Rule-based + Statistical Anomaly + Performance Correlation.
 *
 * Nguyên tắc cốt lõi:
 *  - Hoạt động nhiều KHÔNG đủ để kết luận gây lag.
 *  - Một máy chỉ được coi là "nguyên nhân" khi có tương quan thống kê (Pearson) giữa
 *    mức hoạt động của máy và MSPT, đủ số mẫu, và MSPT khi máy hoạt động cao hơn baseline.
 *  - Thuần Java, không gọi Bukkit, không gọi AI.
 */
public final class RedstoneAnalyzer {

    public record Params(int eventThreshold, double clockMinHz, double clockMaxCv,
                         double minMsptDelta, double msptCritical, int minSamples, double minCorrelation) {
    }

    /**
     * Snapshot thống kê của một máy trong cửa sổ phân tích hiện tại.
     * pairedActivity[i] và pairedMspt[i] là cặp (events, MSPT) của cùng một cửa sổ thời gian.
     */
    public record MachineStats(
            int eventsInWindow,
            double baselineEventsPerWindow,
            double clockFrequencyHz,
            double clockIntervalCv,
            double[] pairedActivity,
            double[] pairedMspt,
            double currentMspt,
            int otherActiveMachines,
            boolean boundaryClear,
            boolean containable,
            int pistonAndHopperCount) {
    }

    public record Verdict(
            Severity severity,
            boolean anomalous,
            boolean clockLike,
            boolean correlated,
            double correlation,
            double msptDelta,
            double confidence,
            boolean isolationEligible,
            List<String> evidence) {
    }

    private final Params p;

    public RedstoneAnalyzer(Params params) {
        this.p = params;
    }

    public Verdict analyze(MachineStats s) {
        List<String> ev = new ArrayList<>();

        // 1. Rule-based + statistical anomaly
        boolean ruleHighEvents = s.eventsInWindow() >= p.eventThreshold();
        boolean statSpike = s.baselineEventsPerWindow() > 0
                && s.eventsInWindow() >= 3.0 * s.baselineEventsPerWindow()
                && s.eventsInWindow() >= Math.max(10, p.eventThreshold() / 4);
        boolean anomalous = ruleHighEvents || statSpike;
        if (ruleHighEvents) {
            ev.add("Số sự kiện Redstone trong cửa sổ = " + s.eventsInWindow() + " (ngưỡng " + p.eventThreshold() + ")");
        }
        if (statSpike) {
            ev.add("Tăng đột biến so với baseline: " + s.eventsInWindow() + " / " + fmt(s.baselineEventsPerWindow()));
        }

        // 2. Clock detection: tần số cao và chu kỳ đều
        boolean clockLike = s.clockFrequencyHz() >= p.clockMinHz()
                && s.clockIntervalCv() <= p.clockMaxCv();
        if (clockLike) {
            ev.add("Mạch có chu kỳ đều: " + fmt(s.clockFrequencyHz()) + " Hz, CV=" + fmt(s.clockIntervalCv()));
        }

        // 3. Performance correlation
        int n = Math.min(s.pairedActivity().length, s.pairedMspt().length);
        double r = n >= p.minSamples() ? pearson(s.pairedActivity(), s.pairedMspt(), n) : Double.NaN;
        double[] means = meansByActivity(s.pairedActivity(), s.pairedMspt(), n);
        double delta = means == null ? 0.0 : means[0] - means[1];
        boolean correlated = !Double.isNaN(r)
                && r >= p.minCorrelation()
                && delta >= p.minMsptDelta();
        if (n < p.minSamples()) {
            ev.add("Chưa đủ mẫu tương quan (" + n + "/" + p.minSamples() + "), chưa kết luận nguyên nhân");
        } else {
            ev.add("Tương quan activity–MSPT r=" + fmt(r) + ", chênh MSPT khi hoạt động=" + fmt(delta) + " ms");
        }

        // 4. Mức độ nghiêm trọng
        Severity severity;
        if (!anomalous && !clockLike) {
            severity = Severity.NORMAL;
        } else if (!correlated) {
            severity = Severity.LOW;
            ev.add("Hoạt động bất thường nhưng CHƯA có bằng chứng tương quan với lag");
        } else if (s.currentMspt() >= p.msptCritical() && delta >= 3 * p.minMsptDelta()) {
            severity = Severity.CRITICAL;
        } else if (delta >= 2 * p.minMsptDelta() || (clockLike && s.eventsInWindow() >= 3 * p.eventThreshold())) {
            severity = Severity.HIGH;
        } else {
            severity = Severity.MEDIUM;
        }

        // 5. Độ tin cậy: giảm mạnh nếu còn máy khác cùng hoạt động (có thể là nguyên nhân chung)
        double confidence = 0.0;
        if (correlated) {
            confidence = Math.min(1.0, 0.5 + Math.max(0.0, r - p.minCorrelation()) + (s.otherActiveMachines() == 0 ? 0.2 : 0.0));
            if (s.otherActiveMachines() > 0) {
                confidence *= 0.5;
                ev.add("Có " + s.otherActiveMachines() + " máy khác cùng hoạt động: độ tin cậy bị giảm");
            }
        }

        // 6. Điều kiện cách ly Level 2: tương quan + mức HIGH trở lên + ranh giới rõ + có thành phần cách ly được
        boolean eligible = correlated
                && severity.atLeast(Severity.HIGH)
                && s.boundaryClear()
                && s.containable()
                && s.otherActiveMachines() == 0
                && confidence >= 0.7;
        if (!s.boundaryClear()) {
            ev.add("Ranh giới máy không rõ ràng: chỉ cảnh báo, yêu cầu Admin kiểm tra");
        }
        if (!s.containable() && correlated) {
            ev.add("Máy không có piston/hopper/dispenser: không thể cách ly an toàn tự động");
        }

        return new Verdict(severity, anomalous, clockLike, correlated, Double.isNaN(r) ? 0.0 : r,
                delta, confidence, eligible, List.copyOf(ev));
    }

    /** Trả về {meanMsptWhenActive, meanMsptIdle}. Active = activity >= trung vị. Null nếu không đủ dữ liệu. */
    static double[] meansByActivity(double[] act, double[] mspt, int n) {
        if (n < 2) {
            return null;
        }
        double[] sorted = new double[n];
        System.arraycopy(act, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        double median = sorted[n / 2];
        double sumA = 0, sumI = 0;
        int cA = 0, cI = 0;
        for (int i = 0; i < n; i++) {
            if (act[i] > median) {
                sumA += mspt[i];
                cA++;
            } else {
                sumI += mspt[i];
                cI++;
            }
        }
        if (cA == 0 || cI == 0) {
            return null;
        }
        return new double[]{sumA / cA, sumI / cI};
    }

    /** Hệ số Pearson trên n cặp đầu tiên. NaN nếu phương sai bằng 0. */
    public static double pearson(double[] x, double[] y, int n) {
        if (n < 2) {
            return Double.NaN;
        }
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) {
            return Double.NaN;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
