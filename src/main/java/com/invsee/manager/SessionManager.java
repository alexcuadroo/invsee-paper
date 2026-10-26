package com.invsee.manager;

import com.invsee.InvSeePlugin;
import com.invsee.gui.EnderSeeHolder;
import com.invsee.gui.InvSeeHolder;
import com.invsee.model.PlayerInventoryData;
import com.invsee.storage.OfflineDataAccess;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordina todas las sesiones activas de /invsee y /endersee tanto para jugadores online como offline.
 * Gestiona también el retorno automático al inventario principal cuando un administrador abre el Ender Chest
 * desde /invsee y luego pulsa el botón "Volver atrás" o la tecla ESC.
 */
public class SessionManager {

    private final InvSeePlugin plugin;
    private final OfflineDataAccess offlineDataAccess;

    // Sesiones activas por UUID del jugador objetivo
    private final Map<UUID, InvSeeHolder> activeInvSessions = new ConcurrentHashMap<>();
    private final Map<UUID, EnderSeeHolder> activeEnderSessions = new ConcurrentHashMap<>();

    // Registra qué jugador objetivo tenía abierto un administrador en /invsee antes de saltar al Ender Chest,
    // para que al pulsar ESC (o el botón Volver) regrese a /invsee en vez de cerrar todo.
    private final Map<UUID, UUID> returnToInvSeeOnEnderClose = new ConcurrentHashMap<>();

    // Jugadores que están cambiando de menú programáticamente (para no disparar el retorno en cadena durante openInventory)
    private final Set<UUID> programmaticSwitchingViewers = ConcurrentHashMap.newKeySet();

    // Cache de cambios hechos a jugadores offline pendientes de sincronizar si el jugador se conecta
    private final Map<UUID, PlayerInventoryData> pendingOfflineEdits = new ConcurrentHashMap<>();

    public SessionManager(InvSeePlugin plugin, OfflineDataAccess offlineDataAccess) {
        this.plugin = plugin;
        this.offlineDataAccess = offlineDataAccess;
    }

    public Map<UUID, InvSeeHolder> getActiveInvSessions() {
        return activeInvSessions;
    }

    public Map<UUID, EnderSeeHolder> getActiveEnderSessions() {
        return activeEnderSessions;
    }

    /**
     * Abre el inventario completo (Inventario, Hotbar, Armadura, Offhand, Mainhand) de un jugador (online u offline).
     */
    public void openInventorySee(Player viewer, OfflinePlayer target) {
        returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : targetUuid.toString().substring(0, 8);
        Player onlineTarget = target.getPlayer();

        // Si ya hay una sesión activa para este objetivo, reutilizar el mismo contenedor para que todos vean lo mismo
        InvSeeHolder existing = activeInvSessions.get(targetUuid);
        if (existing != null) {
            if (onlineTarget != null && onlineTarget.isOnline()) {
                existing.setOnline(true);
                existing.pullFromOnlinePlayer(onlineTarget);
                existing.refreshGuiFromBackingData();
            }
            openInventorySafely(viewer, existing.getInventory());
            return;
        }

        if (onlineTarget != null && onlineTarget.isOnline()) {
            PlayerInventoryData data = getOrCreateSharedData(targetUuid, targetName, onlineTarget);
            InvSeeHolder holder = new InvSeeHolder(targetUuid, targetName, data, true);
            holder.pullFromOnlinePlayer(onlineTarget);
            holder.refreshGuiFromBackingData();
            activeInvSessions.put(targetUuid, holder);
            openInventorySafely(viewer, holder.getInventory());
            return;
        }

        // Jugador Offline
        if (!viewer.hasPermission("invsee.offline")) {
            viewer.sendMessage(Component.text("No tienes permiso para ver inventarios de jugadores desconectados.", NamedTextColor.RED));
            return;
        }

        if (!offlineDataAccess.hasPlayerData(targetUuid)) {
            viewer.sendMessage(Component.text("El jugador '" + targetName + "' nunca ha entrado al servidor o no tiene archivo .dat.", NamedTextColor.RED));
            return;
        }

        viewer.sendMessage(Component.text("Cargando datos offline de " + targetName + "...", NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerInventoryData loadedData = offlineDataAccess.loadOfflineData(targetUuid, targetName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                if (loadedData == null) {
                    viewer.sendMessage(Component.text("Error al leer el archivo de datos (.dat) de " + targetName + ".", NamedTextColor.RED));
                    return;
                }

                // Verificar si el jugador se conectó mientras cargábamos el archivo
                Player nowOnline = Bukkit.getPlayer(targetUuid);
                boolean isNowOnline = (nowOnline != null && nowOnline.isOnline());

                // Si hay una sesión de EnderSee abierta para el mismo jugador, compartir el mismo PlayerInventoryData
                EnderSeeHolder enderSession = activeEnderSessions.get(targetUuid);
                PlayerInventoryData sharedData = (enderSession != null) ? enderSession.getBackingData() : loadedData;

                InvSeeHolder holder = activeInvSessions.computeIfAbsent(
                        targetUuid,
                        k -> new InvSeeHolder(targetUuid, targetName, sharedData, isNowOnline)
                );

                if (isNowOnline) {
                    holder.setOnline(true);
                    holder.pullFromOnlinePlayer(nowOnline);
                }
                holder.refreshGuiFromBackingData();
                openInventorySafely(viewer, holder.getInventory());
            });
        });
    }

    /**
     * Abre el Ender Chest (36 slots: 27 de Ender Chest + 9 de barra inferior con botón de volver)
     * de un jugador (online u offline).
     *
     * @param returnToInvSeeOnEsc Si es true, al pulsar ESC en el Ender Chest regresará automáticamente a /invsee.
     */
    public void openEnderChestSee(Player viewer, OfflinePlayer target, boolean returnToInvSeeOnEsc) {
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : targetUuid.toString().substring(0, 8);
        Player onlineTarget = target.getPlayer();

        if (returnToInvSeeOnEsc) {
            returnToInvSeeOnEnderClose.put(viewer.getUniqueId(), targetUuid);
        } else {
            returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());
        }

        EnderSeeHolder existing = activeEnderSessions.get(targetUuid);
        if (existing != null) {
            if (onlineTarget != null && onlineTarget.isOnline()) {
                existing.setOnline(true);
                existing.pullFromOnlinePlayer(onlineTarget);
                existing.refreshGuiFromBackingData();
            }
            openInventorySafely(viewer, existing.getInventory());
            return;
        }

        if (onlineTarget != null && onlineTarget.isOnline()) {
            PlayerInventoryData data = getOrCreateSharedData(targetUuid, targetName, onlineTarget);
            EnderSeeHolder holder = new EnderSeeHolder(targetUuid, targetName, data, true);
            holder.pullFromOnlinePlayer(onlineTarget);
            holder.refreshGuiFromBackingData();
            activeEnderSessions.put(targetUuid, holder);
            openInventorySafely(viewer, holder.getInventory());
            return;
        }

        // Jugador Offline
        if (!viewer.hasPermission("invsee.offline")) {
            returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());
            viewer.sendMessage(Component.text("No tienes permiso para ver el Ender Chest de jugadores desconectados.", NamedTextColor.RED));
            return;
        }

        if (!offlineDataAccess.hasPlayerData(targetUuid)) {
            returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());
            viewer.sendMessage(Component.text("El jugador '" + targetName + "' nunca ha entrado al servidor o no tiene archivo .dat.", NamedTextColor.RED));
            return;
        }

        viewer.sendMessage(Component.text("Cargando Ender Chest offline de " + targetName + "...", NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerInventoryData loadedData = offlineDataAccess.loadOfflineData(targetUuid, targetName);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                if (loadedData == null) {
                    returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());
                    viewer.sendMessage(Component.text("Error al leer el archivo de datos (.dat) de " + targetName + ".", NamedTextColor.RED));
                    return;
                }

                Player nowOnline = Bukkit.getPlayer(targetUuid);
                boolean isNowOnline = (nowOnline != null && nowOnline.isOnline());

                InvSeeHolder invSession = activeInvSessions.get(targetUuid);
                PlayerInventoryData sharedData = (invSession != null) ? invSession.getBackingData() : loadedData;

                EnderSeeHolder holder = activeEnderSessions.computeIfAbsent(
                        targetUuid,
                        k -> new EnderSeeHolder(targetUuid, targetName, sharedData, isNowOnline)
                );

                if (isNowOnline) {
                    holder.setOnline(true);
                    holder.pullFromOnlinePlayer(nowOnline);
                }
                holder.refreshGuiFromBackingData();
                openInventorySafely(viewer, holder.getInventory());
            });
        });
    }

    public void openEnderChestSee(Player viewer, OfflinePlayer target) {
        openEnderChestSee(viewer, target, true);
    }

    /**
     * Cancela el retorno automático con ESC para este espectador (por ejemplo cuando pulsa el botón "Cerrar Ventana").
     */
    public void cancelReturnToInvSee(UUID viewerUuid) {
        returnToInvSeeOnEnderClose.remove(viewerUuid);
    }

    private void openInventorySafely(Player viewer, org.bukkit.inventory.Inventory inventory) {
        programmaticSwitchingViewers.add(viewer.getUniqueId());
        try {
            viewer.openInventory(inventory);
        } finally {
            programmaticSwitchingViewers.remove(viewer.getUniqueId());
        }
    }

    /**
     * Se invoca cuando un administrador realiza un cambio en el GUI de /invsee.
     */
    public void handleInvSeeGuiModification(InvSeeHolder holder, boolean preferMainhandMirror) {
        if (holder.isUpdating()) return;

        // Volcar lo que hay en el GUI hacia backingData y sincronizar el espejo de mainhand
        holder.readGuiIntoBackingData(preferMainhandMirror);

        Player onlineTarget = Bukkit.getPlayer(holder.getTargetUuid());
        if (onlineTarget != null && onlineTarget.isOnline()) {
            holder.setOnline(true);
            holder.pushToOnlinePlayer(onlineTarget);
            onlineTarget.updateInventory();
        } else {
            holder.setOnline(false);
            PlayerInventoryData data = holder.getBackingData();
            pendingOfflineEdits.put(holder.getTargetUuid(), data);
            // Guardar en el archivo .dat de forma asíncrona
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                offlineDataAccess.saveOfflineData(data, true, false);
            });
        }
    }

    /**
     * Se invoca cuando un administrador realiza un cambio en el GUI de /endersee.
     */
    public void handleEnderSeeGuiModification(EnderSeeHolder holder) {
        if (holder.isUpdating()) return;

        holder.readGuiIntoBackingData();

        Player onlineTarget = Bukkit.getPlayer(holder.getTargetUuid());
        if (onlineTarget != null && onlineTarget.isOnline()) {
            holder.setOnline(true);
            holder.pushToOnlinePlayer(onlineTarget);
            onlineTarget.updateInventory();
        } else {
            holder.setOnline(false);
            PlayerInventoryData data = holder.getBackingData();
            pendingOfflineEdits.put(holder.getTargetUuid(), data);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                offlineDataAccess.saveOfflineData(data, false, true);
            });
        }
    }

    /**
     * Sincroniza desde el jugador online hacia las vistas abiertas de administradores
     * cuando el jugador objetivo mueve objetos, recoge items, cambia de slot de hotbar, etc.
     */
    public void syncFromOnlineTargetToViewers(Player target) {
        UUID uuid = target.getUniqueId();
        InvSeeHolder invHolder = activeInvSessions.get(uuid);
        if (invHolder != null && !invHolder.isUpdating()) {
            invHolder.setOnline(true);
            invHolder.pullFromOnlinePlayer(target);
            invHolder.refreshGuiFromBackingData();
        }

        EnderSeeHolder enderHolder = activeEnderSessions.get(uuid);
        if (enderHolder != null && !enderHolder.isUpdating()) {
            enderHolder.setOnline(true);
            enderHolder.pullFromOnlinePlayer(target);
            enderHolder.refreshGuiFromBackingData();
        }
    }

    /**
     * Maneja la entrada de un jugador al servidor:
     * - Si el jugador tenía ediciones hechas mientras estaba offline (o un admin tiene su GUI abierto ahora mismo),
     *   se asegura de aplicar esos datos en memoria al jugador que entra para que nunca se pierdan ni sobrescriban.
     */
    public void handleTargetPlayerJoin(Player joiningPlayer) {
        UUID uuid = joiningPlayer.getUniqueId();
        InvSeeHolder invHolder = activeInvSessions.get(uuid);
        EnderSeeHolder enderHolder = activeEnderSessions.get(uuid);
        PlayerInventoryData pendingData = pendingOfflineEdits.remove(uuid);

        if (invHolder != null) {
            invHolder.setOnline(true);
            invHolder.readGuiIntoBackingData(false);
            invHolder.pushToOnlinePlayer(joiningPlayer);
            invHolder.pullFromOnlinePlayer(joiningPlayer);
            invHolder.refreshGuiFromBackingData();
        } else if (pendingData != null) {
            InvSeeHolder temp = new InvSeeHolder(uuid, joiningPlayer.getName(), pendingData, true);
            temp.pushToOnlinePlayer(joiningPlayer);
        }

        if (enderHolder != null) {
            enderHolder.setOnline(true);
            enderHolder.readGuiIntoBackingData();
            enderHolder.pushToOnlinePlayer(joiningPlayer);
            enderHolder.pullFromOnlinePlayer(joiningPlayer);
            enderHolder.refreshGuiFromBackingData();
        } else if (pendingData != null) {
            EnderSeeHolder tempEnder = new EnderSeeHolder(uuid, joiningPlayer.getName(), pendingData, true);
            tempEnder.pushToOnlinePlayer(joiningPlayer);
        }

        joiningPlayer.updateInventory();
    }

    /**
     * Maneja la desconexión de un jugador que está siendo observado por un administrador:
     * - Pasa la sesión a modo Offline sin cerrarle la ventana al administrador.
     * - Si el administrador sigue haciendo cambios después de que el jugador salió, se guardan en su .dat.
     */
    public void handleTargetPlayerQuit(Player quittingPlayer) {
        UUID uuid = quittingPlayer.getUniqueId();
        InvSeeHolder invHolder = activeInvSessions.get(uuid);
        if (invHolder != null) {
            invHolder.pullFromOnlinePlayer(quittingPlayer);
            invHolder.setOnline(false);
            invHolder.refreshGuiFromBackingData();
        }

        EnderSeeHolder enderHolder = activeEnderSessions.get(uuid);
        if (enderHolder != null) {
            enderHolder.pullFromOnlinePlayer(quittingPlayer);
            enderHolder.setOnline(false);
            enderHolder.refreshGuiFromBackingData();
        }
    }

    /**
     * Se ejecuta cuando un administrador cierra la ventana de /invsee.
     * Si ya no quedan administradores viendo ese inventario, limpia la sesión y hace un guardado final si era offline.
     */
    public void handleInvSeeClose(InvSeeHolder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (holder.getInventory().getViewers().isEmpty()
                    && !returnToInvSeeOnEnderClose.containsValue(holder.getTargetUuid())) {
                activeInvSessions.remove(holder.getTargetUuid(), holder);
                if (!holder.isOnline()) {
                    PlayerInventoryData data = holder.getBackingData();
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        offlineDataAccess.saveOfflineData(data, true, false);
                        if (!activeEnderSessions.containsKey(holder.getTargetUuid())) {
                            pendingOfflineEdits.remove(holder.getTargetUuid());
                        }
                    });
                }
            }
        });
    }

    /**
     * Se ejecuta cuando un administrador cierra la ventana de /endersee (por ejemplo con la tecla ESC).
     * Si el administrador tenía activo el retorno a /invsee, le reabre automáticamente el inventario principal
     * en el siguiente tick sin cerrar toda la inspección.
     */
    public void handleEnderSeeClose(Player viewer, EnderSeeHolder holder) {
        boolean isProgrammatic = programmaticSwitchingViewers.contains(viewer.getUniqueId());
        UUID returnTargetUuid = isProgrammatic ? null : returnToInvSeeOnEnderClose.remove(viewer.getUniqueId());

        // Guardar cambios hechos en el Ender Chest antes de cerrar o volver
        if (viewer.hasPermission("invsee.enderchest.edit")) {
            holder.readGuiIntoBackingData();
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            // Si el admin cerró con ESC y debe volver atrás al inventario principal:
            if (returnTargetUuid != null && viewer.isOnline() && viewer.hasPermission("invsee.use")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(returnTargetUuid);
                openInventorySee(viewer, target);
            }

            if (holder.getInventory().getViewers().isEmpty()) {
                activeEnderSessions.remove(holder.getTargetUuid(), holder);
                if (!holder.isOnline()) {
                    PlayerInventoryData data = holder.getBackingData();
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        offlineDataAccess.saveOfflineData(data, false, true);
                        if (!activeInvSessions.containsKey(holder.getTargetUuid())) {
                            pendingOfflineEdits.remove(holder.getTargetUuid());
                        }
                    });
                }
            }
        });
    }

    /**
     * Guarda síncronamente todas las sesiones offline abiertas al apagar el servidor (onDisable).
     */
    public void flushAllOnShutdown() {
        returnToInvSeeOnEnderClose.clear();
        for (InvSeeHolder holder : activeInvSessions.values()) {
            if (!holder.isOnline()) {
                holder.readGuiIntoBackingData(false);
                offlineDataAccess.saveOfflineData(holder.getBackingData(), true, false);
            }
            new java.util.ArrayList<>(holder.getInventory().getViewers()).forEach(human -> human.closeInventory());
        }
        for (EnderSeeHolder holder : activeEnderSessions.values()) {
            if (!holder.isOnline()) {
                holder.readGuiIntoBackingData();
                offlineDataAccess.saveOfflineData(holder.getBackingData(), false, true);
            }
            new java.util.ArrayList<>(holder.getInventory().getViewers()).forEach(human -> human.closeInventory());
        }
        activeInvSessions.clear();
        activeEnderSessions.clear();
        pendingOfflineEdits.clear();
    }

    private PlayerInventoryData getOrCreateSharedData(UUID targetUuid, String targetName, Player onlineTarget) {
        InvSeeHolder inv = activeInvSessions.get(targetUuid);
        if (inv != null) return inv.getBackingData();
        EnderSeeHolder ender = activeEnderSessions.get(targetUuid);
        if (ender != null) return ender.getBackingData();

        PlayerInventoryData data = new PlayerInventoryData(targetUuid, targetName);
        if (onlineTarget != null) {
            data.setSelectedHotbarSlot(onlineTarget.getInventory().getHeldItemSlot());
        }
        return data;
    }
}
