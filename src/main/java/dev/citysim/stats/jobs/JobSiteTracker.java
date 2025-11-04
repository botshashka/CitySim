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
import java.util.Collections;

public class JobSiteTracker implements Listener {
    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private final Map<String, LinkedHashMap<ChunkCoordinate, ScanContext>> dirtyChunks = new LinkedHashMap<>();
    private final Map<String, Map<ChunkCoordinate, Map<Profession, Integer>>> cachedChunkJobSites = new LinkedHashMap<>();

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
        clearCachedCounts();
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
        handleBlockChange(event.getBlockPlaced().getType(), event.getBlockPlaced().getLocation(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockChange(event.getBlock().getType(), event.getBlock().getLocation(), -1);
    }

    private void handleBlockChange(Material material, Location location, int delta) {
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

        if (delta != 0) {
            Profession profession = current.professionFor(material);
            if (profession != null) {
                adjustCachedChunkCounts(city, coord, profession, delta);
            }
        }

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

    public Map<Profession, Integer> cityJobSiteTotals(City city) {
        if (city == null || city.id == null) {
            return Map.of();
        }
        synchronized (cachedChunkJobSites) {
            Map<ChunkCoordinate, Map<Profession, Integer>> perCity = cachedChunkJobSites.get(city.id);
            if (perCity == null || perCity.isEmpty()) {
                return Map.of();
            }
            Map<Profession, Integer> totals = new HashMap<>();
            for (Map<Profession, Integer> chunkCounts : perCity.values()) {
                if (chunkCounts == null || chunkCounts.isEmpty()) {
                    continue;
                }
                for (Map.Entry<Profession, Integer> entry : chunkCounts.entrySet()) {
                    Profession profession = entry.getKey();
                    Integer value = entry.getValue();
                    if (profession == null || value == null || value <= 0) {
                        continue;
                    }
                    totals.merge(profession, value, Integer::sum);
                }
            }
            if (totals.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(totals);
        }
    }

    public void updateChunkTotals(City city, String world, int chunkX, int chunkZ, Map<Profession, Integer> counts) {
        if (city == null || city.id == null || world == null) {
            return;
        }
        ChunkCoordinate coord = new ChunkCoordinate(world, chunkX, chunkZ);
        synchronized (cachedChunkJobSites) {
            Map<ChunkCoordinate, Map<Profession, Integer>> perCity = cachedChunkJobSites.computeIfAbsent(city.id, id -> new LinkedHashMap<>());
            if (counts == null || counts.isEmpty()) {
                perCity.remove(coord);
            } else {
                Map<Profession, Integer> snapshot = new HashMap<>();
                for (Map.Entry<Profession, Integer> entry : counts.entrySet()) {
                    Profession profession = entry.getKey();
                    if (profession == null) {
                        continue;
                    }
                    int value = entry.getValue() == null ? 0 : entry.getValue();
                    if (value > 0) {
                        snapshot.put(profession, value);
                    }
                }
                if (snapshot.isEmpty()) {
                    perCity.remove(coord);
                } else {
                    perCity.put(coord, snapshot);
                }
            }
            if (perCity.isEmpty()) {
                cachedChunkJobSites.remove(city.id);
            }
        }
    }

    private void adjustCachedChunkCounts(City city, ChunkCoordinate coord, Profession profession, int delta) {
        if (city == null || city.id == null || coord == null || profession == null || delta == 0) {
            return;
        }
        synchronized (cachedChunkJobSites) {
            Map<ChunkCoordinate, Map<Profession, Integer>> perCity = cachedChunkJobSites.computeIfAbsent(city.id, id -> new LinkedHashMap<>());
            Map<Profession, Integer> counts = perCity.computeIfAbsent(coord, ignored -> new HashMap<>());
            int current = counts.getOrDefault(profession, 0);
            int updated = Math.max(0, current + delta);
            if (updated <= 0) {
                counts.remove(profession);
            } else {
                counts.put(profession, updated);
            }
            if (counts.isEmpty()) {
                perCity.remove(coord);
            }
            if (perCity.isEmpty()) {
                cachedChunkJobSites.remove(city.id);
            }
        }
    }

    private void clearCachedCounts() {
        synchronized (cachedChunkJobSites) {
            cachedChunkJobSites.clear();
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
