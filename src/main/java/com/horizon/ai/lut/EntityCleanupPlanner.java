package com.horizon.ai.lut;

/**
 * Tính số thực thể cần xóa để đưa một chunk về mức an toàn. Thuần, không phụ thuộc Bukkit.
 *
 * Chỉ kích hoạt khi số lượng vượt max; khi kích hoạt thì xóa đến target (không xóa thêm).
 */
public final class EntityCleanupPlanner {

    private EntityCleanupPlanner() {
    }

    /** 0 nếu current <= max; ngược lại current - target (không âm). */
    public static int removeCount(int current, int max, int target) {
        if (current <= max) {
            return 0;
        }
        return Math.max(0, current - Math.max(0, target));
    }
}
