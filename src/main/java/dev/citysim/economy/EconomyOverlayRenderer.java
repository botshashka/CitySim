package dev.citysim.economy;

import dev.citysim.city.City;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple particle overlay for district land value inspection.
 * <p>
 * The renderer intentionally keeps particle counts low to respect the same
 * spirit as the visualization service budget. Each tick it emits a handful of
 * particles per tracked district and automatically tears down after the
 * configured TTL.
 */
public final class EconomyOverlayRenderer {
    private final Plugin plugin;
    private final EconomyService economyService;
    private volatile EconomySettings settings;
    private final Map<UUID, OverlaySession> sessions = new ConcurrentHashMap<>();
    private int taskId = -1;
    private volatile List<BucketColor> bucketColors;

    public EconomyOverlayRenderer(Plugin plugin, EconomyService economyService, EconomySettings settings) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.settings = settings;
        this.bucketColors = buildBucketColors(settings.overlayBuckets());
    }

    public void reload(EconomySettings updated) {
        this.settings = updated;
        this.bucketColors = buildBucketColors(updated.overlayBuckets());
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 5L);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = -1;
        sessions.clear();
    }

    public void showLandValue(Player player, City city) {
        if (player == null || city == null) {
            return;
        }
        Map<DistrictKey, DistrictStats> districts = economyService.grid(city.id);
        if (districts.isEmpty()) {
            return;
        }
        OverlaySession session = new OverlaySession(city.id, System.currentTimeMillis() + settings.overlayTtlSeconds() * 1000L);
        for (Map.Entry<DistrictKey, DistrictStats> entry : districts.entrySet()) {
            DistrictKey key = entry.getKey();
            DistrictStats stats = entry.getValue();
            session.tiles.add(new OverlayTile(key, stats));
        }
        sessions.put(player.getUniqueId(), session);
        start();
    }

    public void hide(Player player) {
        if (player == null) {
            return;
        }
        sessions.remove(player.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            OverlaySession session = sessions.get(uuid);
            if (player == null || session == null) {
                sessions.remove(uuid);
                continue;
            }
            if (now >= session.expiresAt) {
                sessions.remove(uuid);
                continue;
            }
            emitParticles(player, session);
            showActionBar(player, session);
        }
        if (sessions.isEmpty() && taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void emitParticles(Player player, OverlaySession session) {
        double y = player.getLocation().getY() + 1.5;
        for (OverlayTile tile : session.tiles) {
            World world = Bukkit.getWorld(tile.key.world());
            if (world == null) {
                continue;
            }
            Particle.DustOptions color = colorFor(tile.stats.landValue0To100());
            int tileBlocks = Math.max(16, settings.districtTileBlocks());
            double minX = (tile.key.chunkX() << 4);
            double minZ = (tile.key.chunkZ() << 4);
            double maxX = minX + tileBlocks;
            double maxZ = minZ + tileBlocks;
            double step = Math.max(1.0, tileBlocks / 4.0);
            for (double x = minX; x <= maxX; x += step) {
                world.spawnParticle(Particle.DUST, x + 0.5, y, minZ + 0.5, 1, 0, 0, 0, 0, color, true);
                world.spawnParticle(Particle.DUST, x + 0.5, y, maxZ - 0.5, 1, 0, 0, 0, 0, color, true);
            }
            for (double z = minZ; z <= maxZ; z += step) {
                world.spawnParticle(Particle.DUST, minX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0, color, true);
                world.spawnParticle(Particle.DUST, maxX - 0.5, y, z + 0.5, 1, 0, 0, 0, 0, color, true);
            }
        }
    }

    private void showActionBar(Player player, OverlaySession session) {
        Location loc = player.getLocation();
        DistrictKey key = locateDistrict(loc.getWorld(), loc.getBlockX(), loc.getBlockZ());
        if (key == null) {
            player.sendActionBar(Component.text("No district data").color(NamedTextColor.GRAY));
            return;
        }
        DistrictStats stats = session.statsFor(key);
        if (stats == null) {
            player.sendActionBar(Component.text("No district data").color(NamedTextColor.GRAY));
            return;
        }
        String message = "LVI %d | nature %.2f | pollution %.2f | access %.2f".formatted(
                stats.landValue0To100(),
                stats.nature(),
                stats.pollution(),
                stats.access());
        player.sendActionBar(Component.text(message));
    }

    private DistrictKey locateDistrict(World world, int blockX, int blockZ) {
        if (world == null) {
            return null;
        }
        int tileBlocks = Math.max(16, settings.districtTileBlocks());
        int chunkX = floorToMultiple(blockX, tileBlocks) >> 4;
        int chunkZ = floorToMultiple(blockZ, tileBlocks) >> 4;
        return new DistrictKey(world.getName(), chunkX, chunkZ);
    }

    private int floorToMultiple(int value, int multiple) {
        int remainder = Math.floorMod(value, multiple);
        return value - remainder;
    }

    private Particle.DustOptions colorFor(int lvi) {
        for (BucketColor bucket : bucketColors) {
            if (lvi < bucket.threshold) {
                return bucket.color;
            }
        }
        return bucketColors.get(bucketColors.size() - 1).color;
    }

    private List<BucketColor> buildBucketColors(List<Integer> buckets) {
        List<BucketColor> colors = new ArrayList<>();
        List<Color> palette = List.of(
                Color.fromRGB(204, 51, 51),
                Color.fromRGB(255, 153, 0),
                Color.fromRGB(255, 214, 51),
                Color.fromRGB(102, 204, 0),
                Color.fromRGB(51, 153, 51)
        );
        int paletteIndex = 0;
        for (Integer threshold : buckets) {
            colors.add(new BucketColor(threshold != null ? threshold : 20, new Particle.DustOptions(palette.get(paletteIndex++ % palette.size()), 1.0F)));
        }
        colors.add(new BucketColor(101, new Particle.DustOptions(palette.get(palette.size() - 1), 1.0F)));
        return colors;
    }

    private record BucketColor(int threshold, Particle.DustOptions color) {}

    private static final class OverlaySession {
        final String cityId;
        final long expiresAt;
        final List<OverlayTile> tiles = new java.util.concurrent.CopyOnWriteArrayList<>();

        OverlaySession(String cityId, long expiresAt) {
            this.cityId = cityId;
            this.expiresAt = expiresAt;
        }

        DistrictStats statsFor(DistrictKey key) {
            for (OverlayTile tile : tiles) {
                if (tile.key.equals(key)) {
                    return tile.stats;
                }
            }
            return null;
        }
    }

    private record OverlayTile(DistrictKey key, DistrictStats stats) {}
}
