package dev.citysim.stats.scan;

import dev.citysim.city.City;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ScanDebugManager {
    private final Set<UUID> watchers = new HashSet<>();
    private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public boolean toggle(Player player) {
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

    public void logJobStarted(CityScanJob job) {
        broadcast(Component.text()
                .append(Component.text("[" + timestamp() + "] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(startMessage(job), NamedTextColor.GRAY))
                .build());
    }

    public void logJobCompleted(CityScanJob job) {
        broadcast(Component.text()
                .append(Component.text("[" + timestamp() + "] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(completeMessage(job), NamedTextColor.GRAY))
                .build());
    }

    private String timestamp() {
        return LocalDateTime.now().format(timestampFormat);
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

    private String startMessage(CityScanJob job) {
        String type = describeReason(job);
        String cityLabel = describeCity(job.city());
        String location = describeLocation(job.city(), job.context());
        String refreshMode = job.isForceRefresh() ? "force" : "incremental";
        int chunks = job.totalEntityChunks();
        int cuboids = job.cuboidCount();
        return String.format(
                "Started %s scan for %s at %s — refresh=%s, entityChunks=%d, cuboids=%d",
                type,
                cityLabel,
                location,
                refreshMode,
                chunks,
                cuboids
        );
    }

    private String completeMessage(CityScanJob job) {
        long duration = Math.max(0L, System.currentTimeMillis() - job.startedAtMillis());
        String type = describeReason(job);
        String cityLabel = describeCity(job.city());
        StringBuilder message = new StringBuilder(String.format(
                "Completed %s scan for %s in %d ms — pop=%d, employed=%d, beds=%d, prosperity=%d",
                type,
                cityLabel,
                duration,
                job.populationCount(),
                job.employedCount(),
                job.bedCount(),
                job.resultingHappiness()
        ));
        if (job.trainCartsStationCount() != null) {
            message.append(String.format(
                    ", TrainCarts stations=%d (Signs: %d)",
                    job.trainCartsStationCount().stations(),
                    job.trainCartsStationCount().signs()
            ));
        }
        return message.toString();
    }

    private String describeCity(City city) {
        if (city == null) {
            return "unknown city";
        }
        String name = city.name != null ? city.name : "(unnamed)";
        String id = city.id != null ? city.id : "?";
        return name + " (" + id + ")";
    }

    private String describeReason(CityScanJob job) {
        String reason = job.reasonDescription();
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return job.isForceRefresh() ? "forced update" : "incremental update";
    }

    private String describeLocation(City city, ScanContext context) {
        if (context != null) {
            return context.describe();
        }
        if (city != null && city.cuboids != null && !city.cuboids.isEmpty()) {
            var cuboid = city.cuboids.get(0);
            String world = cuboid.world != null ? cuboid.world : city.world;
            int centerX = cuboid.minX + ((cuboid.maxX - cuboid.minX) / 2);
            int centerY = cuboid.minY + ((cuboid.maxY - cuboid.minY) / 2);
            int centerZ = cuboid.minZ + ((cuboid.maxZ - cuboid.minZ) / 2);
            return (world != null ? world : "unknown") + " (" + centerX + ", " + centerY + ", " + centerZ + ")";
        }
        if (city != null && city.world != null) {
            return city.world;
        }
        return "unknown";
    }
}
