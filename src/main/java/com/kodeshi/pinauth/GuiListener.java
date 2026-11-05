package com.kodeshi.pinauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

/**
 * Handles the keypad inventory. Everything in/around a PinAuth inventory is cancelled; only clicks on the
 * top inventory are interpreted, so no item can ever be moved, taken or placed.
 */
public final class GuiListener implements Listener {

    private final PinAuthPlugin plugin;
    private final AuthManager auth;

    public GuiListener(PinAuthPlugin plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder(false) instanceof PinGui.Holder holder) {
            e.setCancelled(true);
            Session s = auth.session(holder.owner);
            if (s == null || !s.uuid.equals(p.getUniqueId())) {
                return;
            }
            ClickType type = e.getClick();
            boolean plain = type == ClickType.LEFT || type == ClickType.RIGHT
                    || type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT;
            if (plain && e.getClickedInventory() == top) {
                auth.handleClick(s, top, e.getRawSlot());
            }
            // keep Bedrock/Java clients in sync after a cancelled click
            Bukkit.getScheduler().runTask(plugin, p::updateInventory);
            return;
        }
        if (auth.isRestricted(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreative(InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && (auth.isRestricted(p) || e.getView().getTopInventory().getHolder(false) instanceof PinGui.Holder)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getView().getTopInventory().getHolder(false) instanceof PinGui.Holder || auth.isRestricted(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        if (auth.isRestricted(p) && !(e.getInventory().getHolder(false) instanceof PinGui.Holder)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) {
            return;
        }
        if (e.getInventory().getHolder(false) instanceof PinGui.Holder holder) {
            Session s = auth.session(holder.owner);
            if (s != null && s.uuid.equals(p.getUniqueId())) {
                auth.onGuiClose(s);
            }
        }
    }
}
