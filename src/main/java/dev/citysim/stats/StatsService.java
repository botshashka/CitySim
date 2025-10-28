
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

    private final Plugin plugin;
    private final CityManager cityManager;
    private int taskId = -1;

    private EmploymentMode employmentMode = EmploymentMode.PROFESSION_ONLY;
    private int wsRadius = 16;
    private int wsYRadius = 8;

    // Weights
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double golemsMaxPts = 10;
    private double overcrowdMaxPenalty = 10;
    private double jobDensityMaxPts = 10;
    private double natureMaxPts = 10;
    private double pollutionMaxPenalty = 10;
    private double bedsMaxPts = 10;
    private double waterMaxPts = 5;
    private double beautyMaxPts = 5;

    public StatsService(Plugin plugin, CityManager cm) {
        this.plugin = plugin;
        this.cityManager = cm;
        updateConfig();
    }

    public void start() {
        updateConfig();
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 40L, 100L);
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    private void tick() {
        for (City city : cityManager.all()) {
            updateCity(city);
        }
    }

    public void updateCity(City city) {
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

        int unemployed = Math.max(0, pop - employed);
        int happiness = computeHappiness(city, pop, employed, golems);

        city.population = pop;
        city.employed = employed;
        city.unemployed = unemployed;
        city.happiness = happiness;
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

    private int computeHappiness(City city, int pop, int employed, int golems) {
        HappinessBreakdown hb = computeHappinessBreakdown(city);
        return hb.total;
    }

    public HappinessBreakdown computeHappinessBreakdown(City city) {
        int pop = city.population;
        int employed = city.employed;
        int golems = 0;

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
                        if (e instanceof IronGolem && c.contains(e.getLocation())) golems++;
                    }
                }
            }
        }

        HappinessBreakdown hb = new HappinessBreakdown();

        double lightScore = averageSurfaceLight(city); // current-time light
        hb.lightPoints = map(lightScore, 0, 15, 0, lightMaxPts);

        double employmentRate = pop <= 0 ? 0.0 : (double) employed / (double) pop;
        hb.employmentPoints = employmentRate * employmentMaxPts;

        hb.golemPoints = Math.min(golemsMaxPts, (golems / Math.max(1.0, (pop / 10.0))) * golemsMaxPts);

        double area2D = totalArea2D(city);
        double density = area2D <= 0 ? 0 : pop / (area2D / 1000.0);
        hb.overcrowdingPenalty = Math.min(overcrowdMaxPenalty, density * 0.5);

        hb.jobDensityPoints = Math.min(jobDensityMaxPts, sampleWorkDensity(city) * jobDensityMaxPts * 0.2);

        double nature = natureRatio(city);
        hb.naturePoints = Math.min(natureMaxPts, nature * natureMaxPts * 1.0);

        double pollution = pollutionRatio(city);
        hb.pollutionPenalty = Math.min(pollutionMaxPenalty, pollution * pollutionMaxPenalty * 2.0);

        int beds = countBeds(city);
        double bedRatio = pop <= 0 ? 1.0 : Math.min(2.0, (double) beds / Math.max(1.0, (double) pop)); // 0..2
        hb.bedsPoints = (bedRatio - 1.0) * bedsMaxPts; // -max..+max

        double water = waterRatio(city);
        hb.waterPoints = Math.min(waterMaxPts, water * waterMaxPts * 1.0);

        double beauty = beautyRatio(city);
        hb.beautyPoints = Math.min(beautyMaxPts, beauty * beautyMaxPts * 1.0);

        double total = hb.base
                + hb.lightPoints
                + hb.employmentPoints
                + hb.golemPoints
                - hb.overcrowdingPenalty
                + hb.jobDensityPoints
                + hb.naturePoints
                - hb.pollutionPenalty
                + hb.bedsPoints
                + hb.waterPoints
                + hb.beautyPoints;

        if (total < 0) total = 0;
        if (total > 100) total = 100;
        hb.total = (int)Math.round(total);
        return hb;
    }

    private double totalArea2D(City city) {
        long sum = 0;
        for (Cuboid c : city.cuboids) sum += (long)(c.maxX - c.minX + 1) * (long)(c.maxZ - c.minZ + 1);
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
                    int y = w.getHighestBlockYAt(x, z);
                    int light = w.getBlockAt(x, y, z).getLightLevel();
                    lightSum += light;
                    samples++;
                }
            }
        }
        return samples == 0 ? 7.5 : (double) lightSum / samples;
    }

    private double sampleWorkDensity(City city) {
        int found = 0, probes = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            int step = 8;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    int y = w.getHighestBlockYAt(x, z);
                    if (Workstations.JOB_BLOCKS.contains(w.getBlockAt(x, y, z).getType())) found++;
                    probes++;
                }
            }
        }
        return probes == 0 ? 0 : (double) found / (double) probes;
    }

    private interface BlockTest { boolean test(org.bukkit.block.Block b); }

    private double ratioSurface(City city, int step, BlockTest test) {
        int found = 0, probes = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    int y = w.getHighestBlockYAt(x, z);
                    org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                    if (test.test(b)) found++;
                    probes++;
                }
            }
        }
        return probes == 0 ? 0.0 : (double) found / (double) probes;
    }

    private double natureRatio(City city) {
        return ratioSurface(city, 6, b -> switch (b.getType()) {
            case GRASS_BLOCK, SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                 OAK_LEAVES, BIRCH_LEAVES, SPRUCE_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES, DARK_OAK_LEAVES,
                 AZALEA_LEAVES, FLOWERING_AZALEA_LEAVES, VINE, LILY_PAD,
                 DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                 OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER, PEONY, ROSE_BUSH -> true;
            default -> false;
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
            for (int x = c.minX; x <= c.maxX; x += 3) {
                for (int z = c.minZ; z <= c.maxZ; z += 3) {
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
        return beds;
    }

    private double waterRatio(City city) {
        return ratioSurface(city, 6, b -> switch (b.getType()) {
            case WATER, WATER_CAULDRON -> true;
            default -> false;
        });
    }

    private double beautyRatio(City city) {
        return ratioSurface(city, 6, b -> switch (b.getType()) {
            case LANTERN, SOUL_LANTERN, FLOWER_POT, TORCH, SOUL_TORCH, CANDLE, CANDLE_CAKE -> true;
            default -> false;
        });
    }

    private static double map(double v, double inMin, double inMax, double outMin, double outMax) {
        double t = (v - inMin) / (inMax - inMin);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return outMin + t * (outMax - outMin);
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

        lightMaxPts = c.getDouble("happiness_weights.light_max_points", 10);
        employmentMaxPts = c.getDouble("happiness_weights.employment_max_points", 15);
        golemsMaxPts = c.getDouble("happiness_weights.golems_max_points", 10);
        overcrowdMaxPenalty = c.getDouble("happiness_weights.overcrowding_max_penalty", 10);
        jobDensityMaxPts = c.getDouble("happiness_weights.job_density_max_points", 10);
        natureMaxPts = c.getDouble("happiness_weights.nature_max_points", 10);
        pollutionMaxPenalty = c.getDouble("happiness_weights.pollution_max_penalty", 10);
        bedsMaxPts = c.getDouble("happiness_weights.beds_max_points", 10);
        waterMaxPts = c.getDouble("happiness_weights.water_max_points", 5);
        beautyMaxPts = c.getDouble("happiness_weights.beauty_max_points", 5);
    }
}
