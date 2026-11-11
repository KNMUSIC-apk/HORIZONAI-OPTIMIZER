package com.horizon.ai.lut;

import java.util.Objects;

/**
 * Điều phối pipeline LUT: Feature Extraction → LUT Score → Confidence → Decision.
 * Thuần Java, không có I/O, không mạng, không AI. Hoạt động khi server offline.
 *
 * Bất biến: một LUTEngine giữ một LUTProfile không đổi. Reload config = tạo engine mới.
 */
public final class LUTEngine {

    private final LUTProfile profile;

    public LUTEngine(LUTProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public LUTProfile profile() {
        return profile;
    }

    public LUTResult analyze(LUTInput input) {
        LUTFeatures f = LUTFeatureExtractor.extract(input, profile.thresholds());
        LUTScore score = LUTScoreEngine.score(f, profile);
        double confidence = LUTConfidenceEngine.confidence(f, profile.thresholds());
        LUTDecision decision = LUTDecisionEngine.decide(score, confidence, f, profile);
        return new LUTResult(input.machineId(), input.world(), f, score, confidence, decision);
    }
}
