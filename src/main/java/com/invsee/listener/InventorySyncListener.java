package com.invsee.listener;

import com.invsee.InvSeePlugin;
import com.invsee.gui.EnderSeeHolder;
import com.invsee.gui.InvSeeHolder;
import com.invsee.manager.SessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Escucha todos los eventos de interacción con el GUI de /invsee y /endersee,
 * así como los eventos en vivo de jugadores online para mantener sincronización bidireccional.
 */
public class InventorySyncListener implements Listener {

    private final InvSeePlugin plugin;
    private final SessionManager sessionManager;

    public InventorySyncListener(InvSeePlugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }

        Inventory topInv = event.getView().getTopInventory();
        InventoryHolder topHolder = topInv.getHolder(false);

        // 1. Interacción con el GUI de /invsee
        if (topHolder instanceof InvSeeHolder invHolder) {
            handleInvSeeClick(event, viewer, invHolder);
            return;
        }

        // 2. Interacción con el GUI de /endersee
        if (topHolder instanceof EnderSeeHolder enderHolder) {
            handleEnderSeeClick(event, viewer, enderHolder);
            return;
        }

        // 3. Si el jugador que hace click es un jugador objetivo que está siendo observado por un admin
        if (sessionManager.getActiveInvSessions().containsKey(viewer.getUniqueId())
                || sessionManager.getActiveEnderSessions().containsKey(viewer.getUniqueId())) {
            scheduleTargetSync(viewer);
        }
    }

    private void handleInvSeeClick(InventoryClickEvent event, Player viewer, InvSeeHolder holder) {
        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < InvSeeHolder.GUI_SIZE;

        // Botón para abrir el Ender Chest (Slot 44)
        if (rawSlot == InvSeeHolder.SLOT_ENDERCHEST_BTN) {
            event.setCancelled(true);
            if (!viewer.hasPermission("invsee.enderchest")) {
                viewer.sendMessage(Component.text("No tienes permiso para ver el Ender Chest.", NamedTextColor.RED));
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(holder.getTargetUuid());
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.7f, 1.1f);
            Bukkit.getScheduler().runTask(plugin, () -> sessionManager.openEnderChestSee(viewer, target, true));
            return;
        }

        // Bloquear siempre clicks en los cristales decorativos / paneles informativos
        if (clickedTop && InvSeeHolder.isDecorativeOrActionSlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }

        // Si el espectador no tiene permiso de edición, cancelar cualquier interacción que modifique el inventario superior
        if (!viewer.hasPermission("invsee.edit")) {
            if (clickedTop
                    || event.isShiftClick()
                    || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }

        // Evitar que COLLECT_TO_CURSOR (doble click) recoja paneles decorativos o duplique el espejo de mainhand
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                for (int i = 0; i < InvSeeHolder.GUI_SIZE; i++) {
                    if (InvSeeHolder.isDecorativeOrActionSlot(i) || i == InvSeeHolder.SLOT_MAINHAND_MIRROR) {
                        ItemStack slotItem = holder.getInventory().getItem(i);
                        if (slotItem != null && slotItem.isSimilar(cursor)) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        }

        boolean editedMainhandMirror = (rawSlot == InvSeeHolder.SLOT_MAINHAND_MIRROR);

        // En el siguiente tick (cuando Bukkit ya procesó el movimiento del item en el GUI), sincronizamos
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessionManager.handleInvSeeGuiModification(holder, editedMainhandMirror);
        });
    }

    private void handleEnderSeeClick(InventoryClickEvent event, Player viewer, EnderSeeHolder holder) {
        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < EnderSeeHolder.GUI_SIZE;

        // Fila 4 (Slots 27..35): Barra inferior con botón de Volver Atrás, Estado y Cerrar
        if (clickedTop && EnderSeeHolder.isBottomBarSlot(rawSlot)) {
            event.setCancelled(true);

            // Slot 27: Botón "Volver al Inventario Principal"
            if (rawSlot == EnderSeeHolder.SLOT_BACK_BUTTON) {
                if (!viewer.hasPermission("invsee.use")) {
                    viewer.sendMessage(Component.text("No tienes permiso para ver el inventario principal.", NamedTextColor.RED));
                    return;
                }
                if (viewer.hasPermission("invsee.enderchest.edit")) {
                    sessionManager.handleEnderSeeGuiModification(holder);
                }
                sessionManager.cancelReturnToInvSee(viewer.getUniqueId());
                OfflinePlayer target = Bukkit.getOfflinePlayer(holder.getTargetUuid());
                viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
                Bukkit.getScheduler().runTask(plugin, () -> sessionManager.openInventorySee(viewer, target));
                return;
            }

            // Slot 35: Botón "Cerrar Ventana" (cierra directamente sin volver a /invsee)
            if (rawSlot == EnderSeeHolder.SLOT_CLOSE_BUTTON) {
                if (viewer.hasPermission("invsee.enderchest.edit")) {
                    sessionManager.handleEnderSeeGuiModification(holder);
                }
                sessionManager.cancelReturnToInvSee(viewer.getUniqueId());
                viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.7f, 1.0f);
                Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
            }
            return;
        }

        if (!viewer.hasPermission("invsee.enderchest.edit")) {
            if (clickedTop
                    || event.isShiftClick()
                    || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }

        // Evitar que COLLECT_TO_CURSOR recoja botones o paneles de la fila inferior (27..35)
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                for (int i = EnderSeeHolder.ENDER_SLOTS; i < EnderSeeHolder.GUI_SIZE; i++) {
                    ItemStack slotItem = holder.getInventory().getItem(i);
                    if (slotItem != null && slotItem.isSimilar(cursor)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            sessionManager.handleEnderSeeGuiModification(holder);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }

        Inventory topInv = event.getView().getTopInventory();
        InventoryHolder topHolder = topInv.getHolder(false);

        if (topHolder instanceof InvSeeHolder invHolder) {
            boolean affectsTop = false;
            boolean affectsDecorative = false;
            boolean affectsMainhandMirror = false;

            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < InvSeeHolder.GUI_SIZE) {
                    affectsTop = true;
                    if (InvSeeHolder.isDecorativeOrActionSlot(rawSlot)) {
                        affectsDecorative = true;
                    }
                    if (rawSlot == InvSeeHolder.SLOT_MAINHAND_MIRROR) {
                        affectsMainhandMirror = true;
                    }
                }
            }

            if (affectsDecorative) {
                event.setCancelled(true);
                return;
            }

            if (affectsTop && !viewer.hasPermission("invsee.edit")) {
                event.setCancelled(true);
                return;
            }

            if (affectsTop) {
                boolean preferMirror = affectsMainhandMirror;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sessionManager.handleInvSeeGuiModification(invHolder, preferMirror);
                });
            }
            return;
        }

        if (topHolder instanceof EnderSeeHolder enderHolder) {
            boolean affectsTop = false;
            boolean affectsBottomBar = false;

            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < EnderSeeHolder.GUI_SIZE) {
                    affectsTop = true;
                    if (EnderSeeHolder.isBottomBarSlot(rawSlot)) {
                        affectsBottomBar = true;
                    }
                }
            }

            if (affectsBottomBar) {
                event.setCancelled(true);
                return;
            }

            if (affectsTop && !viewer.hasPermission("invsee.enderchest.edit")) {
                event.setCancelled(true);
                return;
            }

            if (affectsTop) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sessionManager.handleEnderSeeGuiModification(enderHolder);
                });
            }
            return;
        }

        if (sessionManager.getActiveInvSessions().containsKey(viewer.getUniqueId())
                || sessionManager.getActiveEnderSessions().containsKey(viewer.getUniqueId())) {
            scheduleTargetSync(viewer);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof InvSeeHolder invHolder) {
            sessionManager.handleInvSeeClose(invHolder);
        } else if (holder instanceof EnderSeeHolder enderHolder && event.getPlayer() instanceof Player viewer) {
            sessionManager.handleEnderSeeClose(viewer, enderHolder);
        } else if (event.getPlayer() instanceof Player player) {
            // Si el jugador objetivo cerró su propio Ender Chest o cofre
            if (sessionManager.getActiveInvSessions().containsKey(player.getUniqueId())
                    || sessionManager.getActiveEnderSessions().containsKey(player.getUniqueId())) {
                scheduleTargetSync(player);
            }
        }
    }

    // ==================== EVENTOS DEL JUGADOR OBJETIVO ONLINE ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessionManager.handleTargetPlayerJoin(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessionManager.cancelReturnToInvSee(player.getUniqueId());
        sessionManager.handleTargetPlayerQuit(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleTargetSync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        scheduleTargetSync(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        scheduleTargetSync(event.getPlayer());
    }

    private void scheduleTargetSync(Player target) {
        if (!sessionManager.getActiveInvSessions().containsKey(target.getUniqueId())
                && !sessionManager.getActiveEnderSessions().containsKey(target.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (target.isOnline()) {
                sessionManager.syncFromOnlineTargetToViewers(target);
            }
        });
    }
}
