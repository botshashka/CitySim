package dev.citysim.stats.scan;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.StationCountResult;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CityScanJob {
    private static final long BED_CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(2);
    private static final int MAX_EXPIRED_CHUNKS_PER_JOB = 4;
    private final City city;
    private boolean forceRefresh;
    private boolean forceChunkLoad;
    private final String reason;
    private final ScanContext context;
    private final List<ChunkCoord> entityChunks;
    private int entityChunkIndex = 0;

    private int population = 0;
    private int employed = 0;
    private final Map<Profession, Integer> professionHistogram = new HashMap<>();
    private int adultPopulation = 0;
    private int adultNoneCount = 0;
    private int adultNitwitCount = 0;

    private int bedHalfCount = 0;
    private int beds = 0;
    private final Set<City.ChunkPosition> residentialBedChunks = new LinkedHashSet<>();
    private final List<BedChunkTask> bedTasks;
    private int bedTaskIndex = 0;
    private final Map<City.ChunkPosition, ChunkTracker> chunkTrackers = new LinkedHashMap<>();
    private final boolean synchronous;
    private final Set<City.ChunkPosition> cachedChunks = new LinkedHashSet<>();
    private long totalBedWorkUnits;
    private long completedBedWorkUnits;
    private long cachedBedChunks = 0L;
    private int deferredDirtyChunks = 0;
    private int expiredChunksScheduled = 0;

    private Stage stage = Stage.ENTITY_SCAN;
    private boolean cancelled = false;
    private HappinessBreakdown result = null;

    private boolean rerunRequested = false;
    private boolean rerunForceRefresh = false;
    private boolean rerunForceChunkLoad = false;
    private String rerunReason = null;
    private ScanContext rerunContext = null;

    private final long startedAtMillis = System.currentTimeMillis();
    private boolean startLogged = false;
    private StationCountResult trainCartsStationCount = null;

    private final Set<ChunkCoord> chunksLoadedByJob = new LinkedHashSet<>();

    private final CityScanCallbacks callbacks;
    private final ScanDebugManager debugManager;

    private int entityChunksProcessed = 0;
    private int bedBlocksProcessed = 0;
    private ScanWorkload workloadSnapshot = ScanWorkload.EMPTY;
    private final Set<UUID> countedVillagers = new HashSet<>();

    public CityScanJob(City city, ScanRequest request, CityScanCallbacks callbacks, ScanDebugManager debugManager, boolean synchronous) {
        this.city = city;
        boolean refresh = request != null && request.forceRefresh();
        this.forceRefresh = refresh;
        this.forceChunkLoad = request != null && request.forceChunkLoad();
        this.reason = request != null ? request.reason() : null;
        this.context = request != null ? request.context() : null;
        this.callbacks = callbacks;
        this.debugManager = debugManager;
        this.synchronous = synchronous;
        this.entityChunks = buildChunkList(city);
        this.bedTasks = buildBedTasks(city);
    }

    public boolean process(int chunkLimit, int bedLimit) {
        if (cancelled) {
            stage = Stage.COMPLETE;
            return true;
        }
        logStartIfNeeded();
        if (stage == Stage.ENTITY_SCAN) {
            if (!processEntityStage(chunkLimit)) {
                return false;
            }
            markEntityScanComplete();
            stage = Stage.BEDS;
        }
        if (stage == Stage.BEDS) {
            if (!processBedStage(bedLimit)) {
                return false;
            }
            stage = Stage.BLOCK_CACHE;
            releaseAllLoadedChunks();
        }
        if (stage == Stage.BLOCK_CACHE) {
            releaseAllLoadedChunks();
            finalizeCity();
            stage = Stage.COMPLETE;
            workloadSnapshot = new ScanWorkload(
                    Math.max(1L, System.currentTimeMillis() - startedAtMillis),
                    entityChunksProcessed,
                    bedBlocksProcessed
            );
            if (debugManager.isEnabled()) {
                debugManager.logJobCompleted(this);
            }
        }
        return stage == Stage.COMPLETE;
    }

    private void logStartIfNeeded() {
        if (!startLogged && debugManager.isEnabled()) {
            startLogged = true;
            debugManager.logJobStarted(this);
        }
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
            boolean available = world.isChunkLoaded(coord.x(), coord.z());
            if (!available && forceChunkLoad) {
                available = ensureChunkAvailable(world, coord);
            }
            if (!available) {
                processed++;
                continue;
            }
            Chunk chunk = world.getChunkAt(coord.x(), coord.z());
            if (chunk == null) {
                processed++;
                continue;
            }
            Entity[] entities = chunk.getEntities();
            for (Entity entity : entities) {
                if (!(entity instanceof Villager villager)) {
                    continue;
                }
                if (!villager.isValid()) {
                    continue;
                }
                UUID uuid = villager.getUniqueId();
                if (uuid != null && !countedVillagers.add(uuid)) {
                    continue;
                }
                Profession profession = villager.getProfession();
                if (villager.isAdult()) {
                    adultPopulation++;
                    if (profession == Profession.NONE) {
                        adultNoneCount++;
                    } else if (profession == Profession.NITWIT) {
                        adultNitwitCount++;
                    }
                }
                population++;
                if (profession != Profession.NONE) {
                    employed++;
                    professionHistogram.merge(profession, 1, Integer::sum);
                }
            }
            processed++;
            entityChunksProcessed++;
        }
        boolean complete = entityChunkIndex >= entityChunks.size();
        if (complete) {
            countedVillagers.clear();
        }
        return complete;
    }

    private boolean processBedStage(int bedLimit) {
        if (bedTasks.isEmpty()) {
            beds = bedHalfCount / 2;
            return true;
        }
        if (synchronous) {
            while (bedTaskIndex < bedTasks.size()) {
                BedChunkTask task = bedTasks.get(bedTaskIndex);
                processBedTask(task, Integer.MAX_VALUE);
                if (task.isComplete()) {
                    bedTaskIndex++;
                } else {
                    break;
                }
            }
        } else {
            int limit = bedLimit <= 0 ? Integer.MAX_VALUE : bedLimit;
            int processed = 0;
            while (bedTaskIndex < bedTasks.size() && processed < limit) {
                BedChunkTask task = bedTasks.get(bedTaskIndex);
                int used = processBedTask(task, limit - processed);
                processed += used;
                if (task.isComplete()) {
                    bedTaskIndex++;
                } else {
                    break;
                }
            }
            if (bedTaskIndex < bedTasks.size()) {
                return false;
            }
        }
        beds = bedHalfCount / 2;
        return bedTaskIndex >= bedTasks.size();
    }

    private int processBedTask(BedChunkTask task, int budget) {
        if (task.isComplete() || budget <= 0) {
            return 0;
        }
        World world = Bukkit.getWorld(task.world());
        if (world == null) {
            task.markComplete();
            finalizeChunkSegment(task, false);
            return 0;
        }
        ChunkCoord coord = new ChunkCoord(task.world(), task.chunkX(), task.chunkZ());
        if (!ensureChunkAvailable(world, coord)) {
            task.markComplete();
            finalizeChunkSegment(task, false);
            return 0;
        }
        ChunkTracker tracker = task.tracker();
        ChunkSnapshot snapshot = tracker != null ? tracker.snapshot() : null;
        if (snapshot == null) {
            Chunk chunk = world.getChunkAt(task.chunkX(), task.chunkZ());
            snapshot = chunk.getChunkSnapshot(true, true, false);
            if (tracker != null) {
                tracker.setSnapshot(snapshot);
            }
        }
        int used = 0;
        while (!task.isComplete() && used < budget) {
            int localX = task.nextX() & 15;
            int localZ = task.nextZ() & 15;
            int y = task.nextY();
            if (y < 0 || y > 255) {
                task.recordProgress();
                task.advance();
                used++;
                bedBlocksProcessed++;
                continue;
            }
            Material type = snapshot.getBlockType(localX, y, localZ);
            if (isBed(type)) {
                bedHalfCount++;
                if (tracker != null) {
                    tracker.addBedHalf();
                }
                residentialBedChunks.add(task.chunkPosition());
            }
            used++;
            bedBlocksProcessed++;
            task.recordProgress();
            task.advance();
        }
        if (task.isComplete()) {
            finalizeChunkSegment(task, true);
        }
        return used;
    }

    private void finalizeChunkSegment(BedChunkTask task, boolean scanned) {
        ChunkTracker tracker = task.tracker();
        if (tracker == null) {
            return;
        }
        tracker.segmentCompleted();
        if (tracker.remainingSegments <= 0) {
            long now = System.currentTimeMillis();
            city.putBedSnapshot(task.chunkPosition(), tracker.bedHalves, now);
            if (tracker.bedHalves > 0) {
                residentialBedChunks.add(task.chunkPosition());
            } else {
                residentialBedChunks.remove(task.chunkPosition());
            }
            chunkTrackers.remove(task.chunkPosition());
            tracker.clearSnapshot();
        }
        completedBedWorkUnits++;
    }

    private void finalizeCity() {
        int unemployed = Math.max(0, population - employed);
        city.population = population;
        city.employed = employed;
        city.unemployed = unemployed;
        city.adultPopulation = adultPopulation;
        city.adultNone = adultNoneCount;
        city.adultNitwit = adultNitwitCount;
        city.beds = beds;
        city.setResidentialChunks(residentialBedChunks);
        updateSectorBreakdown();

        trainCartsStationCount = callbacks.refreshStationCount(city);

        City.BlockScanCache metrics = callbacks.ensureBlockScanCache(city, forceRefresh);
        result = callbacks.calculateHappinessBreakdown(city, metrics);
        city.happinessBreakdown = result;
        city.happiness = result.total;
    }

    private void markEntityScanComplete() {
        if (city == null) {
            return;
        }
        City.EntityScanCache cache = city.entityScanCache;
        if (cache == null) {
            cache = new City.EntityScanCache();
            city.entityScanCache = cache;
        }
        cache.timestamp = System.currentTimeMillis();
    }

    private void updateSectorBreakdown() {
        int agri = countForSector(Sector.AGRICULTURE);
        int industry = countForSector(Sector.INDUSTRY);
        int services = countForSector(Sector.SERVICES);
        int total = agri + industry + services;
        if (total <= 0) {
            city.sectorAgri = 0.0;
            city.sectorInd = 0.0;
            city.sectorServ = 0.0;
            return;
        }
        city.sectorAgri = (double) agri / (double) total;
        city.sectorInd = (double) industry / (double) total;
        city.sectorServ = (double) services / (double) total;
    }

    private int countForSector(Sector sector) {
        int sum = 0;
        for (Map.Entry<Profession, Integer> entry : professionHistogram.entrySet()) {
            if (sector == Sector.fromProfession(entry.getKey())) {
                sum += Math.max(0, entry.getValue());
            }
        }
        return sum;
    }

    private enum Sector {
        AGRICULTURE,
        INDUSTRY,
        SERVICES;

        static Sector fromProfession(Profession profession) {
            if (profession == null) {
                return SERVICES;
            }
            if (profession == Profession.FARMER
                    || profession == Profession.FISHERMAN
                    || profession == Profession.SHEPHERD) {
                return AGRICULTURE;
            }
            if (profession == Profession.ARMORER
                    || profession == Profession.TOOLSMITH
                    || profession == Profession.WEAPONSMITH
                    || profession == Profession.MASON
                    || profession == Profession.FLETCHER
                    || profession == Profession.LEATHERWORKER) {
                return INDUSTRY;
            }
            if (profession == Profession.NONE) {
                return SERVICES;
            }
            return SERVICES;
        }
    }

    public HappinessBreakdown getResult() {
        return result;
    }

    public City city() {
        return city;
    }

    public String reasonDescription() {
        return reason;
    }

    public ScanContext context() {
        return context;
    }

    public boolean isForceRefresh() {
        return forceRefresh;
    }

    public int totalEntityChunks() {
        return entityChunks.size();
    }

    public int cuboidCount() {
        return city.cuboids != null ? city.cuboids.size() : 0;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public int populationCount() {
        return population;
    }

    public int employedCount() {
        return employed;
    }

    public int bedCount() {
        return beds;
    }

    public StationCountResult trainCartsStationCount() {
        return trainCartsStationCount;
    }

    public String cityId() {
        return city.id;
    }


    public ScanWorkload workload() {
        return workloadSnapshot;
    }

    public ScanProgress progressSnapshot() {
        int remainingEntityChunks = Math.max(0, totalEntityChunks() - entityChunkIndex);
        long remainingBeds = remainingBedBlockCount();
        long total = safeAdd(remainingEntityChunks, remainingBeds);
        return new ScanProgress(remainingEntityChunks, remainingBeds, total);
    }

    private long remainingBedBlockCount() {
        if (bedTasks.isEmpty() || bedTaskIndex >= bedTasks.size()) {
            return 0L;
        }
        long remaining = 0L;
        for (int i = bedTaskIndex; i < bedTasks.size(); i++) {
            remaining = safeAdd(remaining, bedTasks.get(i).remainingBlocks());
        }
        return remaining;
    }

    public long totalBedWorkUnits() {
        return totalBedWorkUnits;
    }

    public long completedBedWorkUnits() {
        return completedBedWorkUnits;
    }

    public long cachedBedChunks() {
        return cachedBedChunks;
    }

    public int activeBedTasks() {
        return bedTasks.size() - bedTaskIndex;
    }

    public int deferredBedChunks() {
        return deferredDirtyChunks;
    }
    public static final class ScanWorkload {
        public static final ScanWorkload EMPTY = new ScanWorkload(0L, 0, 0);
        private final long durationMillis;
        private final int entityChunks;
        private final int bedBlockChecks;

        public ScanWorkload(long durationMillis, int entityChunks, int bedBlockChecks) {
            this.durationMillis = durationMillis;
            this.entityChunks = entityChunks;
            this.bedBlockChecks = bedBlockChecks;
        }

        public long durationMillis() {
            return durationMillis;
        }

        public int entityChunks() {
            return entityChunks;
        }

        public int bedBlockChecks() {
            return bedBlockChecks;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        releaseAllLoadedChunks();
        cancelled = true;
        countedVillagers.clear();
    }

    public void requestRequeue(boolean force, boolean forceLoad, String newReason, ScanContext newContext) {
        rerunRequested = true;
        rerunForceRefresh = rerunForceRefresh || force;
        rerunForceChunkLoad = rerunForceChunkLoad || forceLoad;
        forceRefresh = forceRefresh || force;
        forceChunkLoad = forceChunkLoad || forceLoad;
        if (newReason != null && !newReason.isBlank()) {
            rerunReason = newReason;
        } else if (rerunReason == null) {
            rerunReason = reason;
        }
        if (newContext != null) {
            rerunContext = newContext;
        }
    }

    public RerunRequest consumeRerunRequest() {
        if (!rerunRequested) {
            return new RerunRequest(false, false, false, null, null);
        }
        String nextReason = rerunReason != null ? rerunReason : reason;
        RerunRequest req = new RerunRequest(true, rerunForceRefresh, rerunForceChunkLoad, nextReason, rerunContext);
        rerunRequested = false;
        rerunForceRefresh = false;
        rerunForceChunkLoad = false;
        rerunReason = null;
        rerunContext = null;
        return req;
    }

    private boolean ensureChunkAvailable(World world, ChunkCoord coord) {
        if (world == null || coord == null) {
            return false;
        }
        if (world.isChunkLoaded(coord.x(), coord.z())) {
            return true;
        }
        if (!forceChunkLoad) {
            return false;
        }
        if (chunksLoadedByJob.contains(coord)) {
            return true;
        }
        if (tryLoadChunk(world, coord)) {
            chunksLoadedByJob.add(coord);
            return true;
        }
        return world.isChunkLoaded(coord.x(), coord.z());
    }

    private boolean tryLoadChunk(World world, ChunkCoord coord) {
        boolean loaded = false;
        try {
            loaded = world.loadChunk(coord.x(), coord.z(), false);
        } catch (NoSuchMethodError | UnsupportedOperationException e) {
            loaded = loadChunkViaReflection(world, coord);
        }
        if (!loaded && !world.isChunkLoaded(coord.x(), coord.z())) {
            loaded = loadChunkViaReflection(world, coord);
        }
        return loaded || world.isChunkLoaded(coord.x(), coord.z());
    }

    private boolean loadChunkViaReflection(World world, ChunkCoord coord) {
        try {
            var method = world.getClass().getMethod("getChunkAt", int.class, int.class, boolean.class);
            Object chunk = method.invoke(world, coord.x(), coord.z(), Boolean.TRUE);
            return chunk != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        try {
            Chunk chunk = world.getChunkAt(coord.x(), coord.z());
            return chunk != null;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void unloadChunkIfLoadedByJob(ChunkCoord coord) {
        if (coord == null) {
            return;
        }
        if (!chunksLoadedByJob.remove(coord)) {
            return;
        }
        World world = Bukkit.getWorld(coord.world());
        if (world == null) {
            return;
        }
        try {
            world.unloadChunkRequest(coord.x(), coord.z());
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private void releaseAllLoadedChunks() {
        if (chunksLoadedByJob.isEmpty()) {
            return;
        }
        for (ChunkCoord coord : new ArrayList<>(chunksLoadedByJob)) {
            unloadChunkIfLoadedByJob(coord);
        }
        chunksLoadedByJob.clear();
    }

    private List<ChunkCoord> buildChunkList(City city) {
        Set<ChunkCoord> coords = new LinkedHashSet<>();
        if (city.cuboids == null) {
            return new ArrayList<>();
        }
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

    private List<BedChunkTask> buildBedTasks(City city) {
        List<BedChunkTask> tasks = new ArrayList<>();
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return tasks;
        }
        Map<ChunkKey, List<BedChunkSegment>> segmentsByChunk = new LinkedHashMap<>();
        for (int cuboidIndex = 0; cuboidIndex < city.cuboids.size(); cuboidIndex++) {
            Cuboid cuboid = city.cuboids.get(cuboidIndex);
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            int minCX = cuboid.minX >> 4;
            int maxCX = cuboid.maxX >> 4;
            int minCZ = cuboid.minZ >> 4;
            int maxCZ = cuboid.maxZ >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    int chunkMinX = cx << 4;
                    int chunkMinZ = cz << 4;
                    int chunkMaxX = chunkMinX + 15;
                    int chunkMaxZ = chunkMinZ + 15;
                    int minX = Math.max(cuboid.minX, chunkMinX);
                    int maxX = Math.min(cuboid.maxX, chunkMaxX);
                    int minZ = Math.max(cuboid.minZ, chunkMinZ);
                    int maxZ = Math.min(cuboid.maxZ, chunkMaxZ);
                    if (minX > maxX || minZ > maxZ) {
                        continue;
                    }
                    ChunkKey key = new ChunkKey(cuboid.world, cx, cz);
                    segmentsByChunk
                            .computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new BedChunkSegment(cuboidIndex, minX, maxX, cuboid.minY, cuboid.maxY, minZ, maxZ));
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<ChunkKey, List<BedChunkSegment>> entry : segmentsByChunk.entrySet()) {
            ChunkKey key = entry.getKey();
            City.ChunkPosition chunkPos = new City.ChunkPosition(key.world(), key.x(), key.z());
            City.BedSnapshot snapshot = city.getBedSnapshot(chunkPos);
            boolean expired = snapshot != null && (now - snapshot.timestamp) > BED_CACHE_TTL_MILLIS;
            if (expired && snapshot != null) {
                snapshot.dirty = true;
            }
            boolean useCache = !synchronous && !forceRefresh && snapshot != null && !snapshot.dirty;
            if (useCache) {
                cachedChunks.add(chunkPos);
                bedHalfCount += snapshot.bedHalves;
                if (snapshot.bedHalves > 0) {
                    residentialBedChunks.add(chunkPos);
                }
                cachedBedChunks++;
                totalBedWorkUnits++;
                completedBedWorkUnits++;
                continue;
            }
            if (!synchronous && !forceRefresh && snapshot != null && snapshot.dirty && expiredChunksScheduled >= MAX_EXPIRED_CHUNKS_PER_JOB) {
                deferredDirtyChunks++;
                continue;
            }
            List<BedChunkSegment> segments = entry.getValue();
            ChunkTracker tracker = new ChunkTracker(segments.size());
            chunkTrackers.put(chunkPos, tracker);
            if (snapshot != null && snapshot.dirty) {
                expiredChunksScheduled++;
                city.bedSnapshotMap().remove(chunkPos);
            }
            for (BedChunkSegment segment : segments) {
                int slabMinY = segment.minY();
                while (slabMinY <= segment.maxY()) {
                    int slabMaxY = Math.min(segment.maxY(), slabMinY + 15);
                    BedChunkSegment slab = new BedChunkSegment(
                            segment.cuboidIndex(),
                            segment.minX(),
                            segment.maxX(),
                            slabMinY,
                            slabMaxY,
                            segment.minZ(),
                            segment.maxZ()
                    );
                    tasks.add(new BedChunkTask(chunkPos, key.world(), key.x(), key.z(), slab, tracker));
                    totalBedWorkUnits++;
                    slabMinY = slabMaxY + 1;
                }
            }
        }
        return tasks;
    }

    public int resultingHappiness() {
        return result != null ? result.total : city.happiness;
    }

    public RerunRequest pendingRerunRequest() {
        return rerunRequested ? new RerunRequest(true, rerunForceRefresh, rerunForceChunkLoad, rerunReason, rerunContext) : new RerunRequest(false, false, false, null, null);
    }

    private enum Stage { ENTITY_SCAN, BEDS, BLOCK_CACHE, COMPLETE }

    public record ScanProgress(int remainingEntityChunks, long remainingBedBlocks, long totalWorkUnits) {
        public boolean hasRemainingWork() {
            return totalWorkUnits > 0L;
        }
    }

    private record BedChunkSegment(int cuboidIndex, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private record ChunkKey(String world, int x, int z) {
    }

    private static long safeAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static final class ChunkTracker {
        private int remainingSegments;
        private int bedHalves;
        private ChunkSnapshot snapshot;

        ChunkTracker(int segments) {
            this.remainingSegments = Math.max(1, segments);
            this.bedHalves = 0;
        }

        void addBedHalf() {
            bedHalves++;
        }

        void segmentCompleted() {
            remainingSegments = Math.max(0, remainingSegments - 1);
        }

        ChunkSnapshot snapshot() {
            return snapshot;
        }

        void setSnapshot(ChunkSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void clearSnapshot() {
            this.snapshot = null;
        }
    }

    private static final class BedChunkTask {
        private final City.ChunkPosition chunkPosition;
        private final String world;
        private final int chunkX;
        private final int chunkZ;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final ChunkTracker tracker;
        private final long totalBlocks;
        private int nextX;
        private int nextY;
        private int nextZ;
        private long processedBlocks = 0L;
        private boolean complete = false;

        BedChunkTask(City.ChunkPosition chunkPosition, String world, int chunkX, int chunkZ, BedChunkSegment segment, ChunkTracker tracker) {
            this.chunkPosition = chunkPosition;
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minX = segment.minX();
            this.maxX = segment.maxX();
            this.minY = segment.minY();
            this.maxY = segment.maxY();
            this.minZ = segment.minZ();
            this.maxZ = segment.maxZ();
            this.tracker = tracker;
            this.nextX = minX;
            this.nextY = minY;
            this.nextZ = minZ;
            long width = (long) (maxX - minX + 1);
            long height = (long) (maxY - minY + 1);
            long depth = (long) (maxZ - minZ + 1);
            this.totalBlocks = Math.max(0L, width * height * depth);
        }

        City.ChunkPosition chunkPosition() {
            return chunkPosition;
        }

        String world() {
            return world;
        }

        int chunkX() {
            return chunkX;
        }

        int chunkZ() {
            return chunkZ;
        }

        int nextX() {
            return nextX;
        }

        int nextY() {
            return nextY;
        }

        int nextZ() {
            return nextZ;
        }

        boolean isComplete() {
            return complete;
        }

        long remainingBlocks() {
            if (complete) {
                return 0L;
            }
            long remaining = totalBlocks - processedBlocks;
            return remaining < 0L ? 0L : remaining;
        }

        void advance() {
            if (complete) {
                return;
            }
            nextY++;
            if (nextY > maxY) {
                nextY = minY;
                nextZ++;
                if (nextZ > maxZ) {
                    nextZ = minZ;
                    nextX++;
                    if (nextX > maxX) {
                        complete = true;
                    }
                }
            }
        }

        void markComplete() {
            complete = true;
            processedBlocks = totalBlocks;
        }

        ChunkTracker tracker() {
            return tracker;
        }

        void recordProgress() {
            if (complete) {
                processedBlocks = totalBlocks;
                return;
            }
            processedBlocks++;
        }
    }

    private record ChunkCoord(String world, int x, int z) {
    }

    private static boolean isBed(Material type) {
        return switch (type) {
            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED, PINK_BED,
                    GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> true;
            default -> false;
        };
    }
}

    
