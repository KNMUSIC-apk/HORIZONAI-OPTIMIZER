package com.horizon.ai.commands;

import com.horizon.ai.HorizonAI;
import com.horizon.ai.analyzer.HealthReport;
import com.horizon.ai.performance.PerfSample;
import com.horizon.ai.redstone.Machine;
import com.horizon.ai.redstone.MachineIsolation;
import com.horizon.ai.redstone.MachineTracker;
import com.horizon.ai.utils.Region;
import com.horizon.ai.utils.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** /horizonai và các subcommand. Mọi lệnh đều kiểm tra permission riêng. Chạy trên Main Thread. */
public final class HorizonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("status", "analyze", "report", "redstone", "optimize",
            "history", "whitelist", "reload");
    private static final List<String> REDSTONE_SUB = List.of("list", "inspect", "isolate", "restore");

    private final HorizonAI plugin;

    public HorizonCommand(HorizonAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("horizonai.use")) {
            sender.sendMessage(Text.noPermission());
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "analyze" -> analyze(sender);
            case "report" -> report(sender);
            case "redstone" -> redstone(sender, args);
            case "optimize" -> optimize(sender);
            case "history" -> history(sender);
            case "whitelist" -> whitelist(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender s) {
        PerfSample p = plugin.perf().latest();
        s.sendMessage("§6§l=== HorizonAI Status ===");
        if (p == null) {
            s.sendMessage("§7Chưa có dữ liệu hiệu suất. Vui lòng đợi vài giây.");
            return;
        }
        s.sendMessage("§7TPS: " + tpsColor(p.tps()) + String.format(Locale.ROOT, "%.2f", p.tps())
                + " §7| MSPT: " + msptColor(p.mspt()) + String.format(Locale.ROOT, "%.2f ms", p.mspt()));
        s.sendMessage(String.format(Locale.ROOT, "§7RAM: §f%d/%d MB §7| CPU: §f%s",
                p.usedMemMb(), p.maxMemMb(), p.cpuLoad() < 0 ? "N/A" : String.format(Locale.ROOT, "%.0f%%", p.cpuLoad() * 100)));
        s.sendMessage("§7Người chơi: §f" + p.players() + " §7| Entity: §f" + p.entities()
                + " §7| Chunk đã tải: §f" + p.loadedChunks());
        s.sendMessage("§7Redstone (cửa sổ gần nhất): §f" + p.redstoneEvents() + " sự kiện §7| Chunk hoạt động: §f"
                + p.activeChunks());
        int isolated = 0;
        int warned = 0;
        for (Machine m : plugin.tracker().all()) {
            if (m.state == Machine.State.ISOLATED) {
                isolated++;
            } else if (m.state == Machine.State.WARNED || m.state == Machine.State.PENDING_ISOLATION) {
                warned++;
            }
        }
        s.sendMessage("§7Máy theo dõi: §f" + plugin.tracker().size() + " §7| Cảnh báo: §e" + warned
                + " §7| Đang cách ly: §c" + isolated + " §7| Hành động bị chặn: §f"
                + plugin.isolation().blockedActions());
        s.sendMessage("§7Lag spike: §f" + plugin.perf().spikeCount() + " §7| Trạng thái AI: §f" + plugin.ai().statusLabel());
    }

    private void analyze(CommandSender s) {
        PerfSample p = plugin.perf().latest();
        HealthReport local = plugin.local().build(p, plugin.tracker(), plugin.perf().isSpiking());
        printReport(s, local);
        if (!plugin.ai().isEnabled()) {
            s.sendMessage("§7AI đang tắt hoặc chưa có API key: chỉ hiển thị phân tích cục bộ.");
            return;
        }
        s.sendMessage("§7Đang gọi AI phân tích snapshot...");
        plugin.ai().analyze(plugin.guardian().snapshotForAi(System.currentTimeMillis()))
                .thenAccept(opt -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (opt.isEmpty()) {
                        s.sendMessage("§eAI không trả kết quả (giới hạn tần suất, lỗi mạng hoặc circuit breaker). Xem console.");
                        return;
                    }
                    printReport(s, opt.get());
                    plugin.guardian().onAiReport(opt.get());
                }));
    }

    private void report(CommandSender s) {
        printReport(s, plugin.local().build(plugin.perf().latest(), plugin.tracker(), plugin.perf().isSpiking()));
    }

    private void optimize(CommandSender s) {
        HealthReport r = plugin.local().build(plugin.perf().latest(), plugin.tracker(), plugin.perf().isSpiking());
        s.sendMessage("§6§l=== Đề xuất tối ưu (chỉ đề xuất, không tự thay đổi) ===");
        for (String rec : r.recommendations()) {
            s.sendMessage("§7- §f" + rec);
        }
    }

    private void history(CommandSender s) {
        plugin.db().queryList(
                "SELECT ts, actor, action, machine_id, detail FROM audit_log ORDER BY id DESC LIMIT 10",
                rs -> String.format(Locale.ROOT, "§7%tF %<tT §f%s §e%s §7%s §8%s",
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .thenAccept(lines -> Bukkit.getScheduler().runTask(plugin, () -> {
                    s.sendMessage("§6§l=== Lịch sử thao tác (10 gần nhất) ===");
                    if (lines.isEmpty()) {
                        s.sendMessage("§7Chưa có thao tác nào.");
                    }
                    lines.forEach(s::sendMessage);
                }));
    }

    private void redstone(CommandSender s, String[] args) {
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> redstoneList(s);
            case "inspect" -> withMachine(s, args, false, m -> inspect(s, m));
            case "isolate" -> {
                if (!s.hasPermission("horizonai.redstone.isolate")) {
                    s.sendMessage(Text.noPermission());
                    return;
                }
                withMachine(s, args, true, m -> isolate(s, m));
            }
            case "restore" -> {
                if (!s.hasPermission("horizonai.redstone.restore")) {
                    s.sendMessage(Text.noPermission());
                    return;
                }
                withMachine(s, args, true, m -> restore(s, m));
            }
            default -> help(s);
        }
    }

    private void redstoneList(CommandSender s) {
        s.sendMessage("§6§l=== Máy Redstone đang theo dõi ===");
        if (plugin.tracker().size() == 0) {
            s.sendMessage("§7Chưa phát hiện máy nào hoạt động đáng kể.");
            return;
        }
        for (Machine m : plugin.tracker().all()) {
            Region r = m.region;
            s.sendMessage(String.format(Locale.ROOT, "§e%s §7%s %s%s §8%s (%d,%d,%d)",
                    m.id, m.state, Text.severityColor(m.severity), m.severity, m.world,
                    r.centerX(), r.centerY(), r.centerZ()));
        }
    }

    private void inspect(CommandSender s, Machine m) {
        s.sendMessage("§6§l=== Máy " + m.id + " ===");
        s.sendMessage("§7Vị trí: §f" + MachineTracker.describe(m.region));
        s.sendMessage("§7Trạng thái: §f" + m.state + " §7| Mức: " + Text.severityColor(m.severity) + m.severity
                + " §7| Tương quan: §f" + (m.correlated ? "CÓ" : "KHÔNG")
                + " §7| Đủ điều kiện cách ly: §f" + (m.eligible ? "CÓ" : "KHÔNG"));
        s.sendMessage(String.format(Locale.ROOT, "§7Độ tin cậy: §f%.0f%% §7| Sự kiện tổng: §f%d §7| Số lần cách ly: §f%d",
                m.confidence * 100, m.totalEvents, m.isolationCount));
        s.sendMessage(String.format(Locale.ROOT, "§7Thành phần: piston=%d, hopper=%d, dispenser=%d, observer=%d, tnt=%d, redstone=%d%s",
                m.components.pistons, m.components.hoppers, m.components.dispensers, m.components.observers,
                m.components.tnt, m.components.redstoneParts, m.components.partial ? " §e(quét chưa đầy đủ)" : ""));
        s.sendMessage("§7Người đặt gần nhất: §f" + (m.builderName == null ? "không xác định" : m.builderName));
        s.sendMessage("§7Người kích hoạt gần nhất: §f" + (m.activatorName == null ? "không xác định" : m.activatorName));
        s.sendMessage("§7Bằng chứng:");
        for (String e : m.evidence) {
            s.sendMessage("§8  • §7" + e);
        }
        s.sendMessage("§7Nhật ký gần đây:");
        int from = Math.max(0, m.journal.size() - 5);
        for (String j : m.journal.subList(from, m.journal.size())) {
            s.sendMessage("§8  • §7" + j);
        }
    }

    private void isolate(CommandSender s, Machine m) {
        World w = Bukkit.getWorld(m.world);
        if (w == null) {
            s.sendMessage("§cWorld " + m.world + " không được tải.");
            return;
        }
        int wi = plugin.guardian().windowIndex();
        plugin.tracker().scanComponents(m, w, wi);
        MachineIsolation.Outcome o = plugin.isolation().isolate(m, s.getName(), System.currentTimeMillis(), true);
        s.sendMessage(outcomeText(o, m.id));
    }

    private void restore(CommandSender s, Machine m) {
        World w = Bukkit.getWorld(m.world);
        MachineIsolation.Outcome o = plugin.isolation().restore(m, s.getName(), System.currentTimeMillis(),
                w, plugin.guardian().windowIndex());
        if (o == MachineIsolation.Outcome.OK) {
            s.sendMessage("§aĐã khôi phục máy §f" + m.id + "§a. Trạng thái so sánh được ghi vào nhật ký.");
        } else {
            s.sendMessage("§eMáy không ở trạng thái cách ly.");
        }
    }

    private void whitelist(CommandSender s, String[] args) {
        if (!s.hasPermission("horizonai.whitelist")) {
            s.sendMessage(Text.noPermission());
            return;
        }
        String action = args.length < 2 ? "list" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                s.sendMessage("§6§l=== Whitelist ===");
                s.sendMessage("§7Worlds: §f" + plugin.settings().whitelistWorlds);
                s.sendMessage("§7Vùng: §f" + plugin.settings().whitelistRegions.size());
                s.sendMessage("§7Người chơi bị loại khỏi cảnh báo: §f" + plugin.settings().excludedPlayers.size());
            }
            case "addworld" -> {
                if (args.length < 3) {
                    s.sendMessage("§cCú pháp: /horizonai whitelist addworld <world>");
                    return;
                }
                List<String> list = new ArrayList<>(plugin.getConfig().getStringList("whitelist.worlds"));
                if (!list.contains(args[2])) {
                    list.add(args[2]);
                }
                plugin.getConfig().set("whitelist.worlds", list);
                plugin.saveConfig();
                plugin.reloadHorizon();
                s.sendMessage("§aĐã thêm world §f" + args[2] + "§a vào whitelist.");
            }
            case "addregion" -> {
                if (args.length < 3 || Region.parse(args[2]) == null) {
                    s.sendMessage("§cCú pháp: /horizonai whitelist addregion world;minX;minY;minZ;maxX;maxY;maxZ");
                    return;
                }
                List<String> list = new ArrayList<>(plugin.getConfig().getStringList("whitelist.regions"));
                list.add(args[2]);
                plugin.getConfig().set("whitelist.regions", list);
                plugin.saveConfig();
                plugin.reloadHorizon();
                s.sendMessage("§aĐã thêm vùng whitelist.");
                plugin.db().audit(s.getName(), "WHITELIST_ADD", null, args[2]);
            }
            default -> s.sendMessage("§cCú pháp: /horizonai whitelist <list|addworld|addregion>");
        }
    }

    private void reload(CommandSender s) {
        if (!s.hasPermission("horizonai.reload")) {
            s.sendMessage(Text.noPermission());
            return;
        }
        plugin.reloadHorizon();
        s.sendMessage("§aĐã tải lại cấu hình HorizonAI. Các máy đang cách ly vẫn được giữ nguyên.");
        plugin.db().audit(s.getName(), "RELOAD", null, "config reloaded");
    }

    private void help(CommandSender s) {
        s.sendMessage("§6§l=== HorizonAI ===");
        s.sendMessage("§e/horizonai status §7- Tình trạng server");
        s.sendMessage("§e/horizonai analyze §7- Phân tích ngay (cục bộ + AI nếu bật)");
        s.sendMessage("§e/horizonai report §7- Báo cáo cục bộ");
        s.sendMessage("§e/horizonai redstone [list|inspect|isolate|restore] <id>");
        s.sendMessage("§e/horizonai optimize §7- Đề xuất tối ưu");
        s.sendMessage("§e/horizonai history §7- Lịch sử thao tác");
        s.sendMessage("§e/horizonai whitelist <list|addworld|addregion>");
        s.sendMessage("§e/horizonai reload");
    }

    private void withMachine(CommandSender s, String[] args, boolean needId, java.util.function.Consumer<Machine> fn) {
        if (args.length < 3) {
            if (needId) {
                s.sendMessage("§cCần cung cấp Machine ID. Xem: /horizonai redstone list");
            } else {
                s.sendMessage("§cCú pháp: /horizonai redstone inspect <id>");
            }
            return;
        }
        Optional<Machine> m = plugin.tracker().get(args[2]);
        if (m.isEmpty()) {
            s.sendMessage(Text.machineNotFound(args[2]));
            return;
        }
        fn.accept(m.get());
    }

    private void printReport(CommandSender s, HealthReport r) {
        s.sendMessage("§6§l=== Báo cáo HorizonAI (" + r.source() + ") ===");
        s.sendMessage(Text.severityColor(r.overall()) + "Mức tổng thể: " + r.overall());
        s.sendMessage("§f" + r.summary());
        s.sendMessage("§7Nguyên nhân:");
        r.causes().forEach(c -> s.sendMessage("§8  • §7" + c));
        s.sendMessage("§7Khuyến nghị:");
        r.recommendations().forEach(c -> s.sendMessage("§8  • §7" + c));
    }

    private static String outcomeText(MachineIsolation.Outcome o, String id) {
        return switch (o) {
            case OK -> "§aĐã cách ly máy §f" + id + "§a. Chỉ piston, hopper và dispenser trong hộp máy bị chặn. "
                    + "Khôi phục: /horizonai redstone restore " + id;
            case ALREADY_ISOLATED -> "§eMáy đã được cách ly.";
            case NOT_ISOLATED -> "§eMáy không ở trạng thái cách ly.";
            case AMBIGUOUS_BOUNDARY -> "§cRanh giới máy không rõ hoặc quá lớn: không cách ly tự động. Admin cần kiểm tra thủ công.";
            case PARTIAL_SCAN -> "§cMột phần vùng máy chưa được tải: đợi chunk tải đầy đủ rồi thử lại.";
            case NO_CONTAINABLE_PARTS -> "§cMáy không có piston/hopper/dispenser: không thể cách ly an toàn. Hãy kiểm tra thủ công.";
            case COOLDOWN -> "§eMáy đang trong thời gian chờ sau khi khôi phục.";
            case TOO_MANY_ISOLATIONS -> "§cMáy đã bị cách ly quá số lần cho phép. Cần xem xét thủ công.";
        };
    }

    private static String tpsColor(double tps) {
        return tps >= 19.0 ? "§a" : tps >= 15.0 ? "§e" : "§c";
    }

    private static String msptColor(double mspt) {
        return mspt < 40 ? "§a" : mspt < 50 ? "§e" : "§c";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUB, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("redstone")) {
            return filter(REDSTONE_SUB, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            return filter(List.of("list", "addworld", "addregion"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("redstone")
                && REDSTONE_SUB.subList(1, 4).contains(args[1].toLowerCase(Locale.ROOT))) {
            List<String> ids = new ArrayList<>();
            plugin.tracker().all().forEach(m -> ids.add(m.id));
            return filter(ids, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
