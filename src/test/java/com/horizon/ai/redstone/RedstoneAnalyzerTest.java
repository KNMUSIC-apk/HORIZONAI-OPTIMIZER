package com.horizon.ai.redstone;

import com.horizon.ai.utils.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneAnalyzerTest {

    private static final RedstoneAnalyzer.Params PARAMS =
            new RedstoneAnalyzer.Params(400, 2.0, 0.25, 5.0, 60.0, 12, 0.5);
    private final RedstoneAnalyzer analyzer = new RedstoneAnalyzer(PARAMS);

    /** Máy bình thường: hoạt động ít, không đột biến, MSPT không liên quan. */
    @Test
    void normalMachineIsNotFlagged() {
        double[] act = {20, 25, 22, 18, 21, 24, 19, 23, 20, 22, 21, 19};
        double[] mspt = {30, 31, 29, 30, 32, 30, 31, 29, 30, 31, 30, 29};
        var s = new RedstoneAnalyzer.MachineStats(22, 21, 0.5, 0.9, act, mspt, 30, 0, true, true, 2);
        var v = analyzer.analyze(s);
        assertEquals(Severity.NORMAL, v.severity());
        assertFalse(v.isolationEligible());
    }

    /** Máy hoạt động nhiều nhưng MSPT không tăng: không được kết luận gây lag. */
    @Test
    void busyMachineWithoutCorrelationIsNotBlamed() {
        double[] act = {500, 520, 510, 530, 505, 515, 525, 500, 520, 510, 530, 505};
        double[] mspt = {30, 31, 29, 30, 32, 30, 31, 29, 30, 31, 30, 29};
        var s = new RedstoneAnalyzer.MachineStats(530, 500, 0.5, 0.9, act, mspt, 30, 0, true, true, 4);
        var v = analyzer.analyze(s);
        assertTrue(v.anomalous() || v.severity() == Severity.LOW);
        assertFalse(v.correlated());
        assertFalse(v.isolationEligible());
        assertFalse(v.severity().atLeast(Severity.HIGH));
    }

    /** Clock gây lag rõ ràng: tương quan cao, MSPT tăng mạnh, ranh giới rõ, có piston. */
    @Test
    void correlatedClockOverloadIsEligible() {
        double[] act = {100, 120, 900, 950, 110, 880, 920, 105, 930, 115, 940, 890};
        double[] mspt = {30, 32, 75, 80, 31, 78, 82, 30, 79, 33, 81, 77};
        var s = new RedstoneAnalyzer.MachineStats(940, 120, 4.0, 0.05, act, mspt, 80, 0, true, true, 3);
        var v = analyzer.analyze(s);
        assertTrue(v.clockLike());
        assertTrue(v.correlated(), "r=" + v.correlation());
        assertTrue(v.severity().atLeast(Severity.HIGH));
        assertTrue(v.isolationEligible());
    }

    /** Có máy khác cùng hoạt động: không được cách ly tự động. */
    @Test
    void otherActiveMachinesBlockIsolation() {
        double[] act = {100, 120, 900, 950, 110, 880, 920, 105, 930, 115, 940, 890};
        double[] mspt = {30, 32, 75, 80, 31, 78, 82, 30, 79, 33, 81, 77};
        var s = new RedstoneAnalyzer.MachineStats(940, 120, 4.0, 0.05, act, mspt, 80, 2, true, true, 3);
        var v = analyzer.analyze(s);
        assertFalse(v.isolationEligible());
    }

    /** Ranh giới không rõ: chỉ cảnh báo, không cách ly. */
    @Test
    void ambiguousBoundaryBlocksIsolation() {
        double[] act = {100, 120, 900, 950, 110, 880, 920, 105, 930, 115, 940, 890};
        double[] mspt = {30, 32, 75, 80, 31, 78, 82, 30, 79, 33, 81, 77};
        var s = new RedstoneAnalyzer.MachineStats(940, 120, 4.0, 0.05, act, mspt, 80, 0, false, true, 3);
        var v = analyzer.analyze(s);
        assertFalse(v.isolationEligible());
    }

    @Test
    void pearsonOfPerfectlyCorrelatedSeriesIsOne() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        assertEquals(1.0, RedstoneAnalyzer.pearson(x, y, 5), 1e-9);
    }
}
