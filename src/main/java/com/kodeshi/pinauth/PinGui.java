package com.kodeshi.pinauth;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Builds and renders the 6-row chest keypad. Plain inventory GUI: works for Java and Bedrock (Geyser). */
public final class PinGui {
    private PinGui() {
    }

    /** Marks inventories created by this plugin. */
    public static final class Holder implements InventoryHolder {
        private final PinSession session;
        private Inventory inventory;

        Holder(PinSession session) {
            this.session = session;
        }

        public PinSession session() {
            return session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static Inventory create(Player player, PinSession session, Settings st) {
        Holder holder = new Holder(session);
        Inventory inv = Bukkit.createInventory(holder, Settings.GUI_SIZE, st.title(session.stage));
        holder.inventory = inv;
        renderAll(inv, player, session, st);
        return inv;
    }

    public static void renderAll(Inventory inv, Player player, PinSession s, Settings st) {
        ItemStack filler = named(st.fillerMaterial, " ");
        for (int i = 0; i < Settings.GUI_SIZE; i++) inv.setItem(i, filler);

        renderInfo(inv, player, s, st);
        for (int d = 0; d < 10; d++) {
            inv.setItem(st.digitSlots[d], named(st.digitMaterial, "&f&l" + d));
        }
        inv.setItem(st.clearSlot, named(st.clearMaterial, st.nameClear));
        inv.setItem(st.backspaceSlot, named(st.backspaceMaterial, st.nameBackspace));
        inv.setItem(st.confirmSlot, named(st.confirmMaterial, st.nameConfirm));
        if (st.cancelSlot >= 0) inv.setItem(st.cancelSlot, named(st.cancelMaterial, st.nameCancel));
        renderPin(inv, s, st);
    }

    public static void renderInfo(Inventory inv, Player player, PinSession s, Settings st) {
        ItemStack item = new ItemStack(st.infoMaterial);
        List<Component> lore = new ArrayList<>();
        for (String line : st.infoLore()) {
            lore.add(Text.item(line.replace("{player}", player.getName())));
        }
        item.editMeta(meta -> {
            meta.displayName(Text.item(st.prompt(s.stage)));
            meta.lore(lore);
            if (meta instanceof SkullMeta skull) {
                skull.setPlayerProfile(player.getPlayerProfile());
            }
        });
        inv.setItem(st.infoSlot, item);
    }

    public static void renderPin(Inventory inv, PinSession s, Settings st) {
        ItemStack filler = named(st.fillerMaterial, " ");
        int len = s.input.length();
        for (int i = 0; i < st.pinSlots.length; i++) {
            if (i >= st.maxPinLength) {
                inv.setItem(st.pinSlots[i], filler);
                continue;
            }
            boolean filled = i < len;
            String name;
            if (filled) {
                name = st.showDigits ? "&a&l" + s.input.charAt(i) : st.namePinFilled;
            } else {
                name = st.namePinEmpty;
            }
            inv.setItem(st.pinSlots[i], named(filled ? st.pinFilledMaterial : st.pinEmptyMaterial, name));
        }
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Text.item(name)));
        return item;
    }
}
