
package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final double OVERCROWDING_BASELINE = 3.0;
    private static final double TRANSIT_BLOCKS_PER_STATION = 125.0;
    private static final double TRANSIT_SHORTAGE_BUFFER = 0.75;
    private static final double TRANSIT_SURPLUS_BUFFER = 0.5;

    private double lightNeutral = 2.0;
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double overcrowdMaxPenalty = 10;
    private double natureMaxPts = 10;
    private double pollutionMaxPenalty = 15;
    private double housingMaxPts = 10;
    private double transitMaxPts = 5;

    private final Map<String, Boolean> pendingCityUpdates = new LinkedHashMap<>();
    private final Deque<String> scheduledCityQueue = new ArrayDeque<>();
    private final Map<String, CityScanJob> activeCityJobs = new LinkedHashMap<>();
    private int maxCitiesPerTick = 1;
    private int maxEntityChunksPerTick = 2;
    private int maxBedBlocksPerTick = 2048;

    public StatsService(Plugin plugin, CityManager cm) {
        this.plugin = plugin;
        this.cityManager = cm;
        updateConfig();
    }

    public void start() {
        updateConfig();
        if (taskId != -1) return;
        pendingCityUpdates.clear();
        scheduledCityQueue.clear();
        queueAllCitiesForInitialScan();
        scheduleTask();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        pendingCityUpdates.clear();
        scheduledCityQueue.clear();
    }

    public void restartTask() {
        updateConfig();
        stop();
        queueAllCitiesForInitialScan();
        scheduleTask();
    }

    public void requestCityUpdate(City city) {
        requestCityUpdate(city, false);
    }

    public void requestCityUpdate(City city, boolean forceRefresh) {
        if (city == null || city.id == null) {
            return;
        }
        addPendingCity(city.id, forceRefresh);
    }

    public void requestCityUpdate(Location location) {
        requestCityUpdate(location, false);
    }

    public void requestCityUpdate(Location location, boolean forceRefresh) {
        if (location == null) {
            return;
        }
        City city = cityManager.cityAt(location);
        if (city != null) {
            requestCityUpdate(city, forceRefresh);
        }
    }

    private void addPendingCity(String cityId, boolean forceRefresh) {
        if (cityId == null || cityId.isEmpty()) {
            return;
        }
        CityScanJob activeJob = activeCityJobs.get(cityId);
        if (activeJob != null) {
            activeJob.requestRequeue(forceRefresh);
            return;
        }
        pendingCityUpdates.merge(cityId, forceRefresh, (existing, incoming) -> existing || incoming);
        scheduledCityQueue.remove(cityId);
    }

    private void tick() {
        progressActiveJobs();
        int processed = 0;
        int target = Math.max(1, maxCitiesPerTick);
        while (processed < target) {
            boolean started = processNextPendingCity();
            if (!started) {
                started = processNextScheduledCity();
            }
            if (!started) {
                break;
            }
            processed++;
        }
    }

    private boolean processNextPendingCity() {
        if (pendingCityUpdates.isEmpty()) {
            return false;
        }
        var iterator = pendingCityUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            City city = cityManager.get(entry.getKey());
            if (city == null) {
                continue;
            }
            if (startCityScanJob(city, entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean processNextScheduledCity() {
        while (true) {
            if (scheduledCityQueue.isEmpty()) {
                refillScheduledQueue();
                if (scheduledCityQueue.isEmpty()) {
                    return false;
                }
            }
            String cityId = scheduledCityQueue.pollFirst();
            if (cityId == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(cityId)) {
                continue;
            }
            City city = cityManager.get(cityId);
            if (city == null) {
                continue;
            }
            if (startCityScanJob(city, false)) {
                return true;
            }
        }
    }

    private void progressActiveJobs() {
        if (activeCityJobs.isEmpty()) {
            return;
        }
        int jobsToProcess = Math.max(1, maxCitiesPerTick);
        var iterator = activeCityJobs.entrySet().iterator();
        List<CityScanJob> toRequeue = new ArrayList<>();
        List<CityScanJob> completed = new ArrayList<>();
        while (iterator.hasNext() && jobsToProcess > 0) {
            Map.Entry<String, CityScanJob> entry = iterator.next();
            iterator.remove();
            CityScanJob job = entry.getValue();
            if (job.isCancelled()) {
                jobsToProcess--;
                continue;
            }
            boolean done = job.process(maxEntityChunksPerTick, maxBedBlocksPerTick);
            if (done) {
                completed.add(job);
            } else {
                toRequeue.add(job);
            }
            jobsToProcess--;
        }
        for (CityScanJob job : toRequeue) {
            activeCityJobs.put(job.cityId(), job);
        }
        for (CityScanJob job : completed) {
            RerunRequest rerun = job.consumeRerunRequest();
            if (rerun.requested()) {
                addPendingCity(job.cityId(), rerun.forceRefresh());
            }
        }
    }

    private boolean startCityScanJob(City city, boolean forceRefresh) {
        if (city == null || city.id == null || city.id.isEmpty()) {
            return false;
        }
        CityScanJob existing = activeCityJobs.get(city.id);
        if (existing != null) {
            existing.requestRequeue(forceRefresh);
            return false;
        }
        CityScanJob job = new CityScanJob(city, forceRefresh);
        activeCityJobs.put(city.id, job);
        return true;
    }

    private void refillScheduledQueue() {
        scheduledCityQueue.clear();
        List<City> cities = new ArrayList<>(cityManager.all());
        cities.sort(Comparator.comparingInt(c -> c.priority));
        for (City city : cities) {
            if (city == null || city.id == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(city.id)) {
                continue;
            }
            scheduledCityQueue.addLast(city.id);
        }
    }

    private void queueAllCitiesForInitialScan() {
        for (City city : cityManager.all()) {
            requestCityUpdate(city, true);
        }
    }

    public HappinessBreakdown updateCity(City city) {
        return updateCity(city, false);
    }

    public HappinessBreakdown updateCity(City city, boolean forceRefresh) {
        if (city == null) {
            return new HappinessBreakdown();
        }
        cancelActiveJob(city);
        CityScanJob job = new CityScanJob(city, forceRefresh);
        while (!job.process(Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            // Keep processing until the scan completes synchronously
        }
        HappinessBreakdown result = job.getResult();
        if (result == null) {
            result = city.happinessBreakdown != null ? city.happinessBreakdown : new HappinessBreakdown();
        }
        return result;
    }

    private void cancelActiveJob(City city) {
        if (city == null || city.id == null) {
            return;
        }
        CityScanJob running = activeCityJobs.remove(city.id);
        if (running != null) {
            running.cancel();
        }
        pendingCityUpdates.remove(city.id);
        scheduledCityQueue.remove(city.id);
    }

    private record ChunkCoord(String world, int x, int z) {}

    private record RerunRequest(boolean requested, boolean forceRefresh) {}

    private final class CityScanJob {
        private final City city;
        private boolean forceRefresh;
        private final List<ChunkCoord> entityChunks;
        private int entityChunkIndex = 0;

        private int population = 0;
        private int employed = 0;

        private int bedHalfCount = 0;
        private int beds = 0;
        private int bedCuboidIndex = 0;
        private int bedX;
        private int bedY;
        private int bedZ;
        private boolean bedInitialized = false;

        private Stage stage = Stage.ENTITY_SCAN;
        private boolean cancelled = false;
        private HappinessBreakdown result = null;

        private boolean rerunRequested = false;
        private boolean rerunForceRefresh = false;

        CityScanJob(City city, boolean forceRefresh) {
            this.city = city;
            this.forceRefresh = forceRefresh;
            this.entityChunks = buildChunkList(city);
        }

        boolean process(int chunkLimit, int bedLimit) {
            if (cancelled) {
                stage = Stage.COMPLETE;
                return true;
            }
            if (stage == Stage.ENTITY_SCAN) {
                if (!processEntityStage(chunkLimit)) {
                    return false;
                }
                stage = Stage.BEDS;
            }
            if (stage == Stage.BEDS) {
                if (!processBedStage(bedLimit)) {
                    return false;
                }
                stage = Stage.BLOCK_CACHE;
            }
            if (stage == Stage.BLOCK_CACHE) {
                finalizeCity();
                stage = Stage.COMPLETE;
            }
            return stage == Stage.COMPLETE;
        }

        private boolean processEntityStage(int chunkLimit) {
            if (entityChunkIndex >= entityChunks.size()) {
                return true;
            }
            int limit = chunkLimit <= 0 ? Integer.MAX_VALUE : chunkLimit;
            int processed = 0;
            while (entityChunkIndex < entityChunks.size() && processed < limit) {
                ChunkCoord coord = entityChunks.get(entityChunkIndex++);
                World world = Bukkit.getWorld(coord.world());
                if (world == null) {
                    processed++;
                    continue;
                }
                if (!world.isChunkLoaded(coord.x(), coord.z())) {
                    processed++;
                    continue;
                }
                Chunk chunk = world.getChunkAt(coord.x(), coord.z());
                for (Entity entity : chunk.getEntities()) {
                    Location loc = entity.getLocation();
                    if (!city.contains(loc)) {
                        continue;
                    }
                    if (entity instanceof Villager villager) {
                        population++;
                        Villager.Profession prof = villager.getProfession();
                        boolean isNitwit = prof == Villager.Profession.NITWIT;
                        boolean hasProf = prof != Villager.Profession.NONE && !isNitwit;
                        boolean nearWork = hasNearbyWorkstation(loc, wsRadius, wsYRadius);
                        boolean employedNow;
                        switch (employmentMode) {
                            case PROFESSION_ONLY -> employedNow = hasProf;
                            case WORKSTATION_PROXIMITY -> employedNow = nearWork;
                            case PROFESSION_AND_WORKSTATION -> employedNow = hasProf && nearWork;
                            default -> employedNow = hasProf;
                        }
                        if (employedNow) {
                            employed++;
                        }
                    }
                }
                processed++;
            }
            return entityChunkIndex >= entityChunks.size();
        }

        private boolean processBedStage(int bedLimit) {
            if (bedCuboidIndex >= city.cuboids.size()) {
                beds = bedHalfCount / 2;
                return true;
            }
            int limit = bedLimit <= 0 ? Integer.MAX_VALUE : bedLimit;
            int processed = 0;
            while (bedCuboidIndex < city.cuboids.size() && processed < limit) {
                Cuboid cuboid = city.cuboids.get(bedCuboidIndex);
                World world = Bukkit.getWorld(cuboid.world);
                if (world == null) {
                    bedCuboidIndex++;
                    bedInitialized = false;
                    continue;
                }
                if (!bedInitialized) {
                    bedX = cuboid.minX;
                    bedY = cuboid.minY;
                    bedZ = cuboid.minZ;
                    bedInitialized = true;
                }
                while (bedCuboidIndex < city.cuboids.size() && processed < limit) {
                    Material type = world.getBlockAt(bedX, bedY, bedZ).getType();
                    if (isBed(type)) {
                        bedHalfCount++;
                    }
                    processed++;
                    if (!advanceBedCursor(cuboid)) {
                        bedCuboidIndex++;
                        bedInitialized = false;
                        break;
                    }
                }
            }
            if (bedCuboidIndex >= city.cuboids.size()) {
                beds = bedHalfCount / 2;
                return true;
            }
            return false;
        }

        private boolean advanceBedCursor(Cuboid cuboid) {
            bedY++;
            if (bedY > cuboid.maxY) {
                bedY = cuboid.minY;
                bedZ++;
                if (bedZ > cuboid.maxZ) {
                    bedZ = cuboid.minZ;
                    bedX++;
                    if (bedX > cuboid.maxX) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void finalizeCity() {
            int unemployed = Math.max(0, population - employed);
            city.population = population;
            city.employed = employed;
            city.unemployed = unemployed;
            city.beds = beds;
            City.BlockScanCache metrics = ensureBlockScanCache(city, forceRefresh);
            result = calculateHappinessBreakdown(city, metrics);
            city.happinessBreakdown = result;
            city.happiness = result.total;
        }

        HappinessBreakdown getResult() {
            return result;
        }

        String cityId() {
            return city.id;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }

        void requestRequeue(boolean force) {
            rerunRequested = true;
            rerunForceRefresh = rerunForceRefresh || force;
            forceRefresh = forceRefresh || force;
        }

        RerunRequest consumeRerunRequest() {
            if (!rerunRequested) {
                return new RerunRequest(false, false);
            }
            RerunRequest req = new RerunRequest(true, rerunForceRefresh);
            rerunRequested = false;
            rerunForceRefresh = false;
            return req;
        }

        private List<ChunkCoord> buildChunkList(City city) {
            Set<ChunkCoord> coords = new LinkedHashSet<>();
            for (Cuboid cuboid : city.cuboids) {
                if (cuboid == null || cuboid.world == null) {
                    continue;
                }
                int minCX = cuboid.minX >> 4;
                int maxCX = cuboid.maxX >> 4;
                int minCZ = cuboid.minZ >> 4;
                int maxCZ = cuboid.maxZ >> 4;
                for (int cx = minCX; cx <= maxCX; cx++) {
                    for (int cz = minCZ; cz <= maxCZ; cz++) {
                        coords.add(new ChunkCoord(cuboid.world, cx, cz));
                    }
                }
            }
            return new ArrayList<>(coords);
        }

        private enum Stage { ENTITY_SCAN, BEDS, BLOCK_CACHE, COMPLETE }
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
        if (city == null) {
            return new HappinessBreakdown();
        }
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
        requestCityUpdate(city, true);
        return city.happinessBreakdown != null ? city.happinessBreakdown : new HappinessBreakdown();
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
        double pollutionTarget = 0.02;
        if (metrics.pollutingBlocks < 4) {
            hb.pollutionPenalty = 0.0;
        } else {
            double pollutionSeverity = Math.max(0.0, (pollution - pollutionTarget) / pollutionTarget);
            hb.pollutionPenalty = clamp(pollutionSeverity * pollutionMaxPenalty, 0.0, pollutionMaxPenalty);
        }

        int beds = city.beds;
        double housingRatio = pop <= 0 ? 1.0 : Math.min(2.0, (double) beds / Math.max(1.0, (double) pop));
        hb.housingPoints = clamp((housingRatio - 1.0) * housingMaxPts, -housingMaxPts, housingMaxPts);

        hb.transitPoints = computeTransitPoints(city);

        double total = hb.base
                + hb.lightPoints
                + hb.employmentPoints
                - hb.overcrowdingPenalty
                + hb.naturePoints
                - hb.pollutionPenalty
                + hb.housingPoints
                + hb.transitPoints;

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
        PollutionStats pollutionStats = pollutionStats(city);
        cache.pollution = pollutionStats.ratio();
        cache.pollutingBlocks = pollutionStats.blockCount();
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
        double penalty = density * 0.5 - OVERCROWDING_BASELINE;
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

    private double totalFootprintArea(City city) {
        long sum = 0;
        for (Cuboid c : city.cuboids) {
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            if (width < 0) width = 0;
            if (length < 0) length = 0;
            sum += width * length;
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

    private double ratioSurface(City city, int step, BlockTest test) {
        return sampleSurface(city, step, test).ratio();
    }

    private double ratioHighriseColumns(City city, int step, BlockTest test) {
        int columnsWithMatch = 0;
        int totalColumns = 0;
        for (Cuboid c : city.cuboids) {
            World w = Bukkit.getWorld(c.world);
            if (w == null) continue;
            for (int x = c.minX; x <= c.maxX; x += step) {
                for (int z = c.minZ; z <= c.maxZ; z += step) {
                    boolean sampled = false;
                    boolean columnMatched = false;
                    for (int y = c.minY; y <= c.maxY; y += HIGHRISE_VERTICAL_STEP) {
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        sampled = true;
                        if (test.test(b)) {
                            columnMatched = true;
                            break;
                        }
                    }
                    if (!columnMatched && (c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                        org.bukkit.block.Block b = w.getBlockAt(x, c.maxY, z);
                        sampled = true;
                        if (test.test(b)) {
                            columnMatched = true;
                        }
                    }
                    if (sampled) {
                        totalColumns++;
                        if (columnMatched) {
                            columnsWithMatch++;
                        }
                    }
                }
            }
        }
        return totalColumns == 0 ? 0.0 : (double) columnsWithMatch / totalColumns;
    }

    private SurfaceSampleResult sampleSurface(City city, int step, BlockTest test) {
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
        return new SurfaceSampleResult(found, probes);
    }

    private double natureRatio(City city) {
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

    private record PollutionStats(double ratio, int blockCount) {}

    private PollutionStats pollutionStats(City city) {
        SurfaceSampleResult result = sampleSurface(city, 8, b -> switch (b.getType()) {
            case FURNACE, BLAST_FURNACE, SMOKER, CAMPFIRE, SOUL_CAMPFIRE, LAVA, LAVA_CAULDRON -> true;
            default -> false;
        });
        return new PollutionStats(result.ratio(), result.found);
    }

    private static boolean isBed(Material type) {
        return switch (type) {
            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED, PINK_BED,
                 GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> true;
            default -> false;
        };
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private double computeTransitPoints(City city) {
        double area = totalFootprintArea(city);
        if (area <= 0.0 || transitMaxPts <= 0.0) {
            return 0.0;
        }

        double requiredStations = area / TRANSIT_BLOCKS_PER_STATION;
        if (requiredStations <= 0.0) {
            return 0.0;
        }

        double actualStations = Math.max(0, city.stations);
        if (actualStations <= 0.0) {
            return -transitMaxPts;
        }

        double densityRatio = actualStations / requiredStations;
        double score;
        if (densityRatio >= 1.0) {
            double surplus = densityRatio - 1.0;
            score = transitMaxPts * (surplus / (surplus + TRANSIT_SURPLUS_BUFFER));
        } else {
            double shortage = 1.0 - densityRatio;
            score = -transitMaxPts * (shortage / (shortage + TRANSIT_SHORTAGE_BUFFER));
        }
        return clamp(score, -transitMaxPts, transitMaxPts);
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

        maxCitiesPerTick = Math.max(1, c.getInt("updates.max_cities_per_tick", 1));
        maxEntityChunksPerTick = Math.max(1, c.getInt("updates.max_entity_chunks_per_tick", 2));
        maxBedBlocksPerTick = Math.max(1, c.getInt("updates.max_bed_blocks_per_tick", 2048));

        lightNeutral = Math.max(0.1, c.getDouble("happiness_weights.light_neutral_level", 2.0));
        lightMaxPts = c.getDouble("happiness_weights.light_max_points", 10);
        employmentMaxPts = c.getDouble("happiness_weights.employment_max_points", 15);
        overcrowdMaxPenalty = c.getDouble("happiness_weights.overcrowding_max_penalty", 10);
        natureMaxPts = c.getDouble("happiness_weights.nature_max_points", 10);
        pollutionMaxPenalty = c.getDouble("happiness_weights.pollution_max_penalty", 15);
        housingMaxPts = c.getDouble("happiness_weights.housing_max_points", 10);
        transitMaxPts = Math.max(0.0, c.getDouble("happiness_weights.transit_max_points", 5));
    }

    private void scheduleTask() {
        if (!plugin.isEnabled()) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, statsInitialDelayTicks, statsIntervalTicks);
    }
}
