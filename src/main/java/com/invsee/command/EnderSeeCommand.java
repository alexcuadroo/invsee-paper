package com.invsee.command;

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

/**
 * Comando /endersee <jugador|uuid> para inspeccionar y modificar directamente el Ender Chest
 * de un jugador online u offline.
 */
public class EnderSeeCommand implements BasicCommand {

    private final InvSeeCommand invSeeCommand;
    private final SessionManager sessionManager;

    public EnderSeeCommand(InvSeeCommand invSeeCommand, SessionManager sessionManager) {
        this.invSeeCommand = invSeeCommand;
        this.sessionManager = sessionManager;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Component.text("Este comando solo puede ser ejecutado por un jugador dentro del servidor.", NamedTextColor.RED));
            return;
        }

        if (!viewer.hasPermission("invsee.enderchest")) {
            viewer.sendMessage(Component.text("No tienes permiso para usar este comando.", NamedTextColor.RED));
            return;
        }

        if (args.length < 1) {
            viewer.sendMessage(Component.text("Uso: /endersee <jugador|uuid>", NamedTextColor.YELLOW));
            return;
        }

        String targetArg = args[0];
        invSeeCommand.resolveOfflinePlayer(viewer, targetArg, target -> {
            if (target == null) {
                viewer.sendMessage(Component.text("No se encontró al jugador '" + targetArg + "'.", NamedTextColor.RED));
                return;
            }

            Player onlineTarget = target.getPlayer();
            if (onlineTarget != null && onlineTarget.hasPermission("invsee.exempt")
                    && !viewer.hasPermission("invsee.exempt.bypass")
                    && !onlineTarget.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.sendMessage(Component.text("No puedes inspeccionar el Ender Chest de este jugador (está protegido).", NamedTextColor.RED));
                return;
            }

            sessionManager.openEnderChestSee(viewer, target);
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
        return List.of();
    }

    @Override
    public @Nullable String permission() {
        return "invsee.enderchest";
    }
}
