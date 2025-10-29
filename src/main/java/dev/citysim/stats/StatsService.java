
package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.Plugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class StatsService {

    private static final long DEFAULT_STATS_INITIAL_DELAY_TICKS = 40L;
    private static final long DEFAULT_STATS_INTERVAL_TICKS = 100L;
    private static final long MIN_STATS_INTERVAL_TICKS = 20L;
    private static final long MAX_STATS_INTERVAL_TICKS = 12000L; // 10 minutes at 20 TPS
    private static final long MAX_STATS_INITIAL_DELAY_TICKS = 6000L; // 5 minutes at 20 TPS

    private final Plugin plugin;
    private final CityManager cityManager;
    private volatile StationCounter stationCounter;
    private int taskId = -1;
    private long statsInitialDelayTicks = DEFAULT_STATS_INITIAL_DELAY_TICKS;
    private long statsIntervalTicks = DEFAULT_STATS_INTERVAL_TICKS;

    private long blockScanRefreshIntervalMillis = 60000L;

    // Weights
    private static final int HIGHRISE_VERTICAL_STEP = 4;
    private static final double OVERCROWDING_BASELINE = 3.0;
    private static final double TRANSIT_IDEAL_SPACING_BLOCKS = 75.0;
    private static final double TRANSIT_EASING_EXPONENT = 0.5;
    private static final double NATURE_TARGET_RATIO = 0.10;
    private static final int NATURE_MIN_EFFECTIVE_SAMPLES = 36;

    private double lightNeutral = 2.0;
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double overcrowdMaxPenalty = 10;
    private double natureMaxPts = 10;
    private double pollutionMaxPenalty = 15;
    private double housingMaxPts = 10;
    private double transitMaxPts = 5;
    private StationCountingMode stationCountingMode = StationCountingMode.MANUAL;
    private boolean stationCountingWarningLogged = false;

    private final Map<String, ScanRequest> pendingCityUpdates = new LinkedHashMap<>();
    private final Deque<String> scheduledCityQueue = new ArrayDeque<>();
    private final Map<String, CityScanJob> activeCityJobs = new LinkedHashMap<>();
    private int maxCitiesPerTick = 1;
    private int maxEntityChunksPerTick = 2;
    private int maxBedBlocksPerTick = 2048;

    private final ScanDebugManager scanDebugManager = new ScanDebugManager();

    public StatsService(Plugin plugin, CityManager cm, StationCounter stationCounter) {
        this.plugin = plugin;
        this.cityManager = cm;
        this.stationCounter = stationCounter;
        updateConfig();
    }

    public void setStationCounter(StationCounter stationCounter) {
        if (this.stationCounter == stationCounter) {
            return;
        }
        this.stationCounter = stationCounter;
        updateConfig();
    }

    public void start() {
        updateConfig();
        if (taskId != -1) return;
        pendingCityUpdates.clear();
        scheduledCityQueue.clear();
        scheduleInitialStartupScans();
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
        pendingCityUpdates.clear();
        scheduledCityQueue.clear();
        scheduleInitialStartupScans();
        scheduleTask();
    }

    public void requestCityUpdate(City city) {
        requestCityUpdate(city, false);
    }

    public void requestCityUpdate(City city, boolean forceRefresh) {
        requestCityUpdate(city, forceRefresh, null, null);
    }

    public void requestCityUpdate(City city, boolean forceRefresh, String reason) {
        requestCityUpdate(city, forceRefresh, reason, null);
    }

    public void requestCityUpdate(City city, boolean forceRefresh, String reason, Location triggerLocation) {
        if (city == null || city.id == null) {
            return;
        }
        addPendingCity(city.id, forceRefresh, reason, createContext(triggerLocation));
    }

    public void requestCityUpdate(Location location) {
        requestCityUpdate(location, false);
    }

    public void requestCityUpdate(Location location, boolean forceRefresh) {
        requestCityUpdate(location, forceRefresh, null);
    }

    public void requestCityUpdate(Location location, boolean forceRefresh, String reason) {
        if (location == null) {
            return;
        }
        City city = cityManager.cityAt(location);
        if (city != null) {
            requestCityUpdate(city, forceRefresh, reason, location);
        }
    }

    public boolean toggleScanDebug(Player player) {
        if (player == null) {
            return false;
        }
        return scanDebugManager.toggle(player);
    }

    public StationCountingMode getStationCountingMode() {
        return stationCountingMode;
    }

    private void addPendingCity(String cityId, boolean forceRefresh, String reason, ScanContext context) {
        addPendingCity(cityId, forceRefresh, false, reason, context);
    }

    private void addPendingCity(String cityId, boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {
        if (cityId == null || cityId.isEmpty()) {
            return;
        }
        CityScanJob activeJob = activeCityJobs.get(cityId);
        if (activeJob != null) {
            activeJob.requestRequeue(forceRefresh, forceChunkLoad, reason, context);
            return;
        }
        ScanRequest request = new ScanRequest(forceRefresh, forceChunkLoad, reason, context);
        pendingCityUpdates.merge(cityId, request, ScanRequest::merge);
        scheduledCityQueue.remove(cityId);
    }

    private ScanContext createContext(Location location) {
        if (location == null) {
            return null;
        }
        String worldName = location.getWorld() != null ? location.getWorld().getName() : null;
        return new ScanContext(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());
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
            ScanRequest request = entry.getValue();
            if (startCityScanJob(city, request)) {
                return true;
            }
        }
        return false;
    }

    private boolean processNextScheduledCity() {
        int attemptsRemaining = 0;
        while (true) {
            if (scheduledCityQueue.isEmpty()) {
                refillScheduledQueue();
                if (scheduledCityQueue.isEmpty()) {
                    return false;
                }
                attemptsRemaining = scheduledCityQueue.size();
            }
            if (attemptsRemaining <= 0) {
                attemptsRemaining = scheduledCityQueue.size();
                if (attemptsRemaining <= 0) {
                    return false;
                }
            }
            attemptsRemaining--;
            String cityId = scheduledCityQueue.pollFirst();
            if (cityId == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(cityId) || activeCityJobs.containsKey(cityId)) {
                continue;
            }
            City city = cityManager.get(cityId);
            if (city == null) {
                continue;
            }
            if (startCityScanJob(city, new ScanRequest(false, "scheduled sweep", null))) {
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
                addPendingCity(job.cityId(), rerun.forceRefresh(), rerun.forceChunkLoad(), rerun.reason(), rerun.context());
            }
        }
    }

    private boolean startCityScanJob(City city, ScanRequest request) {
        if (city == null || city.id == null || city.id.isEmpty()) {
            return false;
        }
        CityScanJob existing = activeCityJobs.get(city.id);
        if (existing != null) {
            existing.requestRequeue(request.forceRefresh(), request.forceChunkLoad(), request.reason(), request.context());
            return false;
        }
        CityScanJob job = new CityScanJob(city, request);
        activeCityJobs.put(city.id, job);
        return true;
    }

    private void refillScheduledQueue() {
        scheduledCityQueue.clear();
        List<City> cities = new ArrayList<>(cityManager.all());
        // Cities are stored in insertion/priority order inside CityManager's LinkedHashMap,
        // so iterating over the snapshot preserves the intended ordering without requiring
        // an expensive sort for every refill.
        for (City city : cities) {
            if (city == null || city.id == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(city.id) || activeCityJobs.containsKey(city.id)) {
                continue;
            }
            scheduledCityQueue.addLast(city.id);
        }
    }

    private void scheduleInitialStartupScans() {
        Bukkit.getScheduler().runTask(plugin, this::runInitialStartupScans);
    }

    private void runInitialStartupScans() {
        if (!activeCityJobs.isEmpty()) {
            for (CityScanJob job : activeCityJobs.values()) {
                job.cancel();
            }
            activeCityJobs.clear();
        }
        for (City city : cityManager.all()) {
            if (city == null || city.id == null || city.id.isEmpty()) {
                continue;
            }
            CityScanJob job = new CityScanJob(city, new ScanRequest(true, true, "initial startup", null));
            while (!job.process(Integer.MAX_VALUE, Integer.MAX_VALUE)) {
                // Keep processing until the scan completes synchronously
            }
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
        CityScanJob job = new CityScanJob(city, new ScanRequest(forceRefresh, true, "synchronous update", null));
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

    private record ScanRequest(boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {
        ScanRequest(boolean forceRefresh, String reason, ScanContext context) {
            this(forceRefresh, false, reason, context);
        }

        ScanRequest merge(ScanRequest other) {
            if (other == null) {
                return this;
            }
            boolean mergedForce = this.forceRefresh || other.forceRefresh;
            boolean mergedForceChunk = this.forceChunkLoad || other.forceChunkLoad;
            String mergedReason = other.reason != null ? other.reason : this.reason;
            ScanContext mergedContext = other.context != null ? other.context : this.context;
            return new ScanRequest(mergedForce, mergedForceChunk, mergedReason, mergedContext);
        }
    }

    private record ScanContext(String world, Integer x, Integer y, Integer z) {
        String describe() {
            if (world == null) {
                return "unknown";
            }
            if (x != null && y != null && z != null) {
                return world + " (" + x + ", " + y + ", " + z + ")";
            }
            return world;
        }
    }

    private record ChunkCoord(String world, int x, int z) {}

    private record RerunRequest(boolean requested, boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {}

    private final class ScanDebugManager {
        private final Set<UUID> watchers = new HashSet<>();
        private final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        boolean toggle(Player player) {
            UUID id = player.getUniqueId();
            if (watchers.remove(id)) {
                return false;
            }
            watchers.add(id);
            return true;
        }

        boolean isEnabled() {
            return !watchers.isEmpty();
        }

        void logJobStarted(CityScanJob job) {
            String type = describeReason(job);
            String cityLabel = describeCity(job.city());
            String location = describeLocation(job.city(), job.context());
            String refreshMode = job.isForceRefresh() ? "force" : "incremental";
            int chunks = job.totalEntityChunks();
            int cuboids = job.cuboidCount();
            String message = String.format(
                    "Started %s scan for %s at %s — refresh=%s, entityChunks=%d, cuboids=%d",
                    type,
                    cityLabel,
                    location,
                    refreshMode,
                    chunks,
                    cuboids
            );
            broadcast(message);
        }

        void logJobCompleted(CityScanJob job) {
            long duration = Math.max(0L, System.currentTimeMillis() - job.startedAtMillis());
            String type = describeReason(job);
            String cityLabel = describeCity(job.city());
            HappinessBreakdown breakdown = job.getResult();
            int happiness = breakdown != null ? breakdown.total : job.city().happiness;
            String message = String.format(
                    "Completed %s scan for %s in %d ms — pop=%d, employed=%d, beds=%d, happiness=%d",
                    type,
                    cityLabel,
                    duration,
                    job.populationCount(),
                    job.employedCount(),
                    job.bedCount(),
                    happiness
            );
            broadcast(message);
        }

        private void broadcast(String message) {
            if (watchers.isEmpty()) {
                return;
            }
            String timestamp = LocalDateTime.now().format(timestampFormat);
            Component component = Component.text()
                    .append(Component.text("[" + timestamp + "] ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(message, NamedTextColor.GRAY))
                    .build();
            var iterator = watchers.iterator();
            while (iterator.hasNext()) {
                UUID id = iterator.next();
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    iterator.remove();
                    continue;
                }
                player.sendMessage(component);
            }
        }

        private String describeCity(City city) {
            if (city == null) {
                return "unknown city";
            }
            String name = city.name != null ? city.name : "(unnamed)";
            String id = city.id != null ? city.id : "?";
            return name + " (" + id + ")";
        }

        private String describeReason(CityScanJob job) {
            String reason = job.reasonDescription();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
            return job.isForceRefresh() ? "forced update" : "incremental update";
        }

        private String describeLocation(City city, ScanContext context) {
            if (context != null) {
                return context.describe();
            }
            if (city != null && city.cuboids != null && !city.cuboids.isEmpty()) {
                Cuboid cuboid = city.cuboids.get(0);
                String world = cuboid.world != null ? cuboid.world : city.world;
                int centerX = cuboid.minX + ((cuboid.maxX - cuboid.minX) / 2);
                int centerY = cuboid.minY + ((cuboid.maxY - cuboid.minY) / 2);
                int centerZ = cuboid.minZ + ((cuboid.maxZ - cuboid.minZ) / 2);
                return (world != null ? world : "unknown") + " (" + centerX + ", " + centerY + ", " + centerZ + ")";
            }
            if (city != null && city.world != null) {
                return city.world;
            }
            return "unknown";
        }
    }

    private final class CityScanJob {
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

        private final Set<ChunkCoord> chunksLoadedByJob = new LinkedHashSet<>();
        private ChunkCoord activeBedChunk = null;
        private String activeBedWorldName = null;
        private int activeBedChunkX = 0;
        private int activeBedChunkZ = 0;

        CityScanJob(City city, ScanRequest request) {
            this.city = city;
            boolean refresh = request != null && request.forceRefresh();
            this.forceRefresh = refresh;
            this.forceChunkLoad = request != null && request.forceChunkLoad();
            this.reason = request != null ? request.reason() : null;
            this.context = request != null ? request.context() : null;
            this.entityChunks = buildChunkList(city);
        }

        boolean process(int chunkLimit, int bedLimit) {
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
                if (scanDebugManager.isEnabled()) {
                    scanDebugManager.logJobCompleted(this);
                }
            }
            return stage == Stage.COMPLETE;
        }

        private void logStartIfNeeded() {
            if (!startLogged && scanDebugManager.isEnabled()) {
                startLogged = true;
                scanDebugManager.logJobStarted(this);
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
                Location reusable = new Location(null, 0, 0, 0);
                for (Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof Villager villager)) {
                        continue;
                    }
                    villager.getLocation(reusable);
                    if (!city.contains(reusable)) {
                        continue;
                    }
                    population++;
                    Villager.Profession prof = villager.getProfession();
                    boolean isNitwit = prof == Villager.Profession.NITWIT;
                    boolean hasProf = prof != Villager.Profession.NONE && !isNitwit;
                    if (hasProf) {
                        employed++;
                    }
                }
                processed++;
                unloadChunkIfLoadedByJob(coord);
            }
            return entityChunkIndex >= entityChunks.size();
        }

        private boolean processBedStage(int bedLimit) {
            if (bedCuboidIndex >= city.cuboids.size()) {
                beds = bedHalfCount / 2;
                releaseActiveBedChunk();
                releaseAllLoadedChunks();
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
                    String worldName = world != null ? world.getName() : null;
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

            refreshStationCount(city);

            City.BlockScanCache metrics = ensureBlockScanCache(city, forceRefresh);
            result = calculateHappinessBreakdown(city, metrics);
            city.happinessBreakdown = result;
            city.happiness = result.total;
        }

        HappinessBreakdown getResult() {
            return result;
        }

        City city() {
            return city;
        }

        String reasonDescription() {
            return reason;
        }

        ScanContext context() {
            return context;
        }

        boolean isForceRefresh() {
            return forceRefresh;
        }

        int totalEntityChunks() {
            return entityChunks.size();
        }

        int cuboidCount() {
            return city.cuboids != null ? city.cuboids.size() : 0;
        }

        long startedAtMillis() {
            return startedAtMillis;
        }

        int populationCount() {
            return population;
        }

        int employedCount() {
            return employed;
        }

        int bedCount() {
            return beds;
        }

        String cityId() {
            return city.id;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            releaseActiveBedChunk();
            releaseAllLoadedChunks();
            cancelled = true;
        }

        void requestRequeue(boolean force, boolean forceLoad, String newReason, ScanContext newContext) {
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

        RerunRequest consumeRerunRequest() {
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
            requestChunkUnload(world, coord.x(), coord.z());
        }

        private void requestChunkUnload(World world, int x, int z) {
            try {
                world.unloadChunkRequest(x, z);
                return;
            } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            }
            try {
                world.setChunkForceLoaded(x, z, false);
                return;
            } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            }
            try {
                world.unloadChunk(x, z);
            } catch (Throwable ignored) {
            }
        }

        private void releaseAllLoadedChunks() {
            if (chunksLoadedByJob.isEmpty()) {
                return;
            }
            List<ChunkCoord> coords = new ArrayList<>(chunksLoadedByJob);
            for (ChunkCoord coord : coords) {
                unloadChunkIfLoadedByJob(coord);
            }
            activeBedChunk = null;
            activeBedWorldName = null;
            activeBedChunkX = 0;
            activeBedChunkZ = 0;
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
        requestCityUpdate(city, true, "compute happiness breakdown");
        return city.happinessBreakdown != null ? city.happinessBreakdown : new HappinessBreakdown();
    }

    private void refreshStationCount(City city) {
        if (city == null) {
            return;
        }
        switch (stationCountingMode) {
            case DISABLED -> city.stations = 0;
            case TRAIN_CARTS -> {
                StationCounter counter = stationCounter;
                if (counter == null) {
                    if (!stationCountingWarningLogged) {
                        plugin.getLogger().warning("TrainCarts station counting requested but integration is unavailable; using manual station totals.");
                        stationCountingWarningLogged = true;
                    }
                    return;
                }
                try {
                    OptionalInt counted = counter.countStations(city);
                    if (counted.isPresent()) {
                        city.stations = Math.max(0, counted.getAsInt());
                        stationCountingWarningLogged = false;
                    } else if (!stationCountingWarningLogged) {
                        plugin.getLogger().warning("Failed to refresh TrainCarts station count for city '" + city.name + "'; keeping the previous value.");
                        stationCountingWarningLogged = true;
                    }
                } catch (RuntimeException ex) {
                    if (!stationCountingWarningLogged) {
                        plugin.getLogger().log(Level.WARNING, "Unexpected error counting TrainCarts stations for city '" + city.name + "'", ex);
                        stationCountingWarningLogged = true;
                    }
                }
            }
            case MANUAL -> {
                stationCountingWarningLogged = false;
            }
        }
    }

    private HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache metrics) {
        int pop = city.population;
        int employed = city.employed;
        HappinessBreakdown hb = new HappinessBreakdown();

        double lightScore = metrics.light;
        double lightScoreNormalized = (lightScore - lightNeutral) / lightNeutral;
        hb.lightPoints = clamp(lightScoreNormalized * lightMaxPts, -lightMaxPts, lightMaxPts);

        double employmentNeutral = 0.75; // 75% employment is neutral for populated cities; empty cities stay neutral
        double employmentScore;
        if (pop <= 0) {
            employmentScore = 0.0; // Neutral employment contribution for cities without residents
        } else {
            double employmentRate = (double) employed / (double) pop;
            if (employmentRate >= employmentNeutral) {
                employmentScore = (employmentRate - employmentNeutral) / (1.0 - employmentNeutral);
            } else {
                employmentScore = (employmentRate - employmentNeutral) / employmentNeutral;
            }
        }
        hb.employmentPoints = clamp(employmentScore * employmentMaxPts, -employmentMaxPts, employmentMaxPts);

        hb.overcrowdingPenalty = clamp(metrics.overcrowdingPenalty, 0.0, overcrowdMaxPenalty);

        double adjustedNature = adjustedNatureRatio(metrics.nature, metrics.natureSamples);
        double natureScore = (adjustedNature - NATURE_TARGET_RATIO) / NATURE_TARGET_RATIO;
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
        double housingNeutral = 1.0 / 0.95; // 95% occupancy (population / beds) is neutral
        double housingScore = (housingRatio / housingNeutral) - 1.0;
        hb.housingPoints = clamp(housingScore * housingMaxPts, -housingMaxPts, housingMaxPts);

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

    private double adjustedNatureRatio(double rawRatio, int sampleCount) {
        double clampedRatio = clamp(rawRatio, 0.0, 1.0);
        int samples = Math.max(0, sampleCount);
        double sampleWeight = Math.min(1.0, samples / (double) NATURE_MIN_EFFECTIVE_SAMPLES);
        double adjusted = NATURE_TARGET_RATIO + sampleWeight * (clampedRatio - NATURE_TARGET_RATIO);
        return clamp(adjusted, 0.0, 1.0);
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
        SampledRatio nature = natureRatio(city);
        cache.nature = nature.ratio();
        cache.natureSamples = nature.samples();
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

    private static class ColumnSample {
        boolean sampled;
        boolean matched;
    }

    private record SampledRatio(double ratio, int samples) {}

    private SampledRatio ratioSurface(City city, int step, BlockTest test) {
        SurfaceSampleResult result = sampleSurface(city, step, test);
        return new SampledRatio(result.ratio(), result.probes);
    }

    private SampledRatio ratioHighriseColumns(City city, int step, BlockTest test) {
        Map<String, Map<Long, ColumnSample>> columns = new HashMap<>();
        for (Cuboid c : city.cuboids) {
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
                        org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                        sampled = true;
                        if (test.test(b)) {
                            column.matched = true;
                            break;
                        }
                    }
                    if (!column.matched && (c.maxY - c.minY) % HIGHRISE_VERTICAL_STEP != 0) {
                        org.bukkit.block.Block b = w.getBlockAt(x, c.maxY, z);
                        sampled = true;
                        if (test.test(b)) {
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
        if (stationCountingMode == StationCountingMode.DISABLED) {
            return 0.0;
        }

        double area = totalFootprintArea(city);
        if (area <= 0.0 || transitMaxPts <= 0.0) {
            return 0.0;
        }

        double actualStations = Math.max(0, city.stations);
        if (actualStations <= 0.0) {
            return -transitMaxPts;
        }

        double idealStations = Math.max(1.0, area / (TRANSIT_IDEAL_SPACING_BLOCKS * TRANSIT_IDEAL_SPACING_BLOCKS));
        double coverageRatio = actualStations / idealStations;
        double easedCoverage = Math.pow(coverageRatio, TRANSIT_EASING_EXPONENT);
        double score = transitMaxPts * clamp(easedCoverage, 0.0, 1.0);
        return clamp(score, -transitMaxPts, transitMaxPts);
    }

    public void updateConfig() {
        var c = plugin.getConfig();
        blockScanRefreshIntervalMillis = Math.max(0L, c.getLong("happiness.block_scan_refresh_interval_millis", 60000L));

        StationCountingMode configuredMode = StationCountingMode.fromConfig(c.getString("stations.counting_mode", "manual"));
        if (configuredMode == StationCountingMode.TRAIN_CARTS && stationCounter == null) {
            if (stationCountingMode != StationCountingMode.MANUAL) {
                plugin.getLogger().warning("TrainCarts station counting requested in configuration, but TrainCarts was not detected. Falling back to manual station counts.");
            }
            stationCountingMode = StationCountingMode.MANUAL;
        } else {
            stationCountingMode = configuredMode;
        }
        if (stationCountingMode != StationCountingMode.TRAIN_CARTS) {
            stationCountingWarningLogged = false;
        }

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
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, statsInitialDelayTicks, statsIntervalTicks);
    }
}
