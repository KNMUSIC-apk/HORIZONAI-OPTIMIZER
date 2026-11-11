package com.horizon.ai.lut;

/**
 * Cờ an toàn cho việc chuyển máy sang Level 2 (chờ cách ly) và Level 3 (cách ly).
 *
 * Bất biến an toàn: ba cờ requireCorrelation, requireClearBoundary, requireCompleteScan luôn là true.
 * Nếu config đặt false, compact constructor ép về true (Settings đã ghi cảnh báo). Lý do: không có
 * cấu hình nào được phép cách ly chỉ dựa trên số sự kiện, trên máy có ranh giới mơ hồ, hoặc khi quét thiếu.
 *
 * Hai cờ còn lại là cấu hình thật:
 *  - requireAdminApproval (mặc định true): mọi cách ly phải qua Admin.
 *  - autoIsolate (mặc định false): chỉ có hiệu lực khi requireAdminApproval = false.
 */
public record LUTSafety(
        boolean requireCorrelation,
        boolean requireClearBoundary,
        boolean requireCompleteScan,
        boolean requireAdminApproval,
        boolean autoIsolate) {

    public LUTSafety {
        requireCorrelation = true;
        requireClearBoundary = true;
        requireCompleteScan = true;
    }

    public static LUTSafety defaults() {
        return new LUTSafety(true, true, true, true, false);
    }

    /** Tự cách ly chỉ được phép khi cấu hình bật và Admin không bắt buộc duyệt. */
    public boolean autoIsolationAllowed() {
        return autoIsolate && !requireAdminApproval;
    }
}
