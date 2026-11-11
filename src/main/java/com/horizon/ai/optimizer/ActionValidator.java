package com.horizon.ai.optimizer;

import com.horizon.ai.redstone.MachineTracker;
import com.horizon.ai.utils.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cổng kiểm soát mọi đề xuất hành động (từ AI hoặc bộ phân tích cục bộ).
 *
 * Quy tắc:
 *  - Chỉ hành động có trong optimization.auto-whitelist-actions mới được tự động thực hiện.
 *  - Cách ly (ISOLATE) và khôi phục (RESTORE) KHÔNG BAO GIỜ được AI kích hoạt trực tiếp:
 *    ISOLATE chỉ được xếp hàng chờ Admin hoặc để luật cục bộ quyết định, RESTORE chỉ dành cho Admin.
 *  - Hành động tham chiếu máy phải tồn tại trong registry.
 */
public final class ActionValidator {

    public enum ActionType { WARN_PLAYER, NOTIFY_ADMIN, ISOLATE_MACHINE, RESTORE_MACHINE, SUGGEST_OPTIMIZATION }

    public enum Decision { EXECUTE, QUEUE_FOR_ADMIN, SUGGEST_ONLY, REJECT }

    /** Đề xuất thô, có thể đến từ JSON của AI nên type vẫn là chuỗi. */
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

    public List<Result> validateAll(List<ProposedAction> actions, boolean fromAi) {
        List<Result> out = new ArrayList<>();
        if (actions == null) {
            return out;
        }
        for (ProposedAction a : actions) {
            out.add(validate(a, fromAi));
        }
        return out;
    }

    public Result validate(ProposedAction a, boolean fromAi) {
        if (a == null || a.type() == null) {
            return new Result(a, Decision.REJECT, "Thiếu loại hành động");
        }
        ActionType type;
        try {
            type = ActionType.valueOf(a.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new Result(a, Decision.REJECT, "Loại hành động không được hỗ trợ: " + a.type());
        }

        if (fromAi && type == ActionType.RESTORE_MACHINE) {
            return new Result(a, Decision.REJECT, "AI không được phép khôi phục máy");
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
