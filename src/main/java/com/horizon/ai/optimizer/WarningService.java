package com.horizon.ai.optimizer;

import com.horizon.ai.redstone.Machine;
import com.horizon.ai.utils.Settings;
import com.horizon.ai.utils.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Cảnh báo Level 1 riêng cho người chơi gần máy (không công khai toàn server).
 * Có cooldown theo máy, theo người chơi và theo nhóm admin để tránh spam.
 *
 * LUỒNG: Main Thread.
 */
public final class WarningService {

    private static final int MAX_RECIPIENTS = 5;
    private static final long CLEANUP_INTERVAL_MS = 60 * 60 * 1000L;

    private final Settings settings;
    private final Logger log;
    private final Map<UUID, Long> playerCooldown = new HashMap<>();
    private final Map<String, Long> adminCooldown = new HashMap<>();
    private long lastCleanup;

    public WarningService(Settings settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    /**
     * Gửi cảnh báo cho người chơi gần máy. Nếu không có ai nhận, báo cho Admin để không mất cảnh báo.
     * Trả về số người chơi đã nhận.
     */
    public int warnNearby(Machine m, long nowMillis) {
        if (nowMillis - m.lastWarningMillis < settings.warnCooldownSeconds * 1000L) {
            return 0;
        }
        cleanup(nowMillis);
        World w = Bukkit.getWorld(m.world);
        if (w == null) {
            return 0;
        }
        Location center = new Location(w, m.region.centerX() + 0.5, m.region.centerY() + 0.5,
                m.region.centerZ() + 0.5);
        double maxSq = (double) settings.warnDistance * settings.warnDistance;

        List<Player> candidates = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            if (isExcluded(p)) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= maxSq) {
                candidates.add(p);
            }
        }
        if (settings.preferActivator && m.activatorName != null) {
            List<Player> activators = candidates.stream()
                    .filter(p -> p.getName().equalsIgnoreCase(m.activatorName)).toList();
            if (!activators.isEmpty()) {
                candidates = new ArrayList<>(activators);
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)));

        int sent = 0;
        for (Player p : candidates) {
            if (sent >= MAX_RECIPIENTS) {
                break;
            }
            Long last = playerCooldown.get(p.getUniqueId());
            if (last != null && nowMillis - last < settings.warnCooldownSeconds * 1000L) {
                continue;
            }
            p.sendMessage(Text.redstoneWarningLine1());
            p.sendMessage(Text.redstoneWarningLine2());
            p.sendMessage(Text.redstoneWarningLine3());
            playerCooldown.put(p.getUniqueId(), nowMillis);
            sent++;
        }

        // Luôn ghi nhận cảnh báo để chuỗi Level 1 -> Level 2 tiến triển; nếu không ai nhận được thì báo Admin.
        m.lastWarningMillis = nowMillis;
        m.warningCount++;
        if (sent == 0) {
            notifyAdmins("§eMáy §f" + m.id + " §ecó dấu hiệu quá tải nhưng không có người chơi nào ở gần. Vui lòng kiểm tra.",
                    "nobody-near:" + m.id, nowMillis, settings.warnCooldownSeconds * 1000L);
        }
        m.log("Cảnh báo Level 1 (" + m.warningCount + "), người nhận: " + sent);
        return sent;
    }

    /** Thông báo cho người có quyền horizonai.use, có cooldown theo khóa. */
    public void notifyAdmins(String message, String key, long nowMillis, long cooldownMillis) {
        Long last = adminCooldown.get(key);
        if (last != null && nowMillis - last < cooldownMillis) {
            return;
        }
        adminCooldown.put(key, nowMillis);
        log.warning("[HorizonAI] " + message.replaceAll("§.", ""));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("horizonai.use")) {
                p.sendMessage(Text.PREFIX + message);
            }
        }
    }

    private boolean isExcluded(Player p) {
        return settings.isPlayerExcluded(p.getName())
                || settings.excludedPlayers.contains(p.getUniqueId().toString())
                || p.hasPermission("horizonai.notify.exclude");
    }

    private void cleanup(long nowMillis) {
        if (nowMillis - lastCleanup < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanup = nowMillis;
        playerCooldown.values().removeIf(t -> nowMillis - t > CLEANUP_INTERVAL_MS);
        adminCooldown.values().removeIf(t -> nowMillis - t > CLEANUP_INTERVAL_MS);
    }
}
