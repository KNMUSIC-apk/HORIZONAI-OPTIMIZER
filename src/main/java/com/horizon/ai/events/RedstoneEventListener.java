package com.horizon.ai.events;

import com.horizon.ai.redstone.ActivityTracker;
import com.horizon.ai.redstone.ActorTracker;
import com.horizon.ai.utils.BlockPos;
import com.horizon.ai.utils.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Chỉ GHI NHẬN, không quét và không thay đổi gì. Mọi xử lý nặng nằm ở luồng phân tích.
 * Tất cả handler chạy trên Main Thread và đều O(1).
 */
public final class RedstoneEventListener implements Listener {

    private static final Set<Material> TRACKED_PLACEMENTS = EnumSet.of(
            Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR, Material.OBSERVER,
            Material.PISTON, Material.STICKY_PISTON, Material.HOPPER, Material.DISPENSER, Material.DROPPER,
            Material.TNT, Material.REDSTONE_TORCH, Material.REDSTONE_BLOCK, Material.LEVER,
            Material.STONE_BUTTON, Material.OAK_BUTTON, Material.TARGET);

    private static final Set<Material> TRACKED_INTERACTIONS = EnumSet.of(
            Material.LEVER, Material.STONE_BUTTON, Material.OAK_BUTTON, Material.REPEATER,
            Material.COMPARATOR, Material.DAYLIGHT_DETECTOR, Material.OAK_PRESSURE_PLATE,
            Material.STONE_PRESSURE_PLATE, Material.TARGET);

    private final Settings settings;
    private final ActivityTracker activity;
    private final ActorTracker actors;

    public RedstoneEventListener(Settings settings, ActivityTracker activity, ActorTracker actors) {
        this.settings = settings;
        this.activity = activity;
        this.actors = actors;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onRedstone(BlockRedstoneEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        record(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!TRACKED_PLACEMENTS.contains(b.getType()) || isWhitelisted(b)) {
            return;
        }
        BlockPos pos = posOf(b);
        actors.rememberPlacement(pos, e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !TRACKED_INTERACTIONS.contains(b.getType()) || isWhitelisted(b)) {
            return;
        }
        actors.rememberInteraction(posOf(b), e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    private void record(Block b) {
        if (isWhitelisted(b)) {
            return;
        }
        activity.record(posOf(b), System.nanoTime());
    }

    private boolean isWhitelisted(Block b) {
        String w = b.getWorld().getName();
        return settings.isWorldWhitelisted(w)
                || settings.isInWhitelistedRegion(w, b.getX(), b.getY(), b.getZ());
    }

    private static BlockPos posOf(Block b) {
        Location l = b.getLocation();
        return new BlockPos(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
