package com.horizon.ai.lut;

/**
 * Snapshot đầu vào của LUT cho một máy trong một cửa sổ phân tích.
 *
 * activity[i] và mspt[i] là cặp (sự kiện, MSPT) của cùng một cửa sổ; phần tử cuối là cửa sổ hiện tại.
 * Mảng được sao chép bởi người gọi (Machine.seriesActivity()/seriesMspt()), LUT không giữ tham chiếu
 * tới trạng thái Bukkit hay tới MachineTracker.
 */
public record LUTInput(
        String machineId,
        String world,
        double[] activity,
        double[] mspt,
        double baselineEvents,
        int eventsNow,
        int windowSeconds,
        double clockHz,
        double clockCv,
        double serverMspt,
        int otherActive,
        boolean boundaryClear,
        boolean scanComplete,
        Composition composition) {

    /** Số lượng linh kiện đã quét được. Chỉ có ý nghĩa khi scanComplete = true. */
    public record Composition(
            int pistons,
            int hoppers,
            int dispensers,
            int observers,
            int tnt,
            int redstoneParts) {

        /** Linh kiện có thể cách ly an toàn: piston, hopper, dispenser. */
        public int containable() {
            return pistons + hoppers + dispensers;
        }

        public static Composition empty() {
            return new Composition(0, 0, 0, 0, 0, 0);
        }
    }
}
