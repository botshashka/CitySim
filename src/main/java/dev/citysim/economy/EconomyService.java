package dev.citysim.economy;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.HappinessCalculator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main entry point for the automatic economy simulation.
 */
public final class EconomyService {
    private final JavaPlugin plugin;
    private final CityManager cityManager;
    private final HappinessCalculator happinessCalculator;
    private final LandValueCalculator landValueCalculator;

    private final Map<String, Map<DistrictKey, DistrictStats>> grids = new ConcurrentHashMap<>();
    private final Map<String, CityEconomy> cityEconomies = new ConcurrentHashMap<>();
    private final Map<String, List<DistrictKey>> cityTiles = new ConcurrentHashMap<>();
    private final Deque<DistrictWork> workQueue = new ArrayDeque<>();

    private final double perWorker;
    private final double happinessMin;
    private final double happinessMax;
    private final double lviMin;
    private final double lviMax;
    private final double accessMin;
    private final double accessMax;
    private final double indexWeightGdp;
    private final double indexWeightLvi;
    private final double indexWeightPopulation;
    private final double emaAlpha;
    private final long refreshIntervalMillis;
    private final int tileBlockSize;
    private final int tileChunkSpan;
    private final int sampleStep;
    private final int tilesPerTick;
    private final boolean enabled;

    private final EconomyOverlayRenderer overlayRenderer;

    private BukkitTask task;

    private static final Set<Material> POLLUTING_BLOCKS = Set.of(
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.LAVA,
            Material.LAVA_CAULDRON,
            Material.JACK_O_LANTERN,
            Material.FURNACE,
            Material.SMOKER,
            Material.BLAST_FURNACE
    );

    public EconomyService(JavaPlugin plugin, CityManager cityManager, HappinessCalculator happinessCalculator) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.happinessCalculator = happinessCalculator;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("economy");
        if (section == null) {
            section = plugin.getConfig().createSection("economy");
        }
        this.enabled = section.getBoolean("enabled", true);
        this.tileBlockSize = Math.max(16, section.getInt("district_tile_blocks", 32));
        this.tileChunkSpan = Math.max(1, tileBlockSize / 16);
        this.sampleStep = Math.max(2, section.getInt("district_sample_step", 8));
        this.refreshIntervalMillis = Math.max(5_000L, section.getLong("refresh_interval_millis", 45_000L));
        this.tilesPerTick = Math.max(1, section.getInt("tiles_per_tick", 2));
        double overlaySpacing = Math.max(2.0, section.getDouble("overlay_spacing", 4.0));

        ConfigurationSection weights = section.getConfigurationSection("lvi_weights");
        double wLight = weights != null ? weights.getDouble("light", 0.25) : 0.25;
        double wNature = weights != null ? weights.getDouble("nature", 0.20) : 0.20;
        double wAccess = weights != null ? weights.getDouble("access", 0.20) : 0.20;
        double wPollution = weights != null ? weights.getDouble("pollution", 0.20) : 0.20;
        double wCrowd = weights != null ? weights.getDouble("crowding", 0.15) : 0.15;
        this.landValueCalculator = new LandValueCalculator(happinessCalculator, wLight, wNature, wAccess, wPollution, wCrowd);

        ConfigurationSection gdpSection = section.getConfigurationSection("gdp");
        this.perWorker = gdpSection != null ? gdpSection.getDouble("per_worker", 4.0) : 4.0;
        this.happinessMin = gdpSection != null ? gdpSection.getDouble("happiness_multiplier_min", 0.80) : 0.80;
        this.happinessMax = gdpSection != null ? gdpSection.getDouble("happiness_multiplier_max", 1.20) : 1.20;
        this.lviMin = gdpSection != null ? gdpSection.getDouble("lvi_multiplier_min", 0.90) : 0.90;
        this.lviMax = gdpSection != null ? gdpSection.getDouble("lvi_multiplier_max", 1.10) : 1.10;
        this.accessMin = gdpSection != null ? gdpSection.getDouble("access_multiplier_min", 0.80) : 0.80;
        this.accessMax = gdpSection != null ? gdpSection.getDouble("access_multiplier_max", 1.20) : 1.20;

        ConfigurationSection indexSection = section.getConfigurationSection("index");
        this.indexWeightGdp = indexSection != null ? indexSection.getDouble("a_gdp_return", 0.6) : 0.6;
        this.indexWeightLvi = indexSection != null ? indexSection.getDouble("b_lvi_return", 0.3) : 0.3;
        this.indexWeightPopulation = indexSection != null ? indexSection.getDouble("c_population_return", 0.1) : 0.1;
        this.emaAlpha = indexSection != null ? indexSection.getDouble("ema_alpha", 0.2) : 0.2;

        int ttlSeconds = section.getInt("overlay_ttl_seconds", 30);
        List<Integer> overlayBuckets = section.getIntegerList("overlay_buckets");
        this.overlayRenderer = new EconomyOverlayRenderer(this, ttlSeconds, overlaySpacing, overlayBuckets);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public CityManager cityManager() {
        return cityManager;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("EconomyService disabled by configuration.");
            return;
        }
        rebuildAll();
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        overlayRenderer.shutdown();
        workQueue.clear();
    }

    public Map<DistrictKey, DistrictStats> grid(String cityId) {
        return grids.getOrDefault(cityId, Map.of());
    }

    public CityEconomy economy(String cityId) {
        return cityEconomies.get(cityId);
    }

    public Optional<DistrictStats> findDistrict(City city, Location location) {
        if (city == null || location == null) {
            return Optional.empty();
        }
        Map<DistrictKey, DistrictStats> map = grids.get(city.id);
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        String world = location.getWorld() != null ? location.getWorld().getName() : null;
        if (world == null) {
            return Optional.empty();
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        int baseChunkX = Math.floorDiv(chunkX, tileChunkSpan) * tileChunkSpan;
        int baseChunkZ = Math.floorDiv(chunkZ, tileChunkSpan) * tileChunkSpan;
        DistrictKey key = new DistrictKey(world, baseChunkX, baseChunkZ);
        return Optional.ofNullable(map.get(key));
    }

    public void showOverlay(Player player, City city, EconomyOverlayRenderer.OverlayMode mode) {
        if (!enabled) {
            player.sendMessage("Economy is disabled in the configuration.");
            return;
        }
        overlayRenderer.show(player, city, mode);
    }

    public void hideOverlay(Player player) {
        overlayRenderer.hide(player);
    }

    public int tileBlockSize() {
        return tileBlockSize;
    }

    public List<DistrictStats> sortedDistricts(String cityId) {
        Map<DistrictKey, DistrictStats> grid = grids.get(cityId);
        if (grid == null) {
            return List.of();
        }
        List<DistrictStats> list = new ArrayList<>(grid.values());
        list.sort(Comparator.comparingDouble(DistrictStats::landValue0to100).reversed());
        return list;
    }

    private void rebuildAll() {
        for (City city : cityManager.all()) {
            if (city == null || city.id == null) {
                continue;
            }
            rebuildCity(city);
        }
    }

    public void rebuildCity(City city) {
        if (city == null || city.id == null) {
            return;
        }
        Map<DistrictKey, DistrictStats> grid = grids.computeIfAbsent(city.id, k -> new ConcurrentHashMap<>());
        Set<DistrictKey> keys = collectKeys(city);
        grid.keySet().retainAll(keys);
        for (DistrictKey key : keys) {
            grid.computeIfAbsent(key, DistrictStats::new);
        }
        List<DistrictKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparing(DistrictKey::world).thenComparingInt(DistrictKey::chunkX).thenComparingInt(DistrictKey::chunkZ));
        cityTiles.put(city.id, ordered);
        refillQueue();
    }

    private Set<DistrictKey> collectKeys(City city) {
        Set<DistrictKey> keys = new LinkedHashSet<>();
        if (city.cuboids == null) {
            return keys;
        }
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            int chunkMinX = Math.floorDiv(cuboid.minX, 16);
            int chunkMaxX = Math.floorDiv(cuboid.maxX, 16);
            int chunkMinZ = Math.floorDiv(cuboid.minZ, 16);
            int chunkMaxZ = Math.floorDiv(cuboid.maxZ, 16);
            int tileMinX = Math.floorDiv(chunkMinX, tileChunkSpan);
            int tileMaxX = Math.floorDiv(chunkMaxX, tileChunkSpan);
            int tileMinZ = Math.floorDiv(chunkMinZ, tileChunkSpan);
            int tileMaxZ = Math.floorDiv(chunkMaxZ, tileChunkSpan);
            for (int tx = tileMinX; tx <= tileMaxX; tx++) {
                for (int tz = tileMinZ; tz <= tileMaxZ; tz++) {
                    int baseChunkX = tx * tileChunkSpan;
                    int baseChunkZ = tz * tileChunkSpan;
                    keys.add(new DistrictKey(cuboid.world, baseChunkX, baseChunkZ));
                }
            }
        }
        return keys;
    }

    private void refillQueue() {
        workQueue.clear();
        for (Map.Entry<String, List<DistrictKey>> entry : cityTiles.entrySet()) {
            String cityId = entry.getKey();
            for (DistrictKey key : entry.getValue()) {
                workQueue.addLast(new DistrictWork(cityId, key));
            }
        }
    }

    private void tick() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        int processed = 0;
        while (processed < tilesPerTick && !workQueue.isEmpty()) {
            DistrictWork work = workQueue.pollFirst();
            if (work == null) {
                break;
            }
            City city = cityManager.get(work.cityId());
            Map<DistrictKey, DistrictStats> grid = grids.get(work.cityId());
            if (city == null || grid == null) {
                continue;
            }
            DistrictStats stats = grid.get(work.key());
            if (stats == null) {
                continue;
            }
            if (!stats.needsRefresh(now, refreshIntervalMillis)) {
                workQueue.addLast(work);
                continue;
            }
            sampleTile(city, stats, now);
            updateCity(city);
            workQueue.addLast(work);
            processed++;
        }
    }

    private void sampleTile(City city, DistrictStats stats, long now) {
        World world = Bukkit.getWorld(stats.key().world());
        if (world == null) {
            return;
        }
        int baseChunkX = stats.key().chunkX();
        int baseChunkZ = stats.key().chunkZ();
        boolean chunkLoaded = false;
        for (int cx = 0; cx < tileChunkSpan; cx++) {
            for (int cz = 0; cz < tileChunkSpan; cz++) {
                if (world.isChunkLoaded(baseChunkX + cx, baseChunkZ + cz)) {
                    chunkLoaded = true;
                    break;
                }
            }
        }
        if (!chunkLoaded) {
            return;
        }

        int minX = baseChunkX * 16;
        int minZ = baseChunkZ * 16;
        int maxX = minX + tileBlockSize - 1;
        int maxZ = minZ + tileBlockSize - 1;

        int samples = 0;
        double lightSum = 0.0;
        double natureHits = 0.0;
        double pollutionHits = 0.0;

        for (int x = minX; x <= maxX; x += sampleStep) {
            for (int z = minZ; z <= maxZ; z += sampleStep) {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                int highestY = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, Math.max(world.getMinHeight(), highestY), z);
                Material material = block.getType();
                lightSum += block.getLightLevel() / 15.0;
                if (isNature(material)) {
                    natureHits += 1.0;
                }
                if (isPolluting(material)) {
                    pollutionHits += 1.0;
                }
                samples++;
            }
        }

        double natureRatio = samples > 0 ? natureHits / samples : stats.natureRatio();
        double pollutionRatio = samples > 0 ? pollutionHits / samples : stats.pollutionRatio();
        double lightAverage = samples > 0 ? lightSum / samples : stats.lightAverage();
        stats.setNatureRatio(clamp01(natureRatio));
        stats.setPollutionRatio(clamp01(pollutionRatio));
        stats.setLightAverage(clamp01(lightAverage));

        double accessScore = computeAccessScore(city);
        stats.setAccessScore(accessScore);

        double tileCount = Math.max(1, cityTiles.getOrDefault(city.id, List.of()).size());
        stats.setHousingCapacity(city.beds / tileCount);
        stats.setJobsCapacity(Math.max(0, city.employed + city.unemployed) / tileCount);
        stats.setCrowdingPenalty(happinessCalculator.computeOvercrowdingPenalty(city));
        stats.setStationsNearby(city.stations / tileCount);

        double lvi = landValueCalculator.computeLvi(city, stats, accessScore);
        stats.setLandValue0to100(lvi);

        stats.markUpdated(now);
    }

    private void updateCity(City city) {
        Map<DistrictKey, DistrictStats> grid = grids.get(city.id);
        if (grid == null || grid.isEmpty()) {
            return;
        }
        CityEconomy economy = cityEconomies.computeIfAbsent(city.id, id -> new CityEconomy());
        double lviAvg = grid.values().stream().mapToDouble(DistrictStats::landValue0to100).average().orElse(0.0);
        double accessAvg = grid.values().stream().mapToDouble(DistrictStats::accessScore).average().orElse(0.6);

        int employed = Math.max(0, city.employed);
        double happinessNorm = clamp01(city.happiness / 100.0);
        double lviNorm = clamp01(lviAvg / 100.0);
        double gdp = employed * perWorker;
        gdp *= lerp(happinessMin, happinessMax, happinessNorm);
        gdp *= lerp(lviMin, lviMax, lviNorm);
        gdp *= lerp(accessMin, accessMax, clamp01(accessAvg));
        double gdpPerCapita = city.population > 0 ? gdp / city.population : 0.0;

        double gdpReturn = economy.lastGdp() > 0.0 ? (gdp - economy.lastGdp()) / economy.lastGdp() : 0.0;
        double lviReturn = economy.lastLvi() > 0.0 ? (lviAvg - economy.lastLvi()) / economy.lastLvi() : 0.0;
        double popReturn = economy.lastPopulation() > 0.0 ? (city.population - economy.lastPopulation()) / economy.lastPopulation() : 0.0;

        economy.update(gdp, gdpPerCapita, lviAvg, gdpReturn, lviReturn, popReturn, city.population,
                emaAlpha, indexWeightGdp, indexWeightLvi, indexWeightPopulation);
    }

    private double computeAccessScore(City city) {
        if (city == null) {
            return 0.6;
        }
        if (city.stations <= 0) {
            return 0.6;
        }
        double base = 0.6;
        base += Math.min(0.4, city.stations * 0.04);
        return clamp01(base);
    }

    private static boolean isNature(Material material) {
        if (material == null) {
            return false;
        }
        if (Tag.LOGS.isTagged(material) || Tag.LEAVES.isTagged(material)) {
            return true;
        }
        return switch (material) {
            case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS, LARGE_FERN, FERN,
                    OAK_SAPLING, SPRUCE_SAPLING, BIRCH_SAPLING, JUNGLE_SAPLING, ACACIA_SAPLING,
                    DARK_OAK_SAPLING, FLOWERING_AZALEA_LEAVES, FLOWERING_AZALEA, AZALEA,
                    LILY_PAD, WATER, SEAGRASS, TALL_SEAGRASS -> true;
            default -> false;
        };
    }

    private static boolean isPolluting(Material material) {
        if (material == null) {
            return false;
        }
        if (POLLUTING_BLOCKS.contains(material)) {
            return true;
        }
        return material.name().contains("FURNACE");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double lerp(double min, double max, double t) {
        t = clamp01(t);
        return min + (max - min) * t;
    }

    private record DistrictWork(String cityId, DistrictKey key) {
    }
}
