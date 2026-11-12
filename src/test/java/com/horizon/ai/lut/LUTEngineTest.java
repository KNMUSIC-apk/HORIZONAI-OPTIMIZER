package com.horizon.ai.lut;

import com.horizon.ai.utils.Severity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 18 kịch bản theo đặc tả mục 15, cộng hai kiểm tra bổ sung về cấu hình và ràng buộc điểm cao.
 * Toàn bộ là logic thuần: không cần Paper API, không cần mạng, không cần API key.
 */
class LUTEngineTest {

    private static final LUTEngine DEFAULT = new LUTEngine(LUTProfile.defaults());

    // ----- helpers -----

    private static double[] fill(int n, double v) {
        double[] a = new double[n];
        Arrays.fill(a, v);
        return a;
    }

    /** Hoạt động lặp chu kỳ 100/300/500/700. */
    private static double[] cyclic(int n) {
        double[] v = {100, 300, 500, 700};
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = v[i % 4];
        }
        return a;
    }

    /** MSPT = base + slope x (activity - 100): slope > 0 là tương quan dương, slope < 0 là âm. */
    private static double[] msptFollowing(double[] act, double base, double slope) {
        double[] m = new double[act.length];
        for (int i = 0; i < act.length; i++) {
            m[i] = base + slope * (act[i] - 100.0);
        }
        return m;
    }

    private static LUTInput.Composition comp(int pistons, int hoppers, int observers, int redstone) {
        return new LUTInput.Composition(pistons, hoppers, 0, observers, 0, redstone);
    }

    private static LUTInput input(double[] act, double[] msp, int events, double baseline,
                                  double clockHz, double clockCv, double serverMspt, int others,
                                  boolean boundary, boolean scan, LUTInput.Composition c) {
        return new LUTInput("HZ-TEST", "world", act, msp, baseline, events, 10,
                clockHz, clockCv, serverMspt, others, boundary, scan, c);
    }

    /** Máy có tương quan mạnh, đủ cấu tạo, ranh giới rõ, quét đầy đủ, không có máy khác. */
    private static LUTInput strongMachine(double serverMspt, int others, boolean boundary, boolean scan) {
        double[] act = cyclic(24);
        return input(act, msptFollowing(act, 30, 0.05), 700, 200, 0, 0, serverMspt, others,
                boundary, scan, comp(4, 2, 0, 20));
    }

    private static boolean blockersContain(LUTResult r, String fragment) {
        return r.decision().blockers().stream().anyMatch(b -> b.contains(fragment));
    }

    // ----- 1-18 -----

    @Test
    void normalMachineStaysNormal() {
        double[] act = fill(24, 20);
        LUTResult r = DEFAULT.analyze(input(act, fill(24, 40), 20, 20, 0, 0, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(Severity.NORMAL, r.severity());
        assertEquals(0, r.decision().level());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void highActivityLowMsptImpactIsNotSevere() {
        double[] act = new double[24];
        for (int i = 0; i < act.length; i++) {
            act[i] = (i % 2 == 0) ? 220 : 850;
        }
        LUTResult r = DEFAULT.analyze(input(act, fill(24, 40), 850, 220, 0, 0, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(Severity.LOW, r.severity());
        assertFalse(r.decision().correlated());
        assertEquals(0, r.decision().level());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void highFrequencyClockIsNotProofOfLag() {
        LUTResult r = DEFAULT.analyze(input(fill(24, 300), fill(24, 40), 300, 300, 4.0, 0.05, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(12, r.score().clock());
        assertEquals(Severity.NORMAL, r.severity());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void activitySpikeIsDetected() {
        double[] act = fill(24, 25);
        act[23] = 900;
        LUTResult r = DEFAULT.analyze(input(act, fill(24, 40), 900, 25, 0, 0, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertTrue(r.features().activitySpike());
    }

    @Test
    void strongPositiveCorrelationIsCorrelatedAndEligibleForAutoIsolation() {
        LUTResult r = DEFAULT.analyze(strongMachine(45, 0, true, true));
        assertTrue(r.features().correlation() > 0.99);
        assertTrue(r.decision().correlated());
        assertEquals(2, r.decision().level());
        assertTrue(r.decision().autoIsolationAllowed(), "Mặc định: tự cách ly khi đủ bằng chứng");
        assertEquals("PENDING_AUTO_ISOLATION", LUTReportBuilder.decisionLabel(r));
    }

    @Test
    void sustainedBurstWarnsWithoutCorrelationButNeverIsolates() {
        LUTResult r = DEFAULT.analyze(input(fill(24, 900), fill(24, 40), 900, 100, 0, 0, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(1, r.decision().level());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void negativeCorrelationGivesNoCredit() {
        double[] act = cyclic(24);
        LUTResult r = DEFAULT.analyze(input(act, msptFollowing(act, 80, -0.05), 700, 200, 0, 0, 45, 0,
                true, true, comp(4, 2, 0, 20)));
        assertTrue(r.features().correlation() < 0);
        assertEquals(0, r.score().correlation());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void insufficientSamplesBlocksIsolation() {
        double[] act = {100, 700, 100, 700, 100};
        LUTResult r = DEFAULT.analyze(input(act, msptFollowing(act, 30, 0.05), 700, 200, 0, 0, 70, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(5, r.features().sampleCount());
        assertFalse(r.decision().isolationEligible());
        assertTrue(blockersContain(r, "Chưa đủ mẫu"));
    }

    @Test
    void constantActivitySeriesIsHandled() {
        double[] msp = new double[24];
        for (int i = 0; i < msp.length; i++) {
            msp[i] = 40 + (i % 3) * 5;
        }
        LUTResult r = DEFAULT.analyze(input(fill(24, 300), msp, 300, 300, 0, 0, 40, 0,
                true, true, comp(4, 2, 0, 20)));
        assertFalse(r.features().deltaValid());
        assertFalse(r.features().correlationValid());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void constantMsptSeriesIsHandled() {
        double[] act = cyclic(24);
        LUTResult r = DEFAULT.analyze(input(act, fill(24, 50), 700, 200, 0, 0, 50, 0,
                true, true, comp(4, 2, 0, 20)));
        assertFalse(r.features().correlationValid());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
    }

    @Test
    void multipleActiveMachinesBlockIsolation() {
        LUTResult r = DEFAULT.analyze(strongMachine(70, 2, true, true));
        assertTrue(r.score().total() >= 65, "Điểm phải đạt HIGH để kiểm tra đúng cổng này");
        assertFalse(r.decision().isolationEligible());
        assertTrue(blockersContain(r, "máy khác"));
    }

    @Test
    void ambiguousBoundaryBlocksIsolation() {
        LUTResult r = DEFAULT.analyze(strongMachine(70, 0, false, true));
        assertFalse(r.decision().isolationEligible());
        assertTrue(blockersContain(r, "Ranh giới"));
        assertEquals(0.0, r.confidence(), 1e-9);
    }

    @Test
    void partialComponentScanBlocksIsolation() {
        LUTResult r = DEFAULT.analyze(strongMachine(70, 0, true, false));
        assertFalse(r.decision().isolationEligible());
        assertTrue(blockersContain(r, "Quét"));
        assertEquals(0.0, r.confidence(), 1e-9);
    }

    @Test
    void veryHighMsptAddsCriticalPoints() {
        LUTResult r = DEFAULT.analyze(input(fill(24, 20), fill(24, 40), 20, 20, 0, 0, 120, 0,
                true, true, comp(4, 2, 0, 20)));
        assertEquals(12, r.score().performance());
        assertTrue(r.score().total() <= 100);
    }

    @Test
    void invalidNumericValuesDoNotBreakEngine() {
        double[] act = new double[24];
        double[] msp = new double[24];
        for (int i = 0; i < 24; i++) {
            act[i] = (i % 5 == 0) ? Double.NaN : (i % 7 == 0 ? Double.POSITIVE_INFINITY : (i % 3 == 0 ? -5 : 100 + i * 10));
            msp[i] = (i % 4 == 0) ? Double.NaN : 40 + i;
        }
        LUTResult r = DEFAULT.analyze(input(act, msp, -3, Double.NaN, Double.NaN, Double.NaN,
                Double.POSITIVE_INFINITY, -4, true, true, comp(4, 2, 0, 20)));
        assertTrue(r.score().total() >= 0 && r.score().total() <= 100);
        assertTrue(r.confidence() >= 0.0 && r.confidence() <= 1.0);
        LUTFeatures f = r.features();
        assertTrue(Double.isFinite(f.eventRatio()));
        assertTrue(Double.isFinite(f.msptDelta()));
        assertTrue(Double.isFinite(f.correlation()));
        assertTrue(Double.isFinite(f.activityCv()));
        assertTrue(Double.isFinite(f.clockHz()));
        assertTrue(Double.isFinite(f.serverMspt()));
        assertEquals(0, f.otherActive());
    }

    @Test
    void riskScoreIsClampedAndGroupCapsHold() {
        LUTWeights huge = new LUTWeights(999, 999, 999, 999, 999, 999, 999, 999, 999);
        LUTProfile p = new LUTProfile(huge, LUTThresholds.defaults(), LUTSafety.defaults(), 30, 5.0);
        assertEquals(LUTWeights.MAX_POINTS, huge.eventRateHighPoints());
        LUTResult r = new LUTEngine(p).analyze(strongMachine(120, 0, true, true));
        assertTrue(r.score().total() >= 0 && r.score().total() <= 100);
        assertTrue(r.score().activity() <= LUTScoreEngine.ACTIVITY_CAP);
        assertTrue(r.score().performance() <= LUTScoreEngine.PERFORMANCE_CAP);
        assertTrue(r.score().correlation() <= LUTScoreEngine.CORRELATION_CAP);
        assertTrue(r.score().clock() <= LUTScoreEngine.CLOCK_CAP);
    }

    @Test
    void confidenceReflectsSampleCount() {
        LUTResult long24 = DEFAULT.analyze(strongMachine(45, 0, true, true));
        double[] act12 = cyclic(12);
        LUTResult short12 = DEFAULT.analyze(input(act12, msptFollowing(act12, 30, 0.05), 700, 200, 0, 0, 45, 0,
                true, true, comp(4, 2, 0, 20)));
        assertTrue(long24.confidence() >= 0.0 && long24.confidence() <= 1.0);
        assertTrue(short12.confidence() >= 0.0 && short12.confidence() <= 1.0);
        assertTrue(long24.confidence() > short12.confidence(),
                "Nhiều mẫu hơn phải cho độ tin cậy cao hơn");
        LUTResult unclear = DEFAULT.analyze(strongMachine(70, 0, false, true));
        assertTrue(unclear.score().total() >= 65, "Điểm vẫn cao");
        assertEquals(0.0, unclear.confidence(), 1e-9,
                "Điểm cao nhưng ranh giới mơ hồ: độ tin cậy phải bằng 0, tách khỏi điểm rủi ro");
    }

    @Test
    void fullEvidenceReachesPendingIsolationOnly() {
        LUTResult r = DEFAULT.analyze(strongMachine(70, 0, true, true));
        assertTrue(r.score().total() >= 65, "Điểm: " + r.score().total());
        assertTrue(r.confidence() >= 0.70, "Độ tin cậy: " + r.confidence());
        assertTrue(r.decision().isolationEligible());
        assertEquals(2, r.decision().level());
        assertTrue(r.decision().autoIsolationAllowed());
        assertTrue(r.decision().blockers().isEmpty());
    }

    @Test
    void adminApprovalIsEnforced() {
        assertFalse(new LUTSafety(true, true, true, true, true).autoIsolationAllowed(),
                "Admin bắt buộc duyệt: không được tự cách ly dù autoIsolate = true");
        assertTrue(new LUTSafety(true, true, true, false, true).autoIsolationAllowed());
        assertFalse(new LUTSafety(true, true, true, false, false).autoIsolationAllowed());

        LUTProfile autoOn = new LUTProfile(LUTWeights.defaults(), LUTThresholds.defaults(),
                new LUTSafety(true, true, true, false, true), 30, 5.0);
        LUTResult r = new LUTEngine(autoOn).analyze(strongMachine(70, 0, true, true));
        assertTrue(r.decision().isolationEligible());
        assertTrue(r.decision().autoIsolationAllowed());
    }

    @Test
    void highScoreWithoutCorrelationCannotIsolate() {
        // Điểm vượt HIGH nhờ trọng số lớn cho MSPT server, nhưng không có tương quan.
        LUTWeights heavy = new LUTWeights(8, 14, 20, 8, 14, 20, 12, 30, 10);
        LUTProfile p = new LUTProfile(heavy, LUTThresholds.defaults(), LUTSafety.defaults(), 30, 5.0);
        double[] act = fill(24, 900);
        LUTResult r = new LUTEngine(p).analyze(input(act, fill(24, 40), 900, 100, 4.0, 0.05, 120, 0,
                true, true, comp(4, 2, 0, 20)));
        assertTrue(r.score().total() >= 65, "Điểm: " + r.score().total());
        assertFalse(r.decision().correlated());
        assertFalse(r.decision().isolationEligible());
        assertNotEquals(2, r.decision().level());
        assertTrue(blockersContain(r, "Chưa có tương quan"));
    }

    @Test
    void invalidThresholdOrderIsRepaired() {
        LUTThresholds t = new LUTThresholds(Double.NaN, 0.5, -2.0, 7.0, Double.NaN, -1,
                90, 10, 5, 1000, 9999, 3.0, Double.NaN, -1, 0);
        assertEquals(1.0, t.eventRateLow(), 1e-9);
        assertTrue(t.eventRateMedium() >= t.eventRateLow());
        assertTrue(t.eventRateHigh() >= t.eventRateMedium());
        assertTrue(t.correlationMinimum() <= 0.99);
        assertTrue(t.warningScore() < t.highScore());
        assertTrue(t.highScore() < t.criticalScore());
        assertTrue(t.criticalScore() <= 100);
        assertTrue(t.minSamples() <= 120);
        assertTrue(t.historySize() <= 120);
        assertTrue(t.eventThreshold() >= 10);
    }
}
