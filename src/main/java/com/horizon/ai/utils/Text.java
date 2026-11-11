package com.horizon.ai.utils;

/** Toàn bộ chuỗi hiển thị cho người chơi/admin. Dùng mã màu § của Minecraft. */
public final class Text {

    private Text() {
    }

    public static final String PREFIX = "§6[HorizonAI] ";

    public static String redstoneWarningLine1() {
        return PREFIX + "§eMáy Redstone của bạn đang có dấu hiệu gây quá tải server.";
    }

    public static String redstoneWarningLine2() {
        return "§7Vui lòng kiểm tra tốc độ hoạt động của máy.";
    }

    public static String redstoneWarningLine3() {
        return "§7Nếu tình trạng tiếp diễn, hệ thống sẽ tự động giới hạn máy.";
    }

    public static String severityColor(Severity s) {
        return switch (s) {
            case NORMAL -> "§a";
            case LOW -> "§2";
            case MEDIUM -> "§e";
            case HIGH -> "§6";
            case CRITICAL -> "§c";
        };
    }

    public static String noPermission() {
        return PREFIX + "§cBạn không có quyền dùng lệnh này.";
    }

    public static String machineNotFound(String id) {
        return PREFIX + "§cKhông tìm thấy máy có ID " + id + ".";
    }
}
