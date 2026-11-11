package com.horizon.ai.lut;

/**
 * Đặc trưng đã trích xuất cho một máy. Tất cả giá trị đều hữu hạn (NaN/Infinity đã được xử lý).
 * Nhóm: activity (4.1), clock (4.2), performance (4.3), composition và concurrency (4.4).
 */
public record LUTFeatures(
        // --- Activity ---
        int eventsNow,
        double baselineEvents,
        double eventRatio,
        double eventsAvg,
        double eventsMax,
        double eventsMin,
        double eventRate,
        double activityCv,
        double activityTrend,
        boolean activitySpike,
        int consecutiveHighWindows,
        int idleToActiveTransitions,
        double stability,
        // --- Clock ---
        double clockHz,
        double clockCv,
        boolean stableClock,
        // --- Performance ---
        double serverMspt,
        double msptAvg,
        double msptMax,
        double msptCv,
        double msptTrend,
        double msptActive,
        double msptIdle,
        double msptDelta,
        boolean deltaValid,
        double deltaConsistency,
        // --- Correlation ---
        double correlation,
        boolean correlationValid,
        int sampleCount,
        // --- Composition / concurrency / boundary ---
        LUTInput.Composition composition,
        int otherActive,
        boolean boundaryClear,
        boolean scanComplete) {
}
