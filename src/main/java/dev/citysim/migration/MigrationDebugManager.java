package dev.citysim.migration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MigrationDebugManager {

    private final Set<UUID> watchers = new HashSet<>();
    private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public boolean toggle(Player player) {
        if (player == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (watchers.remove(id)) {
            return false;
        }
        watchers.add(id);
        return true;
    }

    public boolean isEnabled() {
        return !watchers.isEmpty();
    }

    public void logInfo(String message) {
        log(NamedTextColor.GRAY, message);
    }

    public void logSuccess(String message) {
        log(NamedTextColor.GREEN, message);
    }

    public void logWarning(String message) {
        log(NamedTextColor.YELLOW, message);
    }

    public void logFailure(String message) {
        log(NamedTextColor.RED, message);
    }

    private String timestamp() {
        return LocalDateTime.now().format(timestampFormat);
    }

    private void log(NamedTextColor color, String message) {
        if (!isEnabled() || message == null || message.isBlank()) {
            return;
        }
        Component component = Component.text()
                .append(Component.text("[" + timestamp() + "] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, color))
                .build();
        broadcast(component);
    }

    private void broadcast(Component component) {
        if (watchers.isEmpty()) {
            return;
        }
        var iterator = watchers.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            player.sendMessage(component);
        }
    }
}
