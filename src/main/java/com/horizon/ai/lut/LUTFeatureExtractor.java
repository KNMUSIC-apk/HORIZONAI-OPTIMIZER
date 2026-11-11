package com.horizon.ai.lut;

/**
 * Trích xuất đặc trưng từ snapshot. Thuần, không cấp phát đối tượng lớn, số phép tính tỉ lệ với
 * historySize (tối đa 120) nên chi phí bị chặn trên cho mỗi máy.
 *
 * Quy ước: "hoạt động cao" = sự kiện >= eventThreshold; "đang hoạt động" = sự kiện >= max(10, threshold/4);
 * "nhàn rỗi" = sự kiện < max(1, threshold/20). ΔMSPT = trung bình MSPT khi activity > trung vị
 * trừ trung bình MSPT khi activity <= trung vị.
 */
public final class LUTFeatureExtractor {

    private static final double MISSING_CLOCK_CV = 1e9;

    private LUTFeatureExtractor() {
    }

    public static LUTFeatures extract(LUTInput in, LUTThresholds t) {
        double[] act = in.activity() == null ? new double[0] : in.activity();
        double[] msp = in.mspt() == null ? new double[0] : in.mspt();
        int n = Math.min(act.length, msp.length);
        int from = Math.max(0, n - t.historySize());

        // ---- Activity ----
        double events = Math.max(0.0, LUTStats.finiteOr(in.eventsNow(), 0.0));
        double baseline = Math.max(0.0, LUTStats.finiteOr(in.baselineEvents(), 0.0));
        double ratio = baseline > 0 ? LUTStats.clamp(events / baseline, 0.0, 1000.0) : 0.0;
        double avg = LUTStats.mean(act, from, n);
        double max = LUTStats.max(act, from, n);
        double min = minFinite(act, from, n);
        double rate = events / Math.max(1, in.windowSeconds());
        double cv = LUTStats.coefficientOfVariation(act, from, n);
        double trend = avg > 0 ? LUTStats.slope(act, from, n) / avg : 0.0;
        int threshold = t.eventThreshold();
        int activeFloor = Math.max(10, threshold / 4);
        int idleFloor = Math.max(1, threshold / 20);
        boolean spike = baseline > 0 && events >= 3.0 * baseline && events >= activeFloor;

        int consecutive = 0;
        for (int i = n - 1; i >= from && act[i] >= threshold; i--) {
            consecutive++;
        }
        int idleToActive = 0;
        for (int i = from + 1; i < n; i++) {
            if (act[i - 1] < idleFloor && act[i] >= activeFloor) {
                idleToActive++;
            }
        }
        double stability = LUTStats.clamp(1.0 / (1.0 + cv), 0.0, 1.0);

        // ---- Clock ----
        double clockHz = Math.max(0.0, LUTStats.finiteOr(in.clockHz(), 0.0));
        double clockCv = LUTStats.finiteOr(in.clockCv(), MISSING_CLOCK_CV);
        boolean stableClock = clockHz >= t.clockMinFrequencyHz()
                && clockCv <= t.clockMaxIntervalCv()
                && events >= activeFloor;

        // ---- Performance ----
        double serverMspt = Math.max(0.0, LUTStats.finiteOr(in.serverMspt(), 0.0));
        double msptAvg = LUTStats.mean(msp, from, n);
        double msptMax = LUTStats.max(msp, from, n);
        double msptCv = LUTStats.coefficientOfVariation(msp, from, n);
        double msptTrend = LUTStats.slope(msp, from, n);

        double[] split = splitDelta(act, msp, from, n);
        boolean deltaValid = split != null;
        double msptActive = deltaValid ? split[1] : msptAvg;
        double msptIdle = deltaValid ? split[2] : msptAvg;
        double delta = deltaValid ? split[0] : 0.0;

        int mid = from + (n - from) / 2;
        double[] firstHalf = splitDelta(act, msp, from, mid);
        double[] secondHalf = splitDelta(act, msp, mid, n);
        int consistentHalves = (firstHalf != null && firstHalf[0] >= t.msptDeltaMinimum() ? 1 : 0)
                + (secondHalf != null && secondHalf[0] >= t.msptDeltaMinimum() ? 1 : 0);
        double deltaConsistency = consistentHalves / 2.0;

        // ---- Correlation ----
        double r = LUTStats.pearson(act, msp, from, n);
        boolean corrValid = !Double.isNaN(r);

        return new LUTFeatures(
                (int) Math.min(Integer.MAX_VALUE, events), baseline, ratio,
                avg, max, min, rate, cv, trend, spike, consecutive, idleToActive, stability,
                clockHz, clockCv, stableClock,
                serverMspt, msptAvg, msptMax, msptCv, msptTrend, msptActive, msptIdle, delta,
                deltaValid, deltaConsistency,
                corrValid ? r : 0.0, corrValid, n - from,
                in.composition() == null ? LUTInput.Composition.empty() : in.composition(),
                Math.max(0, in.otherActive()), in.boundaryClear(), in.scanComplete());
    }

    /**
     * Chênh MSPT giữa cửa sổ hoạt động (activity > trung vị) và nhàn rỗi trên [from, to).
     * Trả về {delta, meanActive, meanIdle}, hoặc null nếu không đủ dữ liệu cho cả hai nhóm.
     */
    static double[] splitDelta(double[] act, double[] msp, int from, int to) {
        if (to - from < 2) {
            return null;
        }
        double median = LUTStats.median(act, from, to);
        double sumA = 0;
        double sumI = 0;
        int cA = 0;
        int cI = 0;
        for (int i = from; i < to; i++) {
            if (!Double.isFinite(act[i]) || !Double.isFinite(msp[i])) {
                continue;
            }
            if (act[i] > median) {
                sumA += msp[i];
                cA++;
            } else {
                sumI += msp[i];
                cI++;
            }
        }
        if (cA == 0 || cI == 0) {
            return null;
        }
        double meanA = sumA / cA;
        double meanI = sumI / cI;
        return new double[]{meanA - meanI, meanA, meanI};
    }

    private static double minFinite(double[] a, int from, int to) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(a[i]) && a[i] < best) {
                best = a[i];
            }
        }
        return Double.isInfinite(best) ? 0.0 : best;
    }
}
