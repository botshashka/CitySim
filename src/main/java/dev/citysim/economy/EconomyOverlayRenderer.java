package dev.citysim.economy;

import dev.citysim.city.City;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight overlay renderer that draws district tile edges with particles.
 * Rendering is intentionally conservative to avoid flooding players with
 * particles on busy servers.
 */
public final class EconomyOverlayRenderer {
    public enum OverlayMode { LAND_VALUE }

    private final EconomyService economyService;
    private final Particle particleType = Particle.DUST;

    private BukkitTask task;
    private final Map<UUID, OverlaySession> sessions = new ConcurrentHashMap<>();

    private final int ttlTicks;
    private final double spacing;
    private final int[] buckets;

    public EconomyOverlayRenderer(EconomyService service, int ttlSeconds, double spacing, java.util.List<Integer> buckets) {
        this.economyService = service;
        this.ttlTicks = Math.max(20, ttlSeconds * 20);
        this.spacing = spacing;
        this.buckets = buckets != null ? buckets.stream().mapToInt(Integer::intValue).toArray() : new int[]{20, 40, 60, 80};
    }

    public void show(Player player, City city, OverlayMode mode) {
        if (player == null || city == null) {
            return;
        }
        OverlaySession session = new OverlaySession(player.getUniqueId(), city.id, mode);
        session.refreshExpiry(ttlTicks);
        sessions.put(player.getUniqueId(), session);
        ensureTask();
    }

    public void hide(Player player) {
        if (player == null) {
            return;
        }
        sessions.remove(player.getUniqueId());
        if (sessions.isEmpty()) {
            cancelTask();
        }
    }

    public void shutdown() {
        cancelTask();
        sessions.clear();
    }

    private void ensureTask() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(economyService.plugin(), this::tick, 20L, 20L);
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> session.expired(now));
        if (sessions.isEmpty()) {
            cancelTask();
            return;
        }

        for (OverlaySession session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            City city = economyService.cityManager().get(session.cityId);
            if (city == null) {
                continue;
            }
            renderFor(player, city, session.mode);
            session.refreshExpiry(ttlTicks);
        }
    }

    private void renderFor(Player player, City city, OverlayMode mode) {
        Map<DistrictKey, DistrictStats> grid = economyService.grid(city.id);
        if (grid == null || grid.isEmpty()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        for (DistrictStats stats : grid.values()) {
            if (!stats.key().world().equals(world.getName())) {
                continue;
            }
            drawTile(player, stats, mode);
        }
        maybeSendActionBar(player, city, mode, grid.values());
    }

    private void drawTile(Player player, DistrictStats stats, OverlayMode mode) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        int tileBlocks = economyService.tileBlockSize();
        int tileChunkSpan = Math.max(1, tileBlocks / 16);
        int chunkX = stats.key().chunkX();
        int chunkZ = stats.key().chunkZ();
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + tileChunkSpan * 16;
        int maxZ = minZ + tileChunkSpan * 16;

        Particle.DustOptions options = colorFor(mode, stats);
        for (double x = minX; x <= maxX; x += spacing) {
            spawnParticle(player, options, new Location(world, x + 0.5, world.getMinHeight() + 1.0, minZ + 0.5));
            spawnParticle(player, options, new Location(world, x + 0.5, world.getMinHeight() + 1.0, maxZ + 0.5));
        }
        for (double z = minZ; z <= maxZ; z += spacing) {
            spawnParticle(player, options, new Location(world, minX + 0.5, world.getMinHeight() + 1.0, z + 0.5));
            spawnParticle(player, options, new Location(world, maxX + 0.5, world.getMinHeight() + 1.0, z + 0.5));
        }
    }

    private void spawnParticle(Player player, Particle.DustOptions options, Location location) {
        if (options == null) {
            return;
        }
        player.spawnParticle(particleType, location, 1, 0, 0, 0, 0, options);
    }

    private Particle.DustOptions colorFor(OverlayMode mode, DistrictStats stats) {
        int value;
        switch (mode) {
            case LAND_VALUE -> value = (int) Math.round(stats.landValue0to100());
            default -> value = 50;
        }
        Color color = colorFromBuckets(value);
        return new Particle.DustOptions(color, 1.0f);
    }

    private Color colorFromBuckets(int value) {
        int bucket = 0;
        for (int threshold : buckets) {
            if (value < threshold) {
                break;
            }
            bucket++;
        }
        return switch (bucket) {
            case 0 -> Color.fromRGB(0xFF0000);
            case 1 -> Color.fromRGB(0xFF8800);
            case 2 -> Color.fromRGB(0xFFEE00);
            case 3 -> Color.fromRGB(0x55FF55);
            default -> Color.fromRGB(0x00AA00);
        };
    }

    private void maybeSendActionBar(Player player, City city, OverlayMode mode, Collection<DistrictStats> stats) {
        Location loc = player.getLocation();
        DistrictStats current = economyService.findDistrict(city, loc).orElse(null);
        if (current == null) {
            player.sendActionBar(Component.empty());
            return;
        }
        if (mode == OverlayMode.LAND_VALUE) {
            String line = "LVI %d | nature %.2f | pollution %.2f | access %.2f".formatted(
                    Math.round(current.landValue0to100()),
                    current.natureRatio(),
                    current.pollutionRatio(),
                    current.accessScore());
            player.sendActionBar(AdventureMessages.colored(line, NamedTextColor.WHITE));
        }
    }

    private static final class OverlaySession {
        private final UUID playerId;
        private final String cityId;
        private final OverlayMode mode;
        private long expiresAt;

        OverlaySession(UUID playerId, String cityId, OverlayMode mode) {
            this.playerId = playerId;
            this.cityId = cityId;
            this.mode = mode;
        }

        void refreshExpiry(int ttlTicks) {
            this.expiresAt = System.currentTimeMillis() + ttlTicks * 50L;
        }

        boolean expired(long now) {
            return now >= expiresAt;
        }
    }
}
