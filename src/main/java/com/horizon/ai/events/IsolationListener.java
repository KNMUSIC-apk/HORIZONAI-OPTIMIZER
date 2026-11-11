package com.horizon.ai.events;

import com.horizon.ai.redstone.MachineIsolation;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;

/**
 * Thực thi Level 2: chỉ hủy các sự kiện xảy ra BÊN TRONG hộp của máy đang cách ly.
 * Mọi máy khác và toàn bộ phần còn lại của world không bị ảnh hưởng.
 */
public final class IsolationListener implements Listener {

    private final MachineIsolation isolation;

    public IsolationListener(MachineIsolation isolation) {
        this.isolation = isolation;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (blockedAt(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (blockedAt(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (blockedAt(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** Hopper chỉ bị chặn khi nguồn hoặc đích nằm trong hộp cách ly. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent e) {
        if (blockedInventory(e.getSource()) || blockedInventory(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    private boolean blockedAt(Block b) {
        Location l = b.getLocation();
        return isolation.isBlocked(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private boolean blockedInventory(Inventory inv) {
        if (inv == null) {
            return false;
        }
        Location l = inv.getLocation();
        if (l == null || l.getWorld() == null) {
            return false;
        }
        return isolation.isBlocked(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
