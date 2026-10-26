package com.invsee;

import com.invsee.command.EnderSeeCommand;
import com.invsee.command.InvSeeCommand;
import com.invsee.gui.EnderSeeHolder;
import com.invsee.gui.InvSeeHolder;
import com.invsee.listener.InventorySyncListener;
import com.invsee.manager.SessionManager;
import com.invsee.storage.OfflineDataAccess;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Plugin principal InvSee para Paper 26.1, 26.2 y 26.3.
 * Permite inspeccionar y modificar en tiempo real inventarios, armadura, mano secundaria (offhand),
 * mano principal y Ender Chest de jugadores tanto Online como Offline.
 */
public final class InvSeePlugin extends JavaPlugin {

    private OfflineDataAccess offlineDataAccess;
    private SessionManager sessionManager;

    @Override
    public void onEnable() {
        this.offlineDataAccess = new OfflineDataAccess(this);
        this.sessionManager = new SessionManager(this, offlineDataAccess);

        // Registrar listener de eventos de inventario y jugadores
        getServer().getPluginManager().registerEvents(new InventorySyncListener(this, sessionManager), this);

        // Registrar comandos mediante Paper Lifecycle API (compatible con paper-plugin.yml en 26.1, 26.2 y 26.3)
        InvSeeCommand invSeeCommand = new InvSeeCommand(this, sessionManager);
        EnderSeeCommand enderSeeCommand = new EnderSeeCommand(invSeeCommand, sessionManager);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(
                    "invsee",
                    "Ver y editar el inventario, armadura y mano secundaria de un jugador (online u offline)",
                    List.of("openinv", "inv"),
                    invSeeCommand
            );
            registrar.register(
                    "endersee",
                    "Ver y editar el Ender Chest de un jugador (online u offline)",
                    List.of("ecsee", "openender"),
                    enderSeeCommand
            );
        });

        // Tarea periódica (cada 10 ticks = 0.5s) para mantener actualizado el panel de estadísticas y
        // garantizar sincronización continua de sesiones online activas (por ejemplo cambios hechos por otros plugins)
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (InvSeeHolder holder : sessionManager.getActiveInvSessions().values()) {
                if (holder.isOnline() && !holder.isUpdating()) {
                    Player target = Bukkit.getPlayer(holder.getTargetUuid());
                    if (target != null && target.isOnline()) {
                        holder.pullFromOnlinePlayer(target);
                        holder.refreshGuiFromBackingData();
                    }
                }
            }
            for (EnderSeeHolder holder : sessionManager.getActiveEnderSessions().values()) {
                if (holder.isOnline() && !holder.isUpdating()) {
                    Player target = Bukkit.getPlayer(holder.getTargetUuid());
                    if (target != null && target.isOnline()) {
                        holder.pullFromOnlinePlayer(target);
                        holder.refreshGuiFromBackingData();
                    }
                }
            }
        }, 10L, 10L);

        getLogger().info("InvSee habilitado correctamente (Compatible con Paper 26.1, 26.2 y 26.3).");
    }

    @Override
    public void onDisable() {
        if (sessionManager != null) {
            sessionManager.flushAllOnShutdown();
        }
        getLogger().info("InvSee deshabilitado y datos pendientes guardados.");
    }

    public OfflineDataAccess getOfflineDataAccess() {
        return offlineDataAccess;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }
}
