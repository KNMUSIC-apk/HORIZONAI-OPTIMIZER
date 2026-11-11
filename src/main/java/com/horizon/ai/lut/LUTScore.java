package com.horizon.ai.lut;

import java.util.List;

/**
 * Kết quả chấm điểm rủi ro: tổng điểm 0-100 và điểm từng nhóm (đã kẹp theo trần nhóm).
 * rules: mô tả tiếng Việt của các quy tắc đã kích hoạt, dùng cho báo cáo và kiểm tra.
 */
public record LUTScore(
        int total,
        int intensity,
        int activity,
        int clock,
        int performance,
        int correlation,
        int composition,
        int concurrency,
        int stability,
        List<String> rules) {
}
