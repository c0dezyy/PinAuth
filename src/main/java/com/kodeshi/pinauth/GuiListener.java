package com.kodeshi.pinauth;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Handles the keypad: every interaction with it is cancelled, then interpreted as a button press. */
public final class GuiListener implements Listener {
    private final AuthManager auth;

    public GuiListener(AuthManager auth) {
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        Inventory top = e.getView().getTopInventory();

        if (top.getHolder(false) instanceof PinGui.Holder holder) {
            e.setCancelled(true); // nothing can be moved, taken or placed
            if (e.getClickedInventory() != top) return;
            PinSession s = holder.session();
            if (auth.session(player.getUniqueId()) != s) return;
            ClickType type = e.getClick();
            if (type == ClickType.LEFT || type == ClickType.RIGHT
                    || type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT) {
                auth.handleClick(player, s, e.getRawSlot());
            }
            return;
        }
        if (auth.isRestricted(player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getView().getTopInventory().getHolder(false) instanceof PinGui.Holder
                || auth.isRestricted(player)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (e.getInventory().getHolder(false) instanceof PinGui.Holder holder) {
            auth.handleClose(player, holder);
        }
    }
}
