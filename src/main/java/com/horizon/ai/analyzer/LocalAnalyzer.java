package com.horizon.ai.analyzer;

import com.horizon.ai.optimizer.ActionValidator.ProposedAction;
import com.horizon.ai.performance.PerfSample;
import com.horizon.ai.redstone.Machine;
import com.horizon.ai.redstone.MachineTracker;
import com.horizon.ai.utils.Settings;
import com.horizon.ai.utils.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bộ phân tích cục bộ, KHÔNG dùng AI. Luôn khả dụng.
 * Dùng khi AI tắt/mất kết nối, và để tạo báo cáo nhanh cho lệnh /horizonai report.
 */
public final class LocalAnalyzer {

    private final Settings settings;

    public LocalAnalyzer(Settings settings) {
        this.settings = settings;
    }

    public HealthReport build(PerfSample latest, MachineTracker tracker, boolean spiking) {
        List<String> causes = new ArrayList<>();
        List<String> recs = new ArrayList<>();
        List<ProposedAction> actions = new ArrayList<>();

        Severity serverSeverity = Severity.NORMAL;
        double mspt = latest == null ? 0 : latest.mspt();
        if (mspt >= settings.msptCritical) {
            serverSeverity = Severity.CRITICAL;
        } else if (mspt >= settings.msptWarning) {
            serverSeverity = Severity.HIGH;
        } else if (spiking) {
            serverSeverity = Severity.MEDIUM;
        }

        Severity machineMax = Severity.NORMAL;
        for (Machine m : tracker.all()) {
            if (m.severity.atLeast(machineMax)) {
                machineMax = m.severity;
            }
            if (m.correlated && m.severity.atLeast(Severity.MEDIUM)) {
                String ev = m.evidence.isEmpty() ? "" : " — " + m.evidence.get(0);
                causes.add("Máy " + m.id + " (" + m.severity + ", độ tin cậy "
                        + String.format(Locale.ROOT, "%.0f%%", m.confidence * 100) + ")" + ev);
            }
            if (m.severity.atLeast(Severity.HIGH) && m.correlated) {
                actions.add(new ProposedAction("NOTIFY_ADMIN", m.id,
                        "Máy " + m.id + " có tương quan với lag, cần Admin kiểm tra"));
                if (m.eligible) {
                    actions.add(new ProposedAction("ISOLATE_MACHINE", m.id,
                            "Đủ điều kiện cách ly: chờ Admin duyệt"));
                }
            }
        }

        Severity overall = serverSeverity.atLeast(machineMax) ? serverSeverity : machineMax;

        if (causes.isEmpty()) {
            causes.add("Chưa có máy Redstone nào được xác minh là nguyên nhân gây lag");
        }
        if (mspt >= settings.msptWarning && causes.size() == 1 && causes.get(0).startsWith("Chưa")) {
            recs.add("MSPT đang cao nhưng không có máy Redstone nào tương quan: kiểm tra entity, chunk loading "
                    + "hoặc plugin khác bằng profiler (ví dụ spark) để xác minh nguyên nhân.");
        }
        if (latest != null && latest.maxMemMb() > 0 && (double) latest.usedMemMb() / latest.maxMemMb() > 0.85) {
            recs.add("RAM đang sử dụng trên 85%: kiểm tra số entity và view/simulation distance.");
        }
        if (latest != null && latest.cpuLoad() > 0.9) {
            recs.add("CPU tiến trình trên 90%: giảm số máy Redstone chạy liên tục hoặc tối ưu plugin.");
        }
        if (latest != null && latest.entities() > 0 && latest.players() > 0
                && latest.entities() / latest.players() > 300) {
            recs.add("Tỉ lệ entity trên người chơi cao: xem xét giới hạn mob farm trong entity.mob-thresholds.");
        }
        if (recs.isEmpty()) {
            recs.add("Không phát hiện vấn đề rõ ràng. Tiếp tục theo dõi.");
        }

        String summary = "Mức tổng thể " + overall + ". MSPT hiện tại "
                + String.format(Locale.ROOT, "%.1f", mspt) + " ms (ngưỡng cảnh báo "
                + String.format(Locale.ROOT, "%.0f", settings.msptWarning) + " ms). "
                + "Số máy Redstone đang theo dõi: " + tracker.size() + ".";

        Map<String, Boolean> none = Map.of();
        return new HealthReport(overall, "LOCAL", summary, List.copyOf(causes), List.copyOf(recs),
                List.copyOf(actions), none, System.currentTimeMillis());
    }
}
