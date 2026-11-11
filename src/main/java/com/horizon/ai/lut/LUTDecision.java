package com.horizon.ai.lut;

import com.horizon.ai.utils.Severity;

import java.util.List;

/**
 * Kết quả quyết định của LUT cho một máy.
 *
 * level: 0 = bình thường, 1 = cảnh báo (Level 1), 2 = đủ điều kiện chờ cách ly (Level 2).
 *        Level 3 (đã cách ly) là trạng thái của Machine, do MachineIsolation quản lý, không do LUT.
 * isolationEligible: đủ mọi cổng bằng chứng. Vẫn chưa có nghĩa là đã được cách ly.
 * autoIsolationAllowed: chỉ true khi cấu hình cho phép tự cách ly VÀ không bắt buộc Admin duyệt.
 * blockers: lý do cụ thể khiến máy chưa đủ điều kiện Level 2 (rỗng khi đủ điều kiện).
 */
public record LUTDecision(
        Severity severity,
        int level,
        boolean correlated,
        boolean isolationEligible,
        boolean autoIsolationAllowed,
        List<String> blockers) {
}
