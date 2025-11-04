package dev.citysim.stats.jobs;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.StatsService;
import dev.citysim.stats.scan.ScanContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public class JobSiteTracker implements Listener {
    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private final Map<String, LinkedHashMap<ChunkCoordinate, ScanContext>> dirtyChunks = new LinkedHashMap<>();

    private volatile JobSiteAssignments assignments = JobSiteAssignments.empty();

    public JobSiteTracker(Plugin plugin, CityManager cityManager, StatsService statsService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.statsService = statsService;
    }

    public JobSiteAssignments assignments() {
        return assignments != null ? assignments : JobSiteAssignments.empty();
    }

    public void updateConfig(FileConfiguration config) {
        Map<Profession, EnumSet<Material>> mapping = defaultAssignments();
        if (config != null) {
            ConfigurationSection section = config.getConfigurationSection("job_sites");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    Profession profession = parseProfession(key);
                    if (profession == null) {
                        plugin.getLogger().warning("Unknown profession in job_sites config: " + key);
                        continue;
                    }
                    EnumSet<Material> materials = EnumSet.noneOf(Material.class);
                    if (section.isList(key)) {
                        for (String value : section.getStringList(key)) {
                            addMaterial(materials, value, key);
                        }
                    } else {
                        String raw = section.getString(key);
                        if (raw != null) {
                            for (String part : raw.split(",")) {
                                addMaterial(materials, part, key);
                            }
                        }
                    }
                    mapping.put(profession, materials);
                }
            }
        }
        assignments = JobSiteAssignments.of(mapping);
    }

    public void markChunkClean(City city, String world, int chunkX, int chunkZ) {
        if (city == null || city.id == null || world == null) {
            return;
        }
        ScanContext nextContext = null;
        synchronized (dirtyChunks) {
            LinkedHashMap<ChunkCoordinate, ScanContext> queue = dirtyChunks.get(city.id);
            if (queue == null) {
                return;
            }
            ChunkCoordinate coord = new ChunkCoordinate(world, chunkX, chunkZ);
            boolean removed = queue.remove(coord) != null;
            if (queue.isEmpty()) {
                dirtyChunks.remove(city.id);
            } else {
                Map.Entry<ChunkCoordinate, ScanContext> entry = queue.entrySet().iterator().next();
                nextContext = entry.getValue();
            }
            if (!removed) {
                return;
            }
        }
        if (nextContext != null) {
            statsService.requestCityUpdateWithContext(city, false, "job site block change", nextContext);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        handleBlockChange(event.getBlockPlaced().getType(), event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockChange(event.getBlock().getType(), event.getBlock().getLocation());
    }

    private void handleBlockChange(Material material, Location location) {
        JobSiteAssignments current = assignments();
        if (current.isEmpty() || !current.isTracked(material) || location == null) {
            return;
        }
        City city = cityManager.cityAt(location);
        if (city == null || city.id == null) {
            return;
        }
        String worldName = location.getWorld() != null ? location.getWorld().getName() : null;
        if (worldName == null) {
            return;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        ChunkCoordinate coord = new ChunkCoordinate(worldName, chunkX, chunkZ);
        ScanContext context = new ScanContext(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());

        boolean added;
        synchronized (dirtyChunks) {
            LinkedHashMap<ChunkCoordinate, ScanContext> queue = dirtyChunks.computeIfAbsent(city.id, id -> new LinkedHashMap<>());
            added = !queue.containsKey(coord);
            queue.put(coord, context);
        }
        if (added) {
            statsService.requestCityUpdateWithContext(city, false, "job site block change", context);
        }
    }

    private void addMaterial(EnumSet<Material> materials, String value, String professionKey) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return;
        }
        Material material = Material.matchMaterial(normalized.toUpperCase(Locale.ROOT));
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + value + "' for job_sites." + professionKey);
            return;
        }
        materials.add(material);
    }

    private Map<Profession, EnumSet<Material>> defaultAssignments() {
        Map<Profession, EnumSet<Material>> defaults = new HashMap<>();
        try {
            defaults.put(Profession.ARMORER, EnumSet.of(Material.BLAST_FURNACE));
            defaults.put(Profession.BUTCHER, EnumSet.of(Material.SMOKER));
            defaults.put(Profession.CARTOGRAPHER, EnumSet.of(Material.CARTOGRAPHY_TABLE));
            defaults.put(Profession.CLERIC, EnumSet.of(Material.BREWING_STAND));
            defaults.put(Profession.FARMER, EnumSet.of(Material.COMPOSTER));
            defaults.put(Profession.FISHERMAN, EnumSet.of(Material.BARREL));
            defaults.put(Profession.FLETCHER, EnumSet.of(Material.FLETCHING_TABLE));
            defaults.put(Profession.LEATHERWORKER, EnumSet.of(Material.CAULDRON));
            defaults.put(Profession.LIBRARIAN, EnumSet.of(Material.LECTERN));
            defaults.put(Profession.MASON, EnumSet.of(Material.STONECUTTER));
            defaults.put(Profession.SHEPHERD, EnumSet.of(Material.LOOM));
            defaults.put(Profession.TOOLSMITH, EnumSet.of(Material.SMITHING_TABLE));
            defaults.put(Profession.WEAPONSMITH, EnumSet.of(Material.GRINDSTONE));
        } catch (ExceptionInInitializerError | NoClassDefFoundError ex) {
            defaults.clear();
        }
        return defaults;
    }

    private Profession parseProfession(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String normalized = rawKey.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.equals("STONEMASON") || normalized.equals("STONE_MASON")) {
            normalized = "MASON";
        }
        try {
            return Profession.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private record ChunkCoordinate(String world, int x, int z) {
    }
}
