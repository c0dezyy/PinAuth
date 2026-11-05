package com.kodeshi.pinauth;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Builds the 54-slot chest keypad. Plain inventory clicks only, so it works for Bedrock via Geyser. */
public final class PinGui {

    private PinGui() {
    }

    /** Marks an inventory as ours so listeners can recognise it cheaply. */
    public static final class Holder implements InventoryHolder {
        public final UUID owner;
        private Inventory inventory;

        Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static Inventory create(Settings st, Session s) {
        Holder holder = new Holder(s.uuid);
        Inventory inv = Bukkit.createInventory(holder, 54, st.title(s.flow));
        holder.inventory = inv;

        ItemStack filler = item(st.material("filler"), Component.text(" "), null);
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(st.slotHead, head(st, s));
        for (int d = 0; d <= 9; d++) {
            inv.setItem(st.slotDigits[d], item(st.material("digit"), st.text("gui-digit", "digit", Integer.toString(d)), null));
        }
        inv.setItem(st.slotClear, item(st.material("clear"), st.text("gui-clear"), null));
        inv.setItem(st.slotDelete, item(st.material("delete"), st.text("gui-delete"), null));
        inv.setItem(st.slotConfirm, item(st.material("confirm"), st.text("gui-confirm"), null));
        inv.setItem(st.slotCancel, item(st.material("cancel"),
                st.text(s.authenticated ? "gui-cancel-change" : "gui-cancel-login"), null));
        renderInput(st, inv, s.input.length());
        return inv;
    }

    /** Updates only the PIN indicator slots. Digits are never shown, only how many were entered. */
    public static void renderInput(Settings st, Inventory inv, int length) {
        for (int i = 0; i < st.maxPinLength; i++) {
            boolean filled = i < length;
            inv.setItem(st.slotPinStart + i, item(
                    st.material(filled ? "pin-filled" : "pin-empty"),
                    st.text(filled ? "gui-pin-filled" : "gui-pin-empty", "n", Integer.toString(i + 1)),
                    null));
        }
    }

    private static ItemStack head(Settings st, Session s) {
        ItemStack stack = new ItemStack(st.material("head"));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (meta instanceof SkullMeta skull) {
                skull.setOwningPlayer(s.player);
            }
            meta.displayName(st.text("gui-prompt-" + s.flow.key).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(st.text("gui-lore-length", "length", st.lengthText())
                    .decoration(TextDecoration.ITALIC, false)));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore != null) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
