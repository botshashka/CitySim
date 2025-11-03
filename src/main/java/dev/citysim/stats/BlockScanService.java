package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Provides reusable logic for sampling block-level metrics that feed into the happiness calculation.
 */
public class BlockScanService {
    private static final int HIGHRISE_VERTICAL_STEP = 4;
    private static final long DEFAULT_BLOCK_SCAN_REFRESH_INTERVAL_MILLIS = 60000L;

    private final HappinessCalculator happinessCalculator;
    private long blockScanRefreshIntervalMillis = DEFAULT_BLOCK_SCAN_REFRESH_INTERVAL_MILLIS;

    public BlockScanService(HappinessCalculator happinessCalculator) {
        this.happinessCalculator = happinessCalculator;
    }

    public void updateConfig(FileConfiguration configuration) {
        if (configuration == null) {
            return;
        }
        long configured = Math.max(0L,
                configuration.getLong("happiness.block_scan_refresh_interval_millis",
                        DEFAULT_BLOCK_SCAN_REFRESH_INTERVAL_MILLIS));
        setBlockScanRefreshIntervalMillis(configured);
    }

    public void setBlockScanRefreshIntervalMillis(long intervalMillis) {
        blockScanRefreshIntervalMillis = Math.max(0L, intervalMillis);
    }

    public City.BlockScanCache refreshBlockScanCache(City city) {
        if (city == null) {
            return null;
        }
        return ensureBlockScanCache(city, true);
    }

    public City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
        if (city == null) {
            return null;
        }
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
        SampledRatio nature = natureRatio(city);
        cache.nature = nature.ratio();
        cache.natureSamples = nature.samples();
        PollutionStats pollutionStats = pollutionStats(city);
        cache.pollution = pollutionStats.ratio();
        cache.pollutingBlocks = pollutionStats.blockCount();
        cache.overcrowdingPenalty = happinessCalculator.computeOvercrowdingPenalty(city);
        cache.timestamp = now;
        city.blockScanCache = cache;
        return cache;
    }

    private double averageSurfaceLight(City city) {
        final int step = 8;
        if (!city.highrise) {
            Set<City.ChunkPosition> residentialChunks = city.getResidentialChunks();
            if (residentialChunks != null && !residentialChunks.isEmpty()) {
                int residentialSamples = 0;
                int residentialLightSum = 0;
                for (City.ChunkPosition chunkPos : residentialChunks) {
                    World world = Bukkit.getWorld(chunkPos.world());
                    if (world == null) {
                        continue;
                    }
                    Set<Long> sampledColumns = new HashSet<>();
                    int chunkMinX = chunkPos.x() << 4;
                    int chunkMaxX = chunkMinX + 15;
                    int chunkMinZ = chunkPos.z() << 4;
                    int chunkMaxZ = chunkMinZ + 15;
                    for (Cuboid cuboid : city.cuboids) {
                        if (cuboid == null || cuboid.world == null || !cuboid.world.equals(chunkPos.world())) {
                            continue;
                        }
                        int minX = Math.max(cuboid.minX, chunkMinX);
                        int maxX = Math.min(cuboid.maxX, chunkMaxX);
                        int minZ = Math.max(cuboid.minZ, chunkMinZ);
                        int maxZ = Math.min(cuboid.maxZ, chunkMaxZ);
                        if (minX > maxX || minZ > maxZ) {
                            continue;
                        }
                        for (int x = minX; x <= maxX; x += step) {
                            for (int z = minZ; z <= maxZ; z += step) {
                                long columnKey = (((long) x) << 32) | (z & 0xffffffffL);
                                if (!sampledColumns.add(columnKey)) {
                                    continue;
                                }
                                Integer blockLight = sampleSurfaceColumnBlockLight(world, x, z);
                                if (blockLight == null) {
                                    continue;
                                }
                                residentialLightSum += blockLight;
                                residentialSamples++;
                            }
                        }
                    }
                }
                if (residentialSamples > 0) {
                    return (double) residentialLightSum / residentialSamples;
                }
            }
        }

        int samples = 0;
        int lightSum = 0;
        if (city.cuboids == null) {
            return happinessCalculator.getLightNeutral();
        }
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            lightSum += w.getBlockAt(x, y, z).getLightFromBlocks();
                            samples++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            lightSum += w.getBlockAt(x, c.maxY, z).getLightFromBlocks();
                            samples++;
                        }
                    } else {
                        Integer blockLight = sampleSurfaceColumnBlockLight(w, x, z);
                        if (blockLight == null) {
                            continue;
                        }
                        lightSum += blockLight;
                        samples++;
                    }
                }
            }
        }
        return samples == 0 ? happinessCalculator.getLightNeutral() : (double) lightSum / samples;
    }

    private SampledRatio natureRatio(City city) {
        BlockTest natureTest = b -> {
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
        };

        if (city.highrise) {
            return ratioHighriseColumns(city, 6, natureTest);
        }

        return ratioSurface(city, 6, natureTest);
    }

    private PollutionStats pollutionStats(City city) {
        SurfaceSampleResult result = sampleSurface(city, 8, b -> switch (b.getType()) {
            case FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, SOUL_CAMPFIRE, LAVA, LAVA_CAULDRON -> true;
            default -> false;
        });
        return new PollutionStats(result.ratio(), result.found);
    }

    private SampledRatio ratioSurface(City city, int step, BlockTest test) {
        SurfaceSampleResult result = sampleSurface(city, step, test);
        return new SampledRatio(result.ratio(), result.probes);
    }

    private SampledRatio ratioHighriseColumns(City city, int step, BlockTest test) {
        Map<String, Map<Long, ColumnSample>> columns = new HashMap<>();
        if (city.cuboids == null) {
            return new SampledRatio(0.0, 0);
        }
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    Map<Long, ColumnSample> worldColumns = columns.computeIfAbsent(c.world, k -> new HashMap<>());
                    long columnKey = (((long) x) << 32) ^ (z & 0xffffffffL);
                    ColumnSample column = worldColumns.computeIfAbsent(columnKey, k -> new ColumnSample());
                    if (column.matched) {
                        column.sampled = true;
                        continue;
                    }
                    boolean sampled = false;
                    for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                        Block block = w.getBlockAt(x, y, z);
                        sampled = true;
                        if (test.test(block)) {
                            column.matched = true;
                            break;
                        }
                    }
                    if (!column.matched && (c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                        Block block = w.getBlockAt(x, c.maxY, z);
                        sampled = true;
                        if (test.test(block)) {
                            column.matched = true;
                        }
                    }
                    if (sampled) {
                        column.sampled = true;
                    }
                }
            }
        }
        int totalColumns = 0;
        int columnsWithMatch = 0;
        for (Map<Long, ColumnSample> worldColumns : columns.values()) {
            for (ColumnSample column : worldColumns.values()) {
                if (!column.sampled) continue;
                totalColumns++;
                if (column.matched) {
                    columnsWithMatch++;
                }
            }
        }
        double ratio = totalColumns == 0 ? 0.0 : (double) columnsWithMatch / totalColumns;
        return new SampledRatio(ratio, totalColumns);
    }

    private SurfaceSampleResult sampleSurface(City city, int step, BlockTest test) {
        int found = 0, probes = 0;
        if (city.cuboids == null) {
            return new SurfaceSampleResult(0, 0);
        }
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    if (city.highrise) {
                        for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                            Block block = w.getBlockAt(x, y, z);
                            if (test.test(block)) found++;
                            probes++;
                        }
                        if ((c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                            Block block = w.getBlockAt(x, c.maxY, z);
                            if (test.test(block)) found++;
                            probes++;
                        }
                    } else {
                        int y = w.getHighestBlockYAt(x, z);
                        Block block = w.getBlockAt(x, y, z);
                        if (test.test(block)) found++;
                        probes++;
                    }
                }
            }
        }
        return new SurfaceSampleResult(found, probes);
    }

    private Integer sampleSurfaceColumnBlockLight(World world, int x, int z) {
        if (world == null) {
            return null;
        }

        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY < world.getMinHeight()) {
            return null;
        }

        Block surfaceBlock = world.getBlockAt(x, highestY, z);
        if (surfaceBlock.isLiquid()) {
            return null;
        }

        int maxHeight = world.getMaxHeight();
        int sampleStartY = highestY + 1;
        if (sampleStartY >= maxHeight) {
            return (int) surfaceBlock.getLightFromBlocks();
        }

        Block sampleBlock = null;
        for (int y = sampleStartY; y < maxHeight; y++) {
            Block candidate = world.getBlockAt(x, y, z);
            if (candidate.getType().isAir()) {
                sampleBlock = candidate;
                break;
            }
        }

        if (sampleBlock == null) {
            sampleBlock = surfaceBlock;
        }

        return (int) sampleBlock.getLightFromBlocks();
    }

    private interface BlockTest {
        boolean test(Block block);
    }

    private static class SurfaceSampleResult {
        final int found;
        final int probes;

        SurfaceSampleResult(int found, int probes) {
            this.found = found;
            this.probes = probes;
        }

        double ratio() {
            return probes == 0 ? 0.0 : (double) found / (double) probes;
        }
    }

    private static class ColumnSample {
        boolean sampled;
        boolean matched;
    }

    private record SampledRatio(double ratio, int samples) {
    }

    private record PollutionStats(double ratio, int blockCount) {
    }
}
