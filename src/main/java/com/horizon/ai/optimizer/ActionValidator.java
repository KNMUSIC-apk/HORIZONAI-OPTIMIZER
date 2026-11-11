package com.horizon.ai.optimizer;

import com.horizon.ai.redstone.MachineTracker;
import com.horizon.ai.utils.Settings;

import java.util.Locale;

/**
 * Cổng kiểm soát mọi đề xuất hành động từ Bộ phân tích LUT trước khi thực thi.
 *
 * Quy tắc:
 *  - Cách ly (ISOLATE) luôn được xếp hàng chờ Admin. Việc tự cách ly, nếu được cấu hình, do
 *    Machine decision engine quyết định sau khi đã qua mọi cổng bằng chứng, không qua đường này.
 *  - Khôi phục (RESTORE) chỉ dành cho thao tác trực tiếp của Admin.
 *  - Hành động tham chiếu máy phải tồn tại trong registry.
 */
public final class ActionValidator {

    public enum ActionType { WARN_PLAYER, NOTIFY_ADMIN, ISOLATE_MACHINE, RESTORE_MACHINE, SUGGEST_OPTIMIZATION }

    public enum Decision { EXECUTE, QUEUE_FOR_ADMIN, SUGGEST_ONLY, REJECT }

    /** Đề xuất hành động. type là chuỗi để có thể đọc từ config hoặc lệnh. */
    public record ProposedAction(String type, String machineId, String reason) {
    }

    public record Result(ProposedAction action, Decision decision, String why) {
    }

    private final Settings settings;
    private final MachineTracker tracker;

    public ActionValidator(Settings settings, MachineTracker tracker) {
        this.settings = settings;
        this.tracker = tracker;
    }

    public Result validate(ProposedAction a) {
        if (a == null || a.type() == null) {
            return new Result(a, Decision.REJECT, "Thiếu loại hành động");
        }
        ActionType type;
        try {
            type = ActionType.valueOf(a.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new Result(a, Decision.REJECT, "Loại hành động không được hỗ trợ: " + a.type());
        }

        boolean needsMachine = type == ActionType.WARN_PLAYER || type == ActionType.ISOLATE_MACHINE
                || type == ActionType.RESTORE_MACHINE;
        if (needsMachine && (a.machineId() == null || tracker.get(a.machineId()).isEmpty())) {
            return new Result(a, Decision.REJECT, "Máy không tồn tại trong registry");
        }

        String name = type.name();
        return switch (type) {
            case WARN_PLAYER, NOTIFY_ADMIN -> settings.autoActions.contains(name)
                    ? new Result(a, Decision.EXECUTE, "Nằm trong whitelist tự động")
                    : new Result(a, Decision.SUGGEST_ONLY, "Không nằm trong whitelist tự động");
            case ISOLATE_MACHINE -> new Result(a, Decision.QUEUE_FOR_ADMIN,
                    "Cách ly luôn cần Admin duyệt hoặc luật cục bộ đủ điều kiện");
            case RESTORE_MACHINE -> new Result(a, Decision.EXECUTE, "Admin thao tác trực tiếp");
            case SUGGEST_OPTIMIZATION -> new Result(a, Decision.SUGGEST_ONLY, "Chỉ đề xuất, không tự thực hiện");
        };
    }
}
