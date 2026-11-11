package com.horizon.ai.performance;

/** Một mẫu hiệu suất. Bất biến, an toàn để chuyển sang luồng async (AI, báo cáo). */
public record PerfSample(
        long timeMillis,
        double tps,
        double mspt,
        double cpuLoad,
        long usedMemMb,
        long maxMemMb,
        int players,
        int loadedChunks,
        int entities,
        int redstoneEvents,
        int activeChunks) {
}
