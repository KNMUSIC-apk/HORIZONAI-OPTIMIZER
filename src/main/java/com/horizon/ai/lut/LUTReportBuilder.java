package com.horizon.ai.lut;

import com.horizon.ai.utils.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tạo báo cáo tiếng Việt hoàn toàn xác định từ {@link LUTResult}.
 *
 * Mọi dòng bằng chứng và khuyến nghị đều suy ra trực tiếp từ đặc trưng đo được.
 * Không có dòng nào được bịa: nếu một đặc trưng không đủ dữ liệu, báo cáo nói rõ điều đó.
 */
public final class LUTReportBuilder {

    private LUTReportBuilder() {
    }

    /** Snapshot tổng quan server để hiển thị đầu báo cáo. */
    public record ServerView(double mspt, double msptWarning, double msptCritical,
                             int tracked, int warned, int isolated) {
    }

    /** Chuỗi quyết định hiển thị cho người xem. Level 3 do Machine.State quyết định, không ở đây. */
    public static String decisionLabel(LUTResult r) {
        LUTDecision d = r.decision();
        if (d.level() >= 2) {
            return d.autoIsolationAllowed() ? "PENDING_AUTO_ISOLATION" : "PENDING_ADMIN_APPROVAL";
        }
        if (d.level() == 1) {
            return "WARNING";
        }
        if (d.severity().atLeast(Severity.MEDIUM) && !d.correlated()) {
            return "INCONCLUSIVE";
        }
        return d.severity().atLeast(Severity.LOW) ? "MONITORING" : "NORMAL";
    }

    public static List<String> serverSummary(ServerView v) {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "MSPT server: %.1f ms (cảnh báo %.0f, nguy hiểm %.0f)",
                v.mspt(), v.msptWarning(), v.msptCritical()));
        out.add("Máy theo dõi: " + v.tracked() + " | Chờ duyệt/cảnh báo: " + v.warned()
                + " | Đang cách ly: " + v.isolated());
        return out;
    }

    /** Báo cáo đầy đủ cho một máy, theo định dạng đặc tả mục 11. */
    public static List<String> machineReport(LUTResult r, LUTThresholds t) {
        LUTFeatures f = r.features();
        LUTScore s = r.score();
        List<String> out = new ArrayList<>();
        out.add("[HorizonAI LUT] Phân tích máy");
        out.add("Mã máy: " + r.machineId());
        out.add("Thế giới: " + r.world());
        out.add("Mức: " + r.severity() + " | Điểm rủi ro: " + s.total() + "/100");
        out.add(String.format(Locale.ROOT, "Độ tin cậy: %.2f", r.confidence()));
        out.add("Hoạt động:");
        out.add("- Sự kiện/cửa sổ: " + f.eventsNow());
        out.add(String.format(Locale.ROOT, "- Baseline lịch sử: %.0f", f.baselineEvents()));
        out.add(String.format(Locale.ROOT, "- Tỉ lệ so với baseline: %.2fx", f.eventRatio()));
        out.add("Hiệu năng:");
        out.add(String.format(Locale.ROOT, "- MSPT hiện tại: %.1f ms", f.serverMspt()));
        if (f.deltaValid()) {
            out.add(String.format(Locale.ROOT, "- ΔMSPT khi hoạt động: %+.1f ms", f.msptDelta()));
        } else {
            out.add("- ΔMSPT: chưa đủ dữ liệu (cần cả cửa sổ hoạt động và nhàn rỗi)");
        }
        if (f.correlationValid()) {
            out.add(String.format(Locale.ROOT, "- Pearson: %.2f (n=%d)", f.correlation(), f.sampleCount()));
        } else {
            out.add("- Pearson: không xác định (n=" + f.sampleCount() + ")");
        }
        LUTInput.Composition c = f.composition();
        out.add("Cấu tạo:");
        out.add("- Piston: " + c.pistons() + " | Dispenser: " + c.dispensers()
                + " | Hopper: " + c.hoppers() + " | Observer: " + c.observers());
        out.add("- Linh kiện redstone: " + c.redstoneParts() + (f.scanComplete() ? "" : " (quét chưa đầy đủ)"));
        out.add("Bằng chứng:");
        evidence(r, t).forEach(e -> out.add("- " + e));
        out.add("Khuyến nghị:");
        recommendations(r, t).forEach(e -> out.add("- " + e));
        if (r.decision().level() < 2 && !r.decision().blockers().isEmpty()) {
            out.add("Chưa đủ điều kiện cách ly vì:");
            r.decision().blockers().forEach(b -> out.add("- " + b));
        }
        out.add("Quyết định: " + decisionLabel(r));
        return out;
    }

    /** Bằng chứng suy ra từ đặc trưng. Thứ tự cố định để báo cáo lặp lại được. */
    public static List<String> evidence(LUTResult r, LUTThresholds t) {
        LUTFeatures f = r.features();
        List<String> e = new ArrayList<>();
        if (f.sampleCount() < t.minSamples()) {
            e.add(String.format(Locale.ROOT, "Chưa đủ mẫu (%d/%d): chưa kết luận", f.sampleCount(), t.minSamples()));
        }
        if (f.baselineEvents() > 0 && f.eventsNow() >= t.eventThreshold() && f.eventRatio() >= t.eventRateLow()) {
            e.add(String.format(Locale.ROOT, "Số sự kiện vượt baseline lịch sử %.2f lần", f.eventRatio()));
        }
        if (f.activitySpike()) {
            e.add("Đột biến hoạt động so với baseline");
        }
        if (f.consecutiveHighWindows() >= 3) {
            e.add("Hoạt động cao liên tiếp " + f.consecutiveHighWindows() + " cửa sổ");
        }
        if (f.stableClock()) {
            e.add(String.format(Locale.ROOT,
                    "Mạch có chu kỳ đều %.2f Hz: đây chỉ là dấu hiệu hoạt động, không chứng minh gây lag", f.clockHz()));
        }
        if (f.correlationValid()) {
            if (f.correlation() >= t.correlationMinimum()) {
                e.add(String.format(Locale.ROOT, "Hoạt động và MSPT tương quan dương (r=%.2f)", f.correlation()));
            } else if (f.correlation() < 0) {
                e.add(String.format(Locale.ROOT, "Tương quan âm (r=%.2f): không có bằng chứng máy này gây lag", f.correlation()));
            } else {
                e.add(String.format(Locale.ROOT, "Tương quan yếu (r=%.2f): dưới ngưỡng %.2f", f.correlation(), t.correlationMinimum()));
            }
        }
        if (f.deltaValid() && f.msptDelta() > 0) {
            e.add(String.format(Locale.ROOT, "MSPT cao hơn %.1f ms khi máy hoạt động", f.msptDelta()));
        }
        if (f.deltaValid() && f.deltaConsistency() >= 1.0) {
            e.add("Suy giảm MSPT xuất hiện nhất quán ở cả hai nửa lịch sử");
        } else if (f.deltaValid() && f.deltaConsistency() > 0) {
            e.add("Suy giảm MSPT chỉ xuất hiện ở một nửa lịch sử");
        }
        if (f.otherActive() > 0) {
            e.add(f.otherActive() + " máy khác cùng hoạt động: không xác định được nguyên nhân duy nhất");
        }
        if (!f.boundaryClear()) {
            e.add("Ranh giới máy không rõ ràng");
        }
        if (!f.scanComplete()) {
            e.add("Quét thành phần không đầy đủ");
        }
        return e;
    }

    public static List<String> recommendations(LUTResult r, LUTThresholds t) {
        LUTFeatures f = r.features();
        LUTDecision d = r.decision();
        List<String> out = new ArrayList<>();
        if (d.correlated() && f.stableClock()) {
            out.add("Kiểm tra tần số clock của máy");
        }
        if (d.correlated() && f.eventRatio() >= t.eventRateLow()) {
            out.add("Xem xét các cập nhật redstone lặp lại không cần thiết");
        }
        if (d.severity().atLeast(Severity.MEDIUM) && !d.correlated()) {
            out.add("Hoạt động bất thường nhưng chưa tương quan với lag: theo dõi thêm, chưa cần cách ly");
        }
        if (d.level() == 2 && !d.autoIsolationAllowed()) {
            out.add("Có thể thử cách ly có Admin duyệt");
        }
        if (!f.boundaryClear()) {
            out.add("Admin kiểm tra ranh giới máy thủ công");
        }
        if (!f.scanComplete()) {
            out.add("Đợi chunk tải đầy đủ rồi phân tích lại");
        }
        if (f.sampleCount() < t.minSamples()) {
            out.add("Tiếp tục theo dõi để đủ mẫu");
        }
        if (out.isEmpty()) {
            out.add("Không có khuyến nghị. Tiếp tục theo dõi.");
        }
        return out;
    }
}
