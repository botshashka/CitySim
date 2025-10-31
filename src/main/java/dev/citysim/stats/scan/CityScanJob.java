package dev.citysim.stats.scan;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.StationCountResult;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CityScanJob {
    private final City city;
    private boolean forceRefresh;
    private boolean forceChunkLoad;
    private final String reason;
    private final ScanContext context;
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
    private final Set<City.ChunkPosition> residentialBedChunks = new LinkedHashSet<>();

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
    private ChunkCoord activeBedChunk = null;
    private String activeBedWorldName = null;
    private int activeBedChunkX = 0;
    private int activeBedChunkZ = 0;

    private final CityScanCallbacks callbacks;
    private final ScanDebugManager debugManager;

    public CityScanJob(City city, ScanRequest request, CityScanCallbacks callbacks, ScanDebugManager debugManager) {
        this.city = city;
        boolean refresh = request != null && request.forceRefresh();
        this.forceRefresh = refresh;
        this.forceChunkLoad = request != null && request.forceChunkLoad();
        this.reason = request != null ? request.reason() : null;
        this.context = request != null ? request.context() : null;
        this.callbacks = callbacks;
        this.debugManager = debugManager;
        this.entityChunks = buildChunkList(city);
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
            finalizeCity();
            stage = Stage.COMPLETE;
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
                population++;
                if (villager.getProfession() != Villager.Profession.NONE) {
                    employed++;
                }
            }
            processed++;
        }
        return entityChunkIndex >= entityChunks.size();
    }

    private boolean processBedStage(int bedLimit) {
        if (city.cuboids == null || city.cuboids.isEmpty()) {
            beds = 0;
            return true;
        }
        int limit = bedLimit <= 0 ? Integer.MAX_VALUE : bedLimit;
        int processed = 0;
        while (bedCuboidIndex < city.cuboids.size() && processed < limit) {
            Cuboid cuboid = city.cuboids.get(bedCuboidIndex);
            if (cuboid == null || cuboid.world == null) {
                bedCuboidIndex++;
                bedInitialized = false;
                releaseActiveBedChunk();
                continue;
            }
            World world = Bukkit.getWorld(cuboid.world);
            if (world == null) {
                bedCuboidIndex++;
                bedInitialized = false;
                releaseActiveBedChunk();
                continue;
            }
            if (!bedInitialized) {
                bedX = cuboid.minX;
                bedY = cuboid.minY;
                bedZ = cuboid.minZ;
                bedInitialized = true;
            }
            while (bedCuboidIndex < city.cuboids.size() && processed < limit) {
                String worldName = world.getName();
                int chunkX = bedX >> 4;
                int chunkZ = bedZ >> 4;
                if (!isActiveBedChunk(worldName, chunkX, chunkZ)) {
                    releaseActiveBedChunk();
                    activeBedChunk = new ChunkCoord(worldName, chunkX, chunkZ);
                    activeBedWorldName = worldName;
                    activeBedChunkX = chunkX;
                    activeBedChunkZ = chunkZ;
                }
                if (!ensureChunkAvailable(world, activeBedChunk)) {
                    processed++;
                    if (!advanceBedCursor(cuboid)) {
                        bedCuboidIndex++;
                        bedInitialized = false;
                        releaseActiveBedChunk();
                        break;
                    }
                    continue;
                }
                Material type = world.getBlockAt(bedX, bedY, bedZ).getType();
                if (isBed(type)) {
                    bedHalfCount++;
                    residentialBedChunks.add(new City.ChunkPosition(worldName, chunkX, chunkZ));
                }
                processed++;
                if (!advanceBedCursor(cuboid)) {
                    bedCuboidIndex++;
                    bedInitialized = false;
                    releaseActiveBedChunk();
                    break;
                }
            }
        }
        if (bedCuboidIndex >= city.cuboids.size()) {
            beds = bedHalfCount / 2;
            releaseActiveBedChunk();
            releaseAllLoadedChunks();
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
        city.setResidentialChunks(residentialBedChunks);

        trainCartsStationCount = callbacks.refreshStationCount(city);

        City.BlockScanCache metrics = callbacks.ensureBlockScanCache(city, forceRefresh);
        result = callbacks.calculateHappinessBreakdown(city, metrics);
        city.happinessBreakdown = result;
        city.happiness = result.total;
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

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        releaseActiveBedChunk();
        releaseAllLoadedChunks();
        cancelled = true;
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

    private void releaseActiveBedChunk() {
        if (activeBedChunk == null) {
            return;
        }
        unloadChunkIfLoadedByJob(activeBedChunk);
        activeBedChunk = null;
        activeBedWorldName = null;
        activeBedChunkX = 0;
        activeBedChunkZ = 0;
    }

    private boolean isActiveBedChunk(String worldName, int chunkX, int chunkZ) {
        if (activeBedChunk == null) {
            return false;
        }
        if (activeBedWorldName == null ? worldName != null : !activeBedWorldName.equals(worldName)) {
            return false;
        }
        return activeBedChunkX == chunkX && activeBedChunkZ == chunkZ;
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

    public int resultingHappiness() {
        return result != null ? result.total : city.happiness;
    }

    public RerunRequest pendingRerunRequest() {
        return rerunRequested ? new RerunRequest(true, rerunForceRefresh, rerunForceChunkLoad, rerunReason, rerunContext) : new RerunRequest(false, false, false, null, null);
    }

    private enum Stage { ENTITY_SCAN, BEDS, BLOCK_CACHE, COMPLETE }

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
