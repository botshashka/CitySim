package dev.citysim.cmd;

import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

public final class CommandFeedback {

    private CommandFeedback() {
    }

    public static void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.GREEN));
    }

    public static void sendWarning(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.YELLOW));
    }

    public static void sendError(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.RED));
    }

    public static void sendGray(CommandSender sender, String message) {
        sender.sendMessage(AdventureMessages.colored(message, NamedTextColor.GRAY));
    }

    public static void sendNoPermission(CommandSender sender) {
        sendError(sender, "You do not have permission to do that.");
    }

    public static void sendPlayersOnly(CommandSender sender) {
        sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
    }
}
