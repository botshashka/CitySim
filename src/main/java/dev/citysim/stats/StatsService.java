
package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

public class StatsService {
    public enum EmploymentMode { PROFESSION_ONLY, PROFESSION_AND_WORKSTATION, WORKSTATION_PROXIMITY }

    private static final long DEFAULT_STATS_INITIAL_DELAY_TICKS = 40L;
    private static final long DEFAULT_STATS_INTERVAL_TICKS = 100L;
    private static final long MIN_STATS_INTERVAL_TICKS = 20L;
    private static final long MAX_STATS_INTERVAL_TICKS = 12000L; // 10 minutes at 20 TPS
    private static final long MAX_STATS_INITIAL_DELAY_TICKS = 6000L; // 5 minutes at 20 TPS

    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;
    private long statsInitialDelayTicks = DEFAULT_STATS_INITIAL_DELAY_TICKS;
    private long statsIntervalTicks = DEFAULT_STATS_INTERVAL_TICKS;

    private EmploymentMode employmentMode = EmploymentMode.PROFESSION_ONLY;
    private int wsRadius = 16;
    private int wsYRadius = 8;
    private long blockScanRefreshIntervalMillis = 60000L;

    // Weights
    private static final int HIGHRISE_VERTICAL_STEP = 4;

    private double lightNeutral = 2.0;
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double overcrowdMaxPenalty = 10;
    private double natureMaxPts = 10;
    private double pollutionMaxPenalty = 15;
    private double housingMaxPts = 10;

    public StatsService(Plugin plugin, CityManager cm) {
        this.plugin = plugin;
        this.cityManager = cm;
        updateConfig();
    }

    public void start() {
        updateConfig();
        if (taskId != -1) return;
        scheduleTask();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void restartTask() {
        updateConfig();
        stop();
        scheduleTask();
    }

    private void tick() {
        for (City city : cityManager.all()) {
            updateCity(city);
        }
    }

    public HappinessBreakdown updateCity(City city) {
        return updateCity(city, false);
    }

    public HappinessBreakdown updateCity(City city, boolean forceRefresh) {
        int pop = 0, employed = 0, golems = 0;

        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;

            int minCX = c.minX >> 4, maxCX = c.maxX >> 4;
            int minCZ = c.minZ >> 4, maxCZ = c.maxZ >> 4;

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    if (!w.isChunkLoaded(cx, cz)) continue;
                    Chunk ch = w.getChunkAt(cx, cz);
                    for (Entity e : ch.getEntities()) {
                        Location loc = e.getLocation();
                        if (!c.contains(loc)) continue;

                        if (e instanceof Villager v) {
                            pop++;
                            Villager.Profession prof = v.getProfession();
                            boolean isNitwit = prof == Villager.Profession.NITWIT;
                            boolean hasProf = prof != Villager.Profession.NONE && !isNitwit;
                            boolean nearWork = hasNearbyWorkstation(loc, wsRadius, wsYRadius);
                            boolean emp;
                            switch (employmentMode) {
                                case PROFESSION_ONLY:            emp = hasProf; break;
                                case WORKSTATION_PROXIMITY:     emp = nearWork; break;
                                case PROFESSION_AND_WORKSTATION:
                                default:                         emp = hasProf && nearWork; break;
                            }
                            if (emp) employed++;
                        } else if (e instanceof IronGolem) {
                            if (c.contains(e.getLocation())) golems++;
                        }
                    }
                }
            }
        }

        int beds = countBeds(city);
        int unemployed = Math.max(0, pop - employed);

        city.population = pop;
        city.employed = employed;
        city.unemployed = unemployed;
        city.beds = beds;
        city.golems = golems;

        City.BlockScanCache metrics = ensureBlockScanCache(city, forceRefresh);
        HappinessBreakdown hb = calculateHappinessBreakdown(city, metrics);
        city.happinessBreakdown = hb;
        city.happiness = hb.total;

        return hb;
    }

    private boolean hasNearbyWorkstation(Location loc, int radius, int yRadius) {
        World w = loc.getWorld();
        int r = radius;
        for (int x = -r; x <= r; x++) {
            for (int y = -yRadius; y <= yRadius; y++) {
                for (int z = -r; z <= r; z++) {
                    if (Workstations.JOB_BLOCKS.contains(w.getBlockAt(
                            loc.getBlockX()+x, loc.getBlockY()+y, loc.getBlockZ()+z).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public HappinessBreakdown computeHappinessBreakdown(City city) {
        if (city.happinessBreakdown != null && city.blockScanCache != null) {
            return city.happinessBreakdown;
        }
        City.BlockScanCache metrics = city.blockScanCache;
        if (metrics != null) {
            HappinessBreakdown hb = calculateHappinessBreakdown(city, metrics);
            city.happinessBreakdown = hb;
            city.happiness = hb.total;
            return hb;
        }
        return updateCity(city, true);
    }

    private HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache metrics) {
        int pop = city.population;
        int employed = city.employed;
        HappinessBreakdown hb = new HappinessBreakdown();

        double lightScore = metrics.light;
        double lightScoreNormalized = (lightScore - lightNeutral) / lightNeutral;
        hb.lightPoints = clamp(lightScoreNormalized * lightMaxPts, -lightMaxPts, lightMaxPts);

        double employmentRate = pop <= 0 ? 0.0 : (double) employed / (double) pop;
        double employmentScore = (employmentRate - 0.5) / 0.5; // 50% employment is neutral
        hb.employmentPoints = clamp(employmentScore * employmentMaxPts, -employmentMaxPts, employmentMaxPts);

        hb.overcrowdingPenalty = clamp(metrics.overcrowdingPenalty, 0.0, overcrowdMaxPenalty);

        double nature = metrics.nature;
        double natureTarget = 0.10;
        double natureScore = (nature - natureTarget) / natureTarget;
        hb.naturePoints = clamp(natureScore * natureMaxPts, -natureMaxPts, natureMaxPts);

        double pollution = metrics.pollution;
        double pollutionTarget = 0.01;
        double pollutionSeverity = Math.max(0.0, (pollution - pollutionTarget) / pollutionTarget);
        hb.pollutionPenalty = clamp(pollutionSeverity * pollutionMaxPenalty, 0.0, pollutionMaxPenalty);

        int beds = city.beds;
        double housingRatio = pop <= 0 ? 1.0 : Math.min(2.0, (double) beds / Math.max(1.0, (double) pop));
        hb.housingPoints = clamp((housingRatio - 1.0) * housingMaxPts, -housingMaxPts, housingMaxPts);

        double total = hb.base
                + hb.lightPoints
                + hb.employmentPoints
                - hb.overcrowdingPenalty
                + hb.naturePoints
                - hb.pollutionPenalty
                + hb.housingPoints;

        if (total < 0) total = 0;
        if (total > 100) total = 100;
        hb.total = (int)Math.round(total);
        return hb;
    }

    private City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
        long now = System.currentTimeMillis();
        City.BlockScanCache cache = city.blockScanCache;
        boolean expired = cache == null || blockScanRefreshIntervalMillis <= 0
                || (now - cache.timestamp) >= blockScanRefreshIntervalMillis;
        if (forceRefresh || expired) {
            cache = recomputeBlockScanCache(city, now);
        }
        return cache;
    }

    private City.BlockScanCache recomputeBlockScanCache(City city, long now) {
        City.BlockScanCache cache = new City.BlockScanCache();
        cache.light = averageSurfaceLight(city);
        cache.nature = natureRatio(city);
        cache.pollution = pollutionRatio(city);
        cache.overcrowdingPenalty = computeOvercrowdingPenalty(city);
        cache.timestamp = now;
        city.blockScanCache = cache;
        return cache;
    }

    private double computeOvercrowdingPenalty(City city) {
        double effectiveArea = totalEffectiveArea(city);
        if (effectiveArea <= 0) {
            return 0.0;
        }
        int pop = Math.max(0, city.population);
        if (pop <= 0) {
            return 0.0;
        }
        double density = pop / (effectiveArea / 1000.0);
        double penalty = density * 0.5;
        if (penalty < 0.0) {
            penalty = 0.0;
        }
        if (penalty > overcrowdMaxPenalty) {
            penalty = overcrowdMaxPenalty;
        }
        return penalty;
    }

    public void invalidateBlockScanCache(City city) {
        if (city != null) {
            city.invalidateBlockScanCache();
        }
    }

    public City.BlockScanCache refreshBlockScanCache(City city) {
        if (city == null) {
            return null;
        }
        return ensureBlockScanCache(city, true);
    }

    private double totalEffectiveArea(City city) {
        long sum = 0;
        for (Cuboid c : city.cuboids) {
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            long area = width * length;
            if (city.highrise) {
                long height = (long) (c.maxY - c.minY + 1);
                if (height < 1) height = 1;
                area *= height;
            }
            sum += area;
        }
        return (double) sum;
    }

    private double averageSurfaceLight(City city) {
        int samples = 0, lightSum = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            int step = 8;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            lightSum += w.getBlockAt(x, y, z).getLightLevel();
                            samples++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            lightSum += w.getBlockAt(x, c.maxY, z).getLightLevel();
                            samples++;
                        }
                    } else {
                        int y = w.getHighestBlockYAt(x, z);
                        org.bukkit.block.Block top = w.getBlockAt(x, y, z);
                        if (top.isLiquid()) {
                            continue;
                        }
                        int light = top.getLightLevel();
                        lightSum += light;
                        samples++;
                    }
                }
            }
        }
        return samples == 0 ? lightNeutral : (double) lightSum / samples;
    }

    private interface BlockTest { boolean test(org.bukkit.block.Block b); }

    private double ratioSurface(City city, int step, BlockTest test) {
        int found = 0, probes = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                            if (test.test(b)) found++;
                            probes++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            org.bukkit.block.Block b = w.getBlockAt(x, c.maxY, z);
                            if (test.test(b)) found++;
                            probes++;
                        }
                    } else {
                        int y = w.getHighestBlockYAt(x, z);
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        if (test.test(b)) found++;
                        probes++;
                    }
                }
            }
        }
        return probes == 0 ? 0.0 : (double) found / (double) probes;
    }

    private double natureRatio(City city) {
        return ratioSurface(city, 6, b -> {
            org.bukkit.Material type = b.getType();
            if (org.bukkit.Tag.LOGS.isTagged(type) || org.bukkit.Tag.LEAVES.isTagged(type)) {
                return true;
            }
            return switch (type) {
                case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                     VINE, LILY_PAD,
                     DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                     OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER, PEONY, ROSE_BUSH -> true;
                default -> false;
            };
        });
    }

    private double pollutionRatio(City city) {
        return ratioSurface(city, 8, b -> switch (b.getType()) {
            case FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, SOUL_CAMPFIRE, LAVA, LAVA_CAULDRON -> true;
            default -> false;
        });
    }

    private int countBeds(City city) {
        int beds = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x++) {
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    for (int y = c.minY; y <= c.maxY; y++) {
                        switch (w.getBlockAt(x, y, z).getType()) {
                            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED, PINK_BED,
                                 GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> beds++;
                            default -> {}
                        }
                    }
                }
            }
        }
        return beds / 2;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public void updateConfig() {
        var c = plugin.getConfig();
        String mode = c.getString("employment.mode", "profession_only").toLowerCase();
        if ("profession_and_workstation".equals(mode)) {
            employmentMode = EmploymentMode.PROFESSION_AND_WORKSTATION;
        } else if ("workstation_proximity".equals(mode)) {
            employmentMode = EmploymentMode.WORKSTATION_PROXIMITY;
        } else {
            employmentMode = EmploymentMode.PROFESSION_ONLY;
        }
        wsRadius = Math.max(1, c.getInt("employment.workstation_radius", 16));
        wsYRadius = Math.max(1, c.getInt("employment.workstation_y_radius", 8));
        blockScanRefreshIntervalMillis = Math.max(0L, c.getLong("happiness.block_scan_refresh_interval_millis", 60000L));

        long configuredInterval = c.getLong("updates.stats_interval_ticks", DEFAULT_STATS_INTERVAL_TICKS);
        if (configuredInterval < MIN_STATS_INTERVAL_TICKS || configuredInterval > MAX_STATS_INTERVAL_TICKS) {
            plugin.getLogger().warning("updates.stats_interval_ticks out of range; using default interval of " + DEFAULT_STATS_INTERVAL_TICKS + " ticks.");
            configuredInterval = DEFAULT_STATS_INTERVAL_TICKS;
        }
        statsIntervalTicks = configuredInterval;

        long configuredDelay = c.getLong("updates.stats_initial_delay_ticks", DEFAULT_STATS_INITIAL_DELAY_TICKS);
        if (configuredDelay < 0L || configuredDelay > MAX_STATS_INITIAL_DELAY_TICKS) {
            plugin.getLogger().warning("updates.stats_initial_delay_ticks out of range; using default delay of " + DEFAULT_STATS_INITIAL_DELAY_TICKS + " ticks.");
            configuredDelay = DEFAULT_STATS_INITIAL_DELAY_TICKS;
        }
        statsInitialDelayTicks = configuredDelay;

        lightNeutral = Math.max(0.1, c.getDouble("happiness_weights.light_neutral_level", 2.0));
        lightMaxPts = c.getDouble("happiness_weights.light_max_points", 10);
        employmentMaxPts = c.getDouble("happiness_weights.employment_max_points", 15);
        overcrowdMaxPenalty = c.getDouble("happiness_weights.overcrowding_max_penalty", 10);
        natureMaxPts = c.getDouble("happiness_weights.nature_max_points", 10);
        pollutionMaxPenalty = c.getDouble("happiness_weights.pollution_max_penalty", 15);
        housingMaxPts = c.getDouble("happiness_weights.housing_max_points", 10);
    }

    private void scheduleTask() {
        if (!plugin.isEnabled()) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, statsInitialDelayTicks, statsIntervalTicks);
    }
}
