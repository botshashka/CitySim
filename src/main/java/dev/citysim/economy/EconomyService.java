package dev.citysim.economy;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.HappinessCalculator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Coordinates district sampling and aggregate economy calculations.
 * <p>
 * The implementation is intentionally conservative: rather than scanning whole
 * cities every tick we update a small number of tiles in a round-robin fashion,
 * smoothing results via EMAs to keep TPS costs predictable on survival servers.
 */
public final class EconomyService {
    private static final int MAX_TILES_PER_TICK = 6;
    private static final double TILE_ALPHA = 0.35D;

    private final Plugin plugin;
    private final CityManager cityManager;
    private final HappinessCalculator happinessCalculator;
    private final Logger logger;

    private volatile EconomySettings settings;
    private LandValueCalculator landValueCalculator;

    private final Map<String, CityCache> caches = new ConcurrentHashMap<>();
    private final Map<String, CityEconomy> cityEconomies = new ConcurrentHashMap<>();

    private int taskId = -1;

    public EconomyService(Plugin plugin,
                          CityManager cityManager,
                          HappinessCalculator happinessCalculator,
                          EconomySettings settings) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.happinessCalculator = happinessCalculator;
        this.settings = settings;
        this.logger = plugin.getLogger();
        this.landValueCalculator = new LandValueCalculator(settings, happinessCalculator);
    }

    public synchronized void start() {
        if (!settings.enabled()) {
            logger.info("Economy service disabled by configuration");
            return;
        }
        if (taskId != -1) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 40L, 20L);
        logger.info("Economy service scheduled (tile size=" + settings.districtTileBlocks() + " blocks)");
    }

    public synchronized void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = -1;
    }

    public void reload(EconomySettings updated) {
        this.settings = updated;
        this.landValueCalculator = new LandValueCalculator(updated, happinessCalculator);
        caches.clear();
        cityEconomies.clear();
        stop();
        start();
    }

    public EconomySettings settings() {
        return settings;
    }

    public Map<DistrictKey, DistrictStats> grid(String cityId) {
        CityCache cache = caches.get(normalize(cityId));
        if (cache == null) {
            return Map.of();
        }
        return Collections.unmodifiableMap(cache.districts);
    }

    public CityEconomy cityEconomy(String cityId) {
        return cityEconomies.get(normalize(cityId));
    }

    public void invalidateCity(String cityId) {
        caches.remove(normalize(cityId));
        cityEconomies.remove(normalize(cityId));
    }

    private void tick() {
        EconomySettings currentSettings = this.settings;
        if (!currentSettings.enabled()) {
            return;
        }

        int processed = 0;
        long now = System.currentTimeMillis();
        Collection<City> cities = cityManager.all();
        if (cities == null || cities.isEmpty()) {
            return;
        }

        Iterator<City> iterator = cities.iterator();
        while (iterator.hasNext() && processed < MAX_TILES_PER_TICK) {
            City city = iterator.next();
            if (city == null || city.world == null) {
                continue;
            }
            CityCache cache = ensureCache(city, currentSettings);
            if (cache == null || cache.rotation.isEmpty()) {
                continue;
            }

            int rotations = cache.rotation.size();
            while (rotations-- > 0 && processed < MAX_TILES_PER_TICK) {
                DistrictKey key = cache.rotation.removeFirst();
                cache.rotation.addLast(key);
                DistrictStats stats = cache.districts.computeIfAbsent(key, ignored -> new DistrictStats());
                if (now - stats.updatedAt() < currentSettings.refreshIntervalMillis()) {
                    continue;
                }
                DistrictStats.TileSample sample = sampleTile(city, cache, key, currentSettings);
                if (sample != null) {
                    stats.applySample(sample, TILE_ALPHA);
                    processed++;
                }
            }

            updateCityEconomy(city, cache, currentSettings);
        }
    }

    private CityCache ensureCache(City city, EconomySettings settings) {
        String id = normalize(city.id);
        CityCache cache = caches.get(id);
        int signature = cuboidSignature(city);
        if (cache != null && cache.signature == signature) {
            return cache;
        }
        CityCache rebuilt = buildCache(city, settings, signature);
        if (rebuilt != null) {
            caches.put(id, rebuilt);
        }
        return rebuilt;
    }

    private CityCache buildCache(City city, EconomySettings settings, int signature) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return null;
        }
        List<DistrictKey> keys = buildDistrictKeys(city, settings);
        if (keys.isEmpty()) {
            return null;
        }
        double totalArea = computeCityArea(city);
        if (totalArea <= 0.0D) {
            return null;
        }
        double[] centroid = computeCentroid(city, totalArea);

        Map<DistrictKey, DistrictStats> districts = new ConcurrentHashMap<>();
        Map<DistrictKey, Double> tileAreas = new HashMap<>();
        Map<DistrictKey, double[]> tileCenters = new HashMap<>();
        Deque<DistrictKey> rotation = new ArrayDeque<>();
        int tileBlocks = settings.districtTileBlocks();
        for (DistrictKey key : keys) {
            rotation.addLast(key);
            districts.putIfAbsent(key, new DistrictStats());
            tileAreas.put(key, computeTileOverlapArea(city, key, tileBlocks));
            tileCenters.put(key, new double[]{tileCenter(key, tileBlocks, true), tileCenter(key, tileBlocks, false)});
        }
        return new CityCache(city.world, signature, totalArea, centroid[0], centroid[1], districts, tileAreas, tileCenters, rotation);
    }

    private List<DistrictKey> buildDistrictKeys(City city, EconomySettings settings) {
        int tileBlocks = Math.max(16, settings.districtTileBlocks());
        List<DistrictKey> keys = new ArrayList<>();
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            int tileMinX = floorToMultiple(cuboid.minX, tileBlocks);
            int tileMaxX = floorToMultiple(cuboid.maxX, tileBlocks);
            int tileMinZ = floorToMultiple(cuboid.minZ, tileBlocks);
            int tileMaxZ = floorToMultiple(cuboid.maxZ, tileBlocks);
            for (int x = tileMinX; x <= tileMaxX; x += tileBlocks) {
                for (int z = tileMinZ; z <= tileMaxZ; z += tileBlocks) {
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    DistrictKey key = new DistrictKey(cuboid.world, chunkX, chunkZ);
                    if (!keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
        }
        keys.sort(Comparator.comparing(DistrictKey::world).thenComparing(DistrictKey::chunkX).thenComparing(DistrictKey::chunkZ));
        return keys;
    }

    private DistrictStats.TileSample sampleTile(City city,
                                                CityCache cache,
                                                DistrictKey key,
                                                EconomySettings settings) {
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            return null;
        }
        int tileBlocks = Math.max(16, settings.districtTileBlocks());
        int sampleStep = Math.max(2, settings.districtSampleStep());
        int minX = key.chunkX() << 4;
        int minZ = key.chunkZ() << 4;
        int maxX = minX + tileBlocks - 1;
        int maxZ = minZ + tileBlocks - 1;

        TileVerticalRange range = findVerticalRange(city, key.world(), minX, maxX, minZ, maxZ, world);
        boolean highrise = city.highrise;
        int verticalStep = Math.max(3, sampleStep);

        double lightSum = 0.0D;
        int lightSamples = 0;
        int natureHits = 0;
        int pollutionHits = 0;
        int probes = 0;

        for (int x = minX; x <= maxX; x += sampleStep) {
            for (int z = minZ; z <= maxZ; z += sampleStep) {
                if (!columnInsideCity(city, key.world(), x, z)) {
                    continue;
                }
                if (highrise) {
                    for (int y = range.minY; y <= range.maxY; y += verticalStep) {
                        Block block = world.getBlockAt(x, y, z);
                        probes++;
                        lightSum += block.getLightFromBlocks();
                        lightSamples++;
                        if (isNature(block.getType())) {
                            natureHits++;
                        }
                        if (isPolluting(block.getType())) {
                            pollutionHits++;
                        }
                    }
                } else {
                    int surfaceY = world.getHighestBlockYAt(x, z);
                    Block block = world.getBlockAt(x, Math.max(world.getMinHeight(), surfaceY - 1), z);
                    probes++;
                    lightSum += block.getLightFromBlocks();
                    lightSamples++;
                    if (isNature(block.getType())) {
                        natureHits++;
                    }
                    if (isPolluting(block.getType())) {
                        pollutionHits++;
                    }
                }
            }
        }

        if (probes == 0 || lightSamples == 0) {
            return null;
        }

        double light = lightSum / lightSamples;
        double natureRatio = (double) natureHits / probes;
        double pollutionRatio = (double) pollutionHits / probes;
        double areaShare = safeAreaShare(cache.tileAreas.getOrDefault(key, 0.0D), cache.totalArea);
        double crowdingPenalty = happinessCalculator.computeOvercrowdingPenalty(city) * areaShare;
        double access = computeAccessScore(city, cache, key, areaShare, settings);
        double housingCap = city.beds * areaShare;
        double jobsCap = (city.employed + city.unemployed) * areaShare;
        double landValue = landValueCalculator.computeLvi(city, light, natureRatio, access, pollutionRatio, crowdingPenalty);
        int stationsNearby = Math.max(0, city.stations);
        return new DistrictStats.TileSample(landValue, natureRatio, pollutionRatio, light, access, housingCap, jobsCap, crowdingPenalty, stationsNearby);
    }

    private void updateCityEconomy(City city, CityCache cache, EconomySettings settings) {
        Map<DistrictKey, DistrictStats> statsMap = cache.districts;
        if (statsMap.isEmpty()) {
            return;
        }
        double lviSum = 0.0D;
        double accessSum = 0.0D;
        int count = 0;
        for (DistrictStats stats : statsMap.values()) {
            if (stats == null) {
                continue;
            }
            if (System.currentTimeMillis() - stats.updatedAt() > settings.refreshIntervalMillis() * 2L) {
                continue;
            }
            lviSum += stats.landValueRaw();
            accessSum += stats.access();
            count++;
        }
        if (count == 0) {
            return;
        }
        double avgLvi = lviSum / count;
        double avgAccess = accessSum / count;
        CityEconomy economy = cityEconomies.computeIfAbsent(normalize(city.id), ignored -> new CityEconomy());
        double prevGdp = economy.gdp();
        double prevLvi = economy.lviAverage();
        int prevPopulation = economy.population();

        double happinessNorm = clamp01(city.happiness / 100.0D);
        double lviNorm = clamp01(avgLvi / 100.0D);
        double accessNorm = clamp01(avgAccess);

        double ppw = settings.gdpPerWorker()
                * lerp(settings.happinessMultiplierMin(), settings.happinessMultiplierMax(), happinessNorm)
                * lerp(settings.lviMultiplierMin(), settings.lviMultiplierMax(), lviNorm)
                * lerp(settings.accessMultiplierMin(), settings.accessMultiplierMax(), accessNorm);
        double gdp = Math.max(0.0D, city.employed * ppw);
        double gdpPerCapita = city.population <= 0 ? 0.0D : gdp / Math.max(1, city.population);

        double gdpReturn = prevGdp > 0 ? (gdp - prevGdp) / prevGdp : 0.0D;
        double lviReturn = prevLvi > 0 ? (avgLvi - prevLvi) / prevLvi : 0.0D;
        double populationReturn = prevPopulation > 0 ? (city.population - prevPopulation) / (double) prevPopulation : 0.0D;

        double alpha = settings.indexEmaAlpha();
        economy.update(gdp, gdpPerCapita, avgLvi, city.population,
                alpha, alpha, alpha,
                gdpReturn, lviReturn, populationReturn,
                settings.indexWeightGdp(), settings.indexWeightLvi(), settings.indexWeightPopulation());
        cityEconomies.put(normalize(city.id), economy);
    }

    private TileVerticalRange findVerticalRange(City city,
                                                String world,
                                                int minX,
                                                int maxX,
                                                int minZ,
                                                int maxZ,
                                                World bukkitWorld) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null || !cuboid.world.equals(world)) {
                continue;
            }
            if (maxX < cuboid.minX || minX > cuboid.maxX || maxZ < cuboid.minZ || minZ > cuboid.maxZ) {
                continue;
            }
            minY = Math.min(minY, cuboid.minY);
            maxY = Math.max(maxY, cuboid.maxY);
        }
        if (minY == Integer.MAX_VALUE || maxY == Integer.MIN_VALUE) {
            minY = bukkitWorld.getMinHeight();
            maxY = Math.max(minY, bukkitWorld.getMinHeight() + 8);
        }
        return new TileVerticalRange(minY, maxY);
    }

    private boolean columnInsideCity(City city, String world, int x, int z) {
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null || !cuboid.world.equals(world)) {
                continue;
            }
            if (x >= cuboid.minX && x <= cuboid.maxX && z >= cuboid.minZ && z <= cuboid.maxZ) {
                return true;
            }
        }
        return false;
    }

    private boolean isNature(Material material) {
        if (material == null) {
            return false;
        }
        if (Tag.LOGS.isTagged(material) || Tag.LEAVES.isTagged(material)) {
            return true;
        }
        return switch (material) {
            case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                    VINE, LILY_PAD,
                    DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                    OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER, PEONY, ROSE_BUSH -> true;
            default -> false;
        };
    }

    private boolean isPolluting(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, SOUL_CAMPFIRE, LAVA, LAVA_CAULDRON -> true;
            default -> false;
        };
    }

    private double computeAccessScore(City city,
                                      CityCache cache,
                                      DistrictKey key,
                                      double areaShare,
                                      EconomySettings settings) {
        // TODO: Replace this heuristic with TrainCarts-powered routing when integration is available.
        double base = 0.6D;
        if (city == null || city.stations <= 0) {
            return base;
        }
        double[] center = cache.tileCenters.getOrDefault(key, new double[]{tileCenter(key, settings.districtTileBlocks(), true), tileCenter(key, settings.districtTileBlocks(), false)});
        double dx = center[0] - cache.centroidX;
        double dz = center[1] - cache.centroidZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double radiusBlocks = settings.stationRadiusChunks() * 16.0D * Math.max(1.0D, Math.sqrt(city.stations));
        double normalized = 1.0D - (distance / Math.max(1.0D, radiusBlocks));
        if (normalized < 0.0D) {
            normalized = 0.0D;
        }
        double boost = 0.4D * normalized;
        double result = base + boost;
        result += 0.05D * Math.min(5, city.stations) * areaShare;
        return Math.max(0.0D, Math.min(1.0D, result));
    }

    private double safeAreaShare(double tileArea, double totalArea) {
        if (tileArea <= 0.0D || totalArea <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, tileArea / totalArea);
    }

    private int cuboidSignature(City city) {
        if (city == null || city.cuboids == null) {
            return 0;
        }
        int hash = 1;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null) {
                continue;
            }
            hash = 31 * hash + Objects.hash(cuboid.world, cuboid.minX, cuboid.maxX, cuboid.minZ, cuboid.maxZ, cuboid.minY, cuboid.maxY);
        }
        return hash;
    }

    private double computeCityArea(City city) {
        long area = 0;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null) {
                continue;
            }
            long width = (long) cuboid.maxX - cuboid.minX + 1;
            long depth = (long) cuboid.maxZ - cuboid.minZ + 1;
            area += Math.max(0L, width) * Math.max(0L, depth);
        }
        return Math.max(1.0D, area);
    }

    private double[] computeCentroid(City city, double totalArea) {
        double sumX = 0.0D;
        double sumZ = 0.0D;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null) {
                continue;
            }
            double width = (cuboid.maxX - cuboid.minX + 1);
            double depth = (cuboid.maxZ - cuboid.minZ + 1);
            double area = Math.max(1.0D, width * depth);
            double centerX = cuboid.minX + width / 2.0D;
            double centerZ = cuboid.minZ + depth / 2.0D;
            sumX += centerX * area;
            sumZ += centerZ * area;
        }
        if (totalArea <= 0) {
            return new double[]{0.0D, 0.0D};
        }
        return new double[]{sumX / totalArea, sumZ / totalArea};
    }

    private double computeTileOverlapArea(City city, DistrictKey key, int tileBlocks) {
        int minX = key.chunkX() << 4;
        int minZ = key.chunkZ() << 4;
        int maxX = minX + tileBlocks - 1;
        int maxZ = minZ + tileBlocks - 1;
        long area = 0;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null || !cuboid.world.equals(key.world())) {
                continue;
            }
            int overlapMinX = Math.max(minX, cuboid.minX);
            int overlapMaxX = Math.min(maxX, cuboid.maxX);
            int overlapMinZ = Math.max(minZ, cuboid.minZ);
            int overlapMaxZ = Math.min(maxZ, cuboid.maxZ);
            if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
                continue;
            }
            long width = (long) overlapMaxX - overlapMinX + 1;
            long depth = (long) overlapMaxZ - overlapMinZ + 1;
            area += Math.max(0L, width) * Math.max(0L, depth);
        }
        return Math.max(0.0D, area);
    }

    private double tileCenter(DistrictKey key, int tileBlocks, boolean xAxis) {
        int min = (xAxis ? key.chunkX() : key.chunkZ()) << 4;
        return min + tileBlocks / 2.0D;
    }

    private double lerp(double min, double max, double t) {
        t = clamp01(t);
        return min + (max - min) * t;
    }

    private double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }

    private int floorToMultiple(int value, int multiple) {
        if (multiple <= 0) {
            return value;
        }
        int remainder = Math.floorMod(value, multiple);
        return value - remainder;
    }

    private String normalize(String cityId) {
        if (cityId == null) {
            return null;
        }
        return cityId.toLowerCase(Locale.ROOT);
    }

    private record TileVerticalRange(int minY, int maxY) { }

    private static final class CityCache {
        final String world;
        final int signature;
        final double totalArea;
        final double centroidX;
        final double centroidZ;
        final Map<DistrictKey, DistrictStats> districts;
        final Map<DistrictKey, Double> tileAreas;
        final Map<DistrictKey, double[]> tileCenters;
        final Deque<DistrictKey> rotation;

        CityCache(String world,
                  int signature,
                  double totalArea,
                  double centroidX,
                  double centroidZ,
                  Map<DistrictKey, DistrictStats> districts,
                  Map<DistrictKey, Double> tileAreas,
                  Map<DistrictKey, double[]> tileCenters,
                  Deque<DistrictKey> rotation) {
            this.world = world;
            this.signature = signature;
            this.totalArea = totalArea;
            this.centroidX = centroidX;
            this.centroidZ = centroidZ;
            this.districts = districts;
            this.tileAreas = tileAreas;
            this.tileCenters = tileCenters;
            this.rotation = rotation;
        }
    }
}
