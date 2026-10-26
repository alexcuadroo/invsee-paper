package com.invsee.command;

import com.invsee.InvSeePlugin;
import com.invsee.manager.SessionManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Comando /invsee <jugador|uuid> para inspeccionar y modificar el inventario, armadura y offhand
 * de cualquier jugador online u offline.
 */
public class InvSeeCommand implements BasicCommand {

    private final InvSeePlugin plugin;
    private final SessionManager sessionManager;

    public InvSeeCommand(InvSeePlugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Component.text("Este comando solo puede ser ejecutado por un jugador dentro del servidor.", NamedTextColor.RED));
            return;
        }

        if (!viewer.hasPermission("invsee.use")) {
            viewer.sendMessage(Component.text("No tienes permiso para usar este comando.", NamedTextColor.RED));
            return;
        }

        if (args.length < 1) {
            viewer.sendMessage(Component.text("Uso: /invsee <jugador|uuid> [ender]", NamedTextColor.YELLOW));
            return;
        }

        String targetArg = args[0];
        boolean openEnder = args.length >= 2 && (args[1].equalsIgnoreCase("ender") || args[1].equalsIgnoreCase("ec"));

        resolveOfflinePlayer(viewer, targetArg, target -> {
            if (target == null) {
                viewer.sendMessage(Component.text("No se encontró al jugador '" + targetArg + "'.", NamedTextColor.RED));
                return;
            }

            Player onlineTarget = target.getPlayer();
            if (onlineTarget != null && onlineTarget.hasPermission("invsee.exempt")
                    && !viewer.hasPermission("invsee.exempt.bypass")
                    && !onlineTarget.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.sendMessage(Component.text("No puedes inspeccionar el inventario de este jugador (está protegido).", NamedTextColor.RED));
                return;
            }

            if (openEnder) {
                if (!viewer.hasPermission("invsee.enderchest")) {
                    viewer.sendMessage(Component.text("No tienes permiso para inspeccionar el Ender Chest.", NamedTextColor.RED));
                    return;
                }
                sessionManager.openEnderChestSee(viewer, target, true);
            } else {
                sessionManager.openInventorySee(viewer, target);
            }
        });
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length == 0) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getOfflinePlayers())
                    .map(OfflinePlayer::getName)
                    .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(30)
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("ender").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    @Override
    public @Nullable String permission() {
        return "invsee.use";
    }

    /**
     * Resuelve un jugador online u offline sin bloquear el hilo principal del servidor.
     */
    public void resolveOfflinePlayer(Player viewer, String input, java.util.function.Consumer<OfflinePlayer> callback) {
        // 1. Buscar primero entre los jugadores conectados actualmente
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            callback.accept(online);
            return;
        }

        // 2. Intentar parsear como UUID directo
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer byUuid = Bukkit.getOfflinePlayer(uuid);
            callback.accept(byUuid);
            return;
        } catch (IllegalArgumentException ignored) {
        }

        // 3. Buscar en la caché local del servidor (usercache.json / OfflinePlayers conocidos)
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(input);
        if (cached != null) {
            callback.accept(cached);
            return;
        }

        // 4. Búsqueda asíncrona por lista de OfflinePlayers en disco por si el nombre difiere en mayúsculas/minúsculas
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer found = null;
            for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
                if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(input)) {
                    found = candidate;
                    break;
                }
            }
            OfflinePlayer finalFound = found;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (viewer.isOnline()) {
                    callback.accept(finalFound);
                }
            });
        });
    }
}
