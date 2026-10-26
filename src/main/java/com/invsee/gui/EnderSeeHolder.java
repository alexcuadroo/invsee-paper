package com.invsee.gui;

import com.invsee.model.PlayerInventoryData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Representa una sesión compartida de visualización/edición del Ender Chest de un jugador
 * en un menú de 36 slots (4 filas):
 * - Filas 1 a 3 (Slots 0..26): Los 27 slots del Ender Chest del jugador.
 * - Fila 4 (Slots 27..35): Barra inferior con botón para volver atrás al inventario principal (/invsee)
 *   e indicador de estado (Online / Offline). Además, si se abrió viniendo desde /invsee, pulsar ESC
 *   regresa automáticamente al inventario principal sin cerrar todo.
 */
public class EnderSeeHolder implements InventoryHolder {

    public static final int GUI_SIZE = 36;
    public static final int ENDER_SLOTS = 27;

    public static final int SLOT_BACK_BUTTON = 27;
    public static final int SLOT_STATUS_INFO = 31;
    public static final int SLOT_CLOSE_BUTTON = 35;

    private final UUID targetUuid;
    private final String targetName;
    private final Inventory inventory;
    private PlayerInventoryData backingData;
    private boolean online;
    private boolean updating = false;

    public EnderSeeHolder(UUID targetUuid, String targetName, PlayerInventoryData initialData, boolean online) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.backingData = initialData;
        this.online = online;

        Component title = Component.text("Ender Chest: ", NamedTextColor.DARK_PURPLE)
                .append(Component.text(targetName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(online ? " (Online)" : " (Offline)",
                        online ? NamedTextColor.GREEN : NamedTextColor.RED));

        this.inventory = Bukkit.createInventory(this, GUI_SIZE, title);
        refreshGuiFromBackingData();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public PlayerInventoryData getBackingData() {
        return backingData;
    }

    public void setBackingData(PlayerInventoryData backingData) {
        this.backingData = backingData;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public boolean isUpdating() {
        return updating;
    }

    public static boolean isBottomBarSlot(int rawSlot) {
        return rawSlot >= ENDER_SLOTS && rawSlot < GUI_SIZE;
    }

    public void pullFromOnlinePlayer(Player target) {
        ItemStack[] ender = target.getEnderChest().getContents();
        for (int i = 0; i < ENDER_SLOTS; i++) {
            backingData.getEnderChestContents()[i] = (i < ender.length) ? cloneOrNull(ender[i]) : null;
        }
    }

    public void refreshGuiFromBackingData() {
        if (updating) return;
        updating = true;
        try {
            ItemStack[] ender = backingData.getEnderChestContents();
            for (int i = 0; i < ENDER_SLOTS; i++) {
                inventory.setItem(i, cloneOrNull(ender[i]));
            }
            renderBottomBar();
        } finally {
            updating = false;
        }
    }

    public void readGuiIntoBackingData() {
        if (updating) return;
        updating = true;
        try {
            ItemStack[] ender = backingData.getEnderChestContents();
            for (int i = 0; i < ENDER_SLOTS; i++) {
                ender[i] = cloneOrNull(inventory.getItem(i));
            }
        } finally {
            updating = false;
        }
    }

    public void pushToOnlinePlayer(Player target) {
        ItemStack[] ender = backingData.getEnderChestContents();
        for (int i = 0; i < ENDER_SLOTS; i++) {
            target.getEnderChest().setItem(i, cloneOrNull(ender[i]));
        }
    }

    public void renderBottomBar() {
        ItemStack filler = createPane(Material.BLACK_STAINED_GLASS_PANE,
                Component.text(" ", NamedTextColor.DARK_GRAY), List.of());
        for (int i = ENDER_SLOTS; i < GUI_SIZE; i++) {
            inventory.setItem(i, filler);
        }

        // Slot 27: Botón Volver Atrás (al inventario principal /invsee)
        inventory.setItem(SLOT_BACK_BUTTON, createPane(
                Material.ARROW,
                Component.text("⬅ Volver al Inventario Principal", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(
                        Component.text("Haz clic (o pulsa ESC) para regresar", NamedTextColor.GRAY),
                        Component.text("al inventario y equipamiento de " + targetName, NamedTextColor.GOLD)
                )
        ));

        // Slot 31: Indicador de Estado Online / Offline
        Material statusMat = online ? Material.LIME_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE;
        Component statusTitle = online
                ? Component.text("● Jugador ONLINE", NamedTextColor.GREEN, TextDecoration.BOLD)
                : Component.text("● Jugador OFFLINE", NamedTextColor.GOLD, TextDecoration.BOLD);
        List<Component> statusLore = online
                ? List.of(
                Component.text("Ender Chest sincronizado en tiempo real.", NamedTextColor.GRAY))
                : List.of(
                Component.text("Editando Ender Chest en archivo .dat.", NamedTextColor.GRAY),
                Component.text("Se guarda automáticamente al editar.", NamedTextColor.YELLOW));
        inventory.setItem(SLOT_STATUS_INFO, createPane(statusMat, statusTitle, statusLore));

        // Slot 35: Botón para Cerrar completamente sin volver atrás
        inventory.setItem(SLOT_CLOSE_BUTTON, createPane(
                Material.BARRIER,
                Component.text("✖ Cerrar Ventana", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("Haz clic para cerrar directamente.", NamedTextColor.GRAY))
        ));
    }

    private ItemStack createPane(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return null;
        }
        return item.clone();
    }
}
