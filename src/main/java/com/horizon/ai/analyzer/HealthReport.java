package com.horizon.ai.analyzer;

import com.horizon.ai.optimizer.ActionValidator.ProposedAction;
import com.horizon.ai.utils.Severity;

import java.util.List;
import java.util.Map;

/**
 * Báo cáo sức khỏe server. Có thể đến từ AI (source = AI) hoặc bộ phân tích cục bộ (source = LOCAL).
 *
 * machineConfirmed: machineId -> AI có xác nhận máy là nguyên nhân hay không.
 *   AI chỉ dùng để PHỦ QUYẾT (false) việc cách ly; AI không thể tự kích hoạt cách ly.
 * actions: đề xuất thô. Không bao giờ thực thi trực tiếp, luôn đi qua ActionValidator.
 */
public record HealthReport(
        Severity overall,
        String source,
        String summary,
        List<String> causes,
        List<String> recommendations,
        List<ProposedAction> actions,
        Map<String, Boolean> machineConfirmed,
        long createdMillis) {
}
