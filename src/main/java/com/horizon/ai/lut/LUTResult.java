package com.horizon.ai.lut;

import com.horizon.ai.utils.Severity;

/** Toàn bộ kết quả một lần phân tích LUT cho một máy. Bất biến. */
public record LUTResult(
        String machineId,
        String world,
        LUTFeatures features,
        LUTScore score,
        double confidence,
        LUTDecision decision) {

    public Severity severity() {
        return decision.severity();
    }
}
