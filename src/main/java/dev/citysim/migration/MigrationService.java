package dev.citysim.migration;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.links.CityLink;
import dev.citysim.links.LinkService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class MigrationService implements Runnable {

    private static final double PREFERRED_STATION_RADIUS = 12.0;
    private static final double FALLBACK_VERTICAL_RANGE = 6.0;
    private static final double FALLBACK_VERTICAL_RANGE_WIDE = 8.0;
    private static final double MAX_FALLBACK_RADIUS = 64.0;
    private static final int FALLBACK_SIGN_RADIUS = 4;

    private final Plugin plugin;
    private final CityManager cityManager;
    private final LinkService linkService;
    private final NamespacedKey cooldownKey;
    private final StationPlatformResolver platformResolver;

    private final Map<String, CityMigrationCounters> counters = new HashMap<>();
    private final Map<String, CityEma> cityEmas = new HashMap<>();
    private final Map<String, ConsistencyCounter> originConsistency = new HashMap<>();
    private final Map<String, Map<String, ConsistencyCounter>> pairConsistency = new HashMap<>();
    private final Map<String, TokenBucket> originBuckets = new HashMap<>();
    private final Map<String, TokenBucket> destinationBuckets = new HashMap<>();
    private final Map<String, TokenBucket> linkBuckets = new HashMap<>();
    private final Map<String, Integer> inflightToDest = new HashMap<>();
    private final Map<String, Integer> pendingFromOrigin = new HashMap<>();
    private final PriorityQueue<DelayedMove> delayedQueue = new PriorityQueue<>(Comparator.comparingLong(DelayedMove::executeTick));
    private final TokenBucket globalBucket = new TokenBucket();
    private final Map<String, Integer> platformIndices = new HashMap<>();
    private final Map<String, Long> staleStatsLogTick = new HashMap<>();
    private final Map<String, Long> platformHintsLogTick = new HashMap<>();

    private MigrationSettings settings = MigrationSettings.disabled();
    private BukkitTask task;
    private long logicalTick = 0L;

    public MigrationService(Plugin plugin, CityManager cityManager, LinkService linkService, StationPlatformResolver platformResolver) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.linkService = linkService;
        this.cooldownKey = new NamespacedKey(plugin, "migrate_until");
        this.platformResolver = platformResolver;
    }

    public void reload(FileConfiguration config) {
        this.settings = MigrationSettings.fromConfig(plugin, config);
        if (platformResolver != null) {
            platformResolver.updateTeleportSettings(settings.teleport);
        }
        restart();
    }

    public void start() {
        restart();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void restart() {
        stop();
        resetRuntimeState();
        if (!settings.enabled || settings.intervalTicks <= 0) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, settings.intervalTicks, settings.intervalTicks);
    }

    private void resetRuntimeState() {
        delayedQueue.clear();
        inflightToDest.clear();
        pendingFromOrigin.clear();
        cityEmas.clear();
        originConsistency.clear();
        pairConsistency.clear();
        originBuckets.clear();
        destinationBuckets.clear();
        linkBuckets.clear();
        platformIndices.clear();
        staleStatsLogTick.clear();
        platformHintsLogTick.clear();
        globalBucket.configure(settings.rate.globalPerInterval);
        globalBucket.reset();
        logicalTick = 0L;
    }

    @Override
    public void run() {
        tick();
    }

    private void tick() {
        if (!settings.enabled || settings.maxMovesPerTick <= 0) {
            return;
        }
        if (linkService == null || !linkService.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        logicalTick += Math.max(1, settings.intervalTicks);

        processDelayedQueue(now);

        Collection<City> cities = cityManager.all();
        if (cities.isEmpty()) {
            return;
        }

        prunePlatformRoundRobin(cities);

        for (City city : cities) {
            if (city == null || city.id == null) {
                continue;
            }
            updateEma(city);
        }

        int approvals = 0;
        for (City origin : cities) {
            if (origin == null || origin.id == null) {
                continue;
            }

            boolean originEligible = isOriginEligible(origin, now);
            int originStreak = updateOriginConsistency(origin.id, originEligible);
            if (!originEligible) {
                pairConsistency.remove(origin.id);
                continue;
            }
            if (originStreak < settings.logic.requireConsistencyScans) {
                continue;
            }
            if (!hasPopulationBudget(origin)) {
                continue;
            }

            List<CityLink> links = new ArrayList<>(linkService.computeLinks(origin));
            if (links.isEmpty()) {
                continue;
            }

            List<DestinationCandidate> candidates = evaluateCandidates(origin, links, now);
            if (candidates.isEmpty()) {
                continue;
            }

            for (DestinationCandidate candidate : candidates) {
                if (approvals >= settings.maxMovesPerTick) {
                    return;
                }
                if (!hasPopulationBudget(origin)) {
                    break;
                }
                if (tryApproveMove(origin, candidate.destination, now)) {
                    approvals++;
                }
            }
        }
    }

    private void processDelayedQueue(long nowMillis) {
        while (!delayedQueue.isEmpty()) {
            DelayedMove move = delayedQueue.peek();
            if (move == null || move.executeTick() > logicalTick) {
                break;
            }
            delayedQueue.poll();
            executeApprovedMove(move, nowMillis);
        }
    }

    private void prunePlatformRoundRobin(Collection<City> cities) {
        if (platformIndices.isEmpty() || cities == null || cities.isEmpty()) {
            return;
        }
        Set<String> activeIds = new HashSet<>();
        for (City city : cities) {
            if (city == null || city.id == null) {
                continue;
            }
            activeIds.add(city.id);
        }
        if (activeIds.isEmpty()) {
            platformIndices.clear();
            return;
        }
        platformIndices.keySet().removeIf(id -> !activeIds.contains(id));
    }

    private void updateEma(City city) {
        CityEma ema = cityEmas.computeIfAbsent(city.id, id -> new CityEma());
        ema.update(city.employmentRate, city.housingRatio, city.happiness);
    }

    private boolean isOriginEligible(City city, long nowMillis) {
        if (city == null || city.id == null) {
            return false;
        }
        if (!isStatsFresh(city, nowMillis)) {
            return false;
        }
        CityEma ema = cityEmas.get(city.id);
        if (ema == null || !ema.isInitialized()) {
            return false;
        }
        boolean employmentPressure = ema.employment() < 0.75d;
        boolean housingPressure = ema.housing() < 1.0d;
        return (employmentPressure || housingPressure) && city.population > settings.minPopulationFloor;
    }

    private boolean hasPopulationBudget(City origin) {
        if (origin == null || origin.id == null) {
            return false;
        }
        int pending = pendingFromOrigin.getOrDefault(origin.id, 0);
        int population = Math.max(0, origin.population);
        return population - (pending + 1) > settings.minPopulationFloor;
    }

    private List<DestinationCandidate> evaluateCandidates(City origin, List<CityLink> links, long nowMillis) {
        CityEma originEma = cityEmas.get(origin.id);
        if (originEma == null) {
            return List.of();
        }

        List<DestinationCandidate> rawCandidates = new ArrayList<>();
        double maxStrength = 0.0d;
        double maxProsperityDelta = 0.0d;
        double maxVacancies = 0.0d;

        for (CityLink link : links) {
            City destination = link != null ? link.neighbor() : null;
            if (destination == null || destination.id == null || destination == origin) {
                continue;
            }
            if (!isStatsFresh(destination, nowMillis)) {
                updatePairConsistency(origin.id, destination.id, false);
                continue;
            }
            CityEma destEma = cityEmas.get(destination.id);
            if (destEma == null || !destEma.isInitialized()) {
                updatePairConsistency(origin.id, destination.id, false);
                continue;
            }
            boolean housingOk = destEma.housing() >= settings.logic.destMinHousingRatio;
            boolean employmentOk = destEma.employment() >= settings.logic.destMinEmploymentFloor;
            double prosperityDelta = destEma.prosperity() - originEma.prosperity();
            boolean prosperityOk = prosperityDelta >= settings.logic.minProsperityDelta;
            if (!housingOk || !employmentOk || !prosperityOk) {
                updatePairConsistency(origin.id, destination.id, false);
                continue;
            }

            int pairStreak = updatePairConsistency(origin.id, destination.id, true);
            if (pairStreak < settings.logic.requireConsistencyScans) {
                continue;
            }

            double vacancies = Math.max(0, destination.vacanciesTotal);
            double strength = Math.max(0, link.strength());

            DestinationCandidate candidate = new DestinationCandidate(destination, link, strength, prosperityDelta, vacancies);
            rawCandidates.add(candidate);

            if (strength > maxStrength) {
                maxStrength = strength;
            }
            if (prosperityDelta > maxProsperityDelta) {
                maxProsperityDelta = prosperityDelta;
            }
            if (vacancies > maxVacancies) {
                maxVacancies = vacancies;
            }
        }

        if (rawCandidates.isEmpty()) {
            return List.of();
        }

        double maxStrengthFinal = maxStrength;
        double maxProsperityFinal = maxProsperityDelta;
        double maxVacanciesFinal = maxVacancies;

        for (DestinationCandidate candidate : rawCandidates) {
            double linkScore = maxStrengthFinal > 0 ? candidate.linkStrength / maxStrengthFinal : 0.0d;
            double prosperityScore = maxProsperityFinal > 0 ? candidate.prosperityDelta / maxProsperityFinal : 0.0d;
            double vacanciesScore = maxVacanciesFinal > 0 ? candidate.vacancies / maxVacanciesFinal : 0.0d;
            candidate.score = settings.logic.scoreWeights.linkStrength * clamp01(linkScore)
                    + settings.logic.scoreWeights.prosperityDelta * clamp01(prosperityScore)
                    + settings.logic.scoreWeights.vacancies * clamp01(vacanciesScore);
        }

        rawCandidates.sort(Comparator
                .comparingDouble((DestinationCandidate c) -> c.score).reversed()
                .thenComparingDouble(c -> horizDistance(origin, c.destination)));

        return rawCandidates;
    }

    private double clamp01(double value) {
        if (value <= 0.0d) {
            return 0.0d;
        }
        if (value >= 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private boolean tryApproveMove(City origin, City destination, long nowMillis) {
        if (origin == null || destination == null || origin.id == null || destination.id == null) {
            return false;
        }
        if (!passesPostMoveGuard(destination)) {
            return false;
        }

        Location originAnchor = stationAnchor(origin);
        if (originAnchor == null) {
            return false;
        }

        Location destinationAnchor = stationAnchor(destination);
        if (destinationAnchor == null) {
            return false;
        }

        if (!reserveTokens(origin.id, destination.id, linkKey(origin.id, destination.id))) {
            return false;
        }

        int jitter = settings.rate.jitterTicksMax > 0
                ? ThreadLocalRandom.current().nextInt(settings.rate.jitterTicksMax + 1)
                : 0;
        long executeTick = logicalTick + jitter;

        inflightToDest.merge(destination.id, 1, Integer::sum);
        pendingFromOrigin.merge(origin.id, 1, Integer::sum);
        delayedQueue.add(new DelayedMove(executeTick, origin.id, destination.id));
        return true;
    }

    private boolean passesPostMoveGuard(City destination) {
        if (destination == null || destination.id == null) {
            return false;
        }
        if (settings.logic.postMoveHousingFloor <= 0) {
            return true;
        }
        int inflight = inflightToDest.getOrDefault(destination.id, 0);
        int population = Math.max(0, destination.population);
        int beds = Math.max(0, destination.beds);
        int denominator = population + inflight + 1;
        if (denominator <= 0) {
            return false;
        }
        double effectiveHousing = beds / (double) denominator;
        return effectiveHousing >= settings.logic.postMoveHousingFloor;
    }

    private boolean reserveTokens(String originId, String destinationId, String linkKey) {
        TokenBucket global = settings.rate.globalPerInterval > 0 ? globalBucket : null;
        TokenBucket originBucket = settings.rate.perOriginPerInterval > 0
                ? getBucket(originBuckets, originId, settings.rate.perOriginPerInterval)
                : null;
        TokenBucket destinationBucket = settings.rate.perDestinationPerInterval > 0
                ? getBucket(destinationBuckets, destinationId, settings.rate.perDestinationPerInterval)
                : null;
        TokenBucket linkBucket = settings.rate.perLinkPerInterval > 0
                ? getBucket(linkBuckets, linkKey, settings.rate.perLinkPerInterval)
                : null;

        if (global != null && !global.hasTokens(logicalTick, settings.rate.intervalTicks)) {
            return false;
        }
        if (originBucket != null && !originBucket.hasTokens(logicalTick, settings.rate.intervalTicks)) {
            return false;
        }
        if (destinationBucket != null && !destinationBucket.hasTokens(logicalTick, settings.rate.intervalTicks)) {
            return false;
        }
        if (linkBucket != null && !linkBucket.hasTokens(logicalTick, settings.rate.intervalTicks)) {
            return false;
        }

        if (global != null) {
            global.consume();
        }
        if (originBucket != null) {
            originBucket.consume();
        }
        if (destinationBucket != null) {
            destinationBucket.consume();
        }
        if (linkBucket != null) {
            linkBucket.consume();
        }
        return true;
    }

    private TokenBucket getBucket(Map<String, TokenBucket> buckets, String key, int capacity) {
        if (key == null) {
            return null;
        }
        return buckets.compute(key, (k, existing) -> {
            if (existing == null) {
                TokenBucket bucket = new TokenBucket();
                bucket.configure(capacity);
                return bucket;
            }
            existing.configure(capacity);
            return existing;
        });
    }

    private void executeApprovedMove(DelayedMove move, long nowMillis) {
        boolean success = false;
        Villager villager = null;
        try {
            if (move == null) {
                return;
            }
            City origin = cityManager.get(move.originId);
            City destination = cityManager.get(move.destinationId);
            if (origin == null || destination == null) {
                return;
            }

            Location originAnchor = stationAnchor(origin);
            if (originAnchor == null) {
                return;
            }
            villager = selectVillager(origin, originAnchor, nowMillis);
            if (villager == null) {
                return;
            }

            Location destinationAnchor = stationAnchor(destination);
            List<StationPlatformResolver.StationSpots> stationSpots = platformResolver != null
                    ? platformResolver.resolveStations(destination)
                    : List.of();
            boolean hasPrevalidated = stationSpots.stream().anyMatch(spots -> spots != null && !spots.spots().isEmpty());

            Location target;
            if (hasPrevalidated) {
                target = selectPlatformTarget(destination, stationSpots, settings.teleport);
                if (target == null) {
                    return;
                }
            } else {
                if (platformResolver != null) {
                    platformResolver.invalidateCity(destination);
                }
                Location fallbackAnchor = preferredFallbackAnchor(stationSpots, destinationAnchor);
                if (fallbackAnchor == null) {
                    return;
                }
                int clampY = fallbackAnchor.getBlockY() + 1;
                int fallbackRadiusLimit = Math.max(FALLBACK_SIGN_RADIUS, Math.max(0, settings.teleport.radius));
                target = findDestinationSpot(fallbackAnchor, settings.teleport, fallbackRadiusLimit, clampY);
                if (target == null) {
                    return;
                }
            }
            if (!ensureChunkLoaded(target.getWorld(), target.getBlockX(), target.getBlockZ())) {
                return;
            }
            if (!villager.teleport(target)) {
                return;
            }
            villager.setFallDistance(0f);
            applyCooldown(villager, nowMillis);
            recordDeparture(origin, destination);
            recordArrival(destination, origin);
            success = true;
        } finally {
            if (!success && villager != null) {
                villager.setFallDistance(0f);
            }
            decrementPending(move != null ? move.originId : null);
            decrementInflight(move != null ? move.destinationId : null);
        }
    }

    private void decrementPending(String originId) {
        if (originId == null) {
            return;
        }
        pendingFromOrigin.computeIfPresent(originId, (id, value) -> {
            int next = value - 1;
            return next <= 0 ? null : next;
        });
    }

    private void decrementInflight(String destinationId) {
        if (destinationId == null) {
            return;
        }
        inflightToDest.computeIfPresent(destinationId, (id, value) -> {
            int next = value - 1;
            return next <= 0 ? null : next;
        });
    }

    private int updateOriginConsistency(String originId, boolean eligible) {
        if (originId == null) {
            return 0;
        }
        ConsistencyCounter counter = originConsistency.computeIfAbsent(originId, id -> new ConsistencyCounter());
        if (eligible) {
            return counter.increment(logicalTick, Math.max(1, settings.intervalTicks));
        }
        counter.reset(logicalTick);
        return 0;
    }

    private int updatePairConsistency(String originId, String destinationId, boolean eligible) {
        if (originId == null || destinationId == null) {
            return 0;
        }
        Map<String, ConsistencyCounter> map = pairConsistency.computeIfAbsent(originId, id -> new HashMap<>());
        ConsistencyCounter counter = map.computeIfAbsent(destinationId, id -> new ConsistencyCounter());
        if (eligible) {
            return counter.increment(logicalTick, Math.max(1, settings.intervalTicks));
        }
        counter.reset(logicalTick);
        return 0;
    }

    private boolean isStatsFresh(City city, long nowMillis) {
        if (settings.logic.freshnessMillis <= 0) {
            return true;
        }
        StatsTimestamps stats = lastStatsTimestamp(city);
        long last = stats.maxTimestamp();
        if (last <= 0L) {
            logStaleStatsSkip(city, nowMillis, stats);
            return false;
        }
        boolean fresh = nowMillis - last <= settings.logic.freshnessMillis;
        if (!fresh) {
            logStaleStatsSkip(city, nowMillis, stats);
            return false;
        }
        if (city != null && city.id != null) {
            staleStatsLogTick.remove(city.id);
        }
        return true;
    }

    private StatsTimestamps lastStatsTimestamp(City city) {
        Map<String, Long> sources = new LinkedHashMap<>();
        long maxTimestamp = 0L;
        if (city == null) {
            return new StatsTimestamps(0L, Map.of());
        }
        long statsTimestamp = Math.max(0L, city.statsTimestamp);
        sources.put("stats", statsTimestamp);
        maxTimestamp = Math.max(maxTimestamp, statsTimestamp);

        City.BlockScanCache blockCache = city.blockScanCache;
        long blockTimestamp = blockCache != null ? Math.max(0L, blockCache.timestamp) : 0L;
        sources.put("block_scan", blockTimestamp);
        maxTimestamp = Math.max(maxTimestamp, blockTimestamp);

        City.EntityScanCache entityCache = city.entityScanCache;
        long entityTimestamp = entityCache != null ? Math.max(0L, entityCache.timestamp) : 0L;
        sources.put("entity_scan", entityTimestamp);
        maxTimestamp = Math.max(maxTimestamp, entityTimestamp);

        return new StatsTimestamps(maxTimestamp, Collections.unmodifiableMap(sources));
    }

    private void logStaleStatsSkip(City city, long nowMillis, StatsTimestamps timestamps) {
        if (city == null || city.id == null) {
            return;
        }
        long lastTick = staleStatsLogTick.getOrDefault(city.id, Long.MIN_VALUE);
        if (lastTick == logicalTick) {
            return;
        }
        staleStatsLogTick.put(city.id, logicalTick);
        String cityLabel = city.name != null && !city.name.isBlank() ? city.name : city.id;
        long lastTimestamp = timestamps.maxTimestamp();
        String baseMessage;
        if (lastTimestamp > 0L) {
            long ageSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(Math.max(0L, nowMillis - lastTimestamp)));
            long maxSeconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(Math.max(1L, settings.logic.freshnessMillis)));
            baseMessage = "Skipping migration for city '" + cityLabel + "' (" + city.id + ") — stats " + ageSeconds + "s old (max " + maxSeconds + "s).";
        } else {
            baseMessage = "Skipping migration for city '" + cityLabel + "' (" + city.id + ") — stats have never completed.";
        }
        if (plugin.getLogger().isLoggable(Level.FINE)) {
            plugin.getLogger().fine(baseMessage + " Sources=" + timestamps.describeSources(nowMillis) + ", max=" + lastTimestamp);
        } else {
            plugin.getLogger().fine(baseMessage);
        }
    }

    private void logNoPlatformCandidates(City city, TeleportSettings teleportSettings, boolean sawNonWallSigns) {
        if (city == null || city.id == null) {
            return;
        }
        long lastTick = platformHintsLogTick.getOrDefault(city.id, Long.MIN_VALUE);
        if (lastTick == logicalTick) {
            return;
        }
        platformHintsLogTick.put(city.id, logicalTick);
        String cityLabel = city.name != null && !city.name.isBlank() ? city.name : city.id;
        boolean requireWallSign = teleportSettings != null && teleportSettings.requireWallSign;
        if (requireWallSign && sawNonWallSigns) {
            plugin.getLogger().info("No platform candidates were cached for '" + cityLabel + "' — stations in range use non-wall signs while migration.teleport.require_wall_sign=true.");
            return;
        }
        plugin.getLogger().info("No platform candidates resolved for '" + cityLabel + "'. Verify station builds or rerun a TrainCarts scan.");
    }

    private String linkKey(String originId, String destinationId) {
        return originId + "->" + destinationId;
    }

    private void applyCooldown(Villager villager, long now) {
        if (settings.cooldownMillis <= 0) {
            return;
        }
        long until = now + settings.cooldownMillis;
        villager.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, until);
    }

    private Villager selectVillager(City city, Location anchor, long now) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        List<Villager> nearby = findVillagers(world.getNearbyEntities(anchor, PREFERRED_STATION_RADIUS, FALLBACK_VERTICAL_RANGE, PREFERRED_STATION_RADIUS), city, anchor, now);
        if (!nearby.isEmpty()) {
            return nearby.get(0);
        }

        CityBounds bounds = computeBounds(city);
        double fallbackRadius = PREFERRED_STATION_RADIUS * 2.0;
        if (bounds != null) {
            fallbackRadius = Math.max(fallbackRadius, bounds.maxDimension() / 2.0 + 4.0);
        }
        fallbackRadius = Math.min(MAX_FALLBACK_RADIUS, fallbackRadius);

        Collection<Entity> fallbackEntities = world.getNearbyEntities(anchor, fallbackRadius, FALLBACK_VERTICAL_RANGE_WIDE, fallbackRadius);
        List<Villager> fallback = findVillagers(fallbackEntities, city, anchor, now);
        if (!fallback.isEmpty()) {
            return fallback.get(0);
        }
        return null;
    }

    private List<Villager> findVillagers(Collection<Entity> entities, City city, Location anchor, long now) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<Villager> results = new ArrayList<>();
        for (Entity entity : entities) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            if (!isVillagerEligible(villager, city, now)) {
                continue;
            }
            results.add(villager);
        }
        results.sort(Comparator.comparingDouble(v -> distanceSquared(anchor, v.getLocation())));
        return results;
    }

    private Location selectPlatformTarget(City city, List<StationPlatformResolver.StationSpots> stationSpots, TeleportSettings teleportSettings) {
        if (city == null || city.id == null || stationSpots == null || stationSpots.isEmpty()) {
            if (city != null && city.id != null) {
                platformIndices.remove(city.id);
            }
            return null;
        }
        List<Location> candidates = new ArrayList<>();
        for (StationPlatformResolver.StationSpots station : stationSpots) {
            if (station == null) {
                continue;
            }
            candidates.addAll(station.spots());
        }
        boolean sawNonWallSigns = platformResolver != null && platformResolver.hasOnlyNonWallSignStations(city.id);
        if (candidates.isEmpty()) {
            platformIndices.remove(city.id);
            logNoPlatformCandidates(city, teleportSettings, sawNonWallSigns);
            return null;
        }
        int startIndex = Math.max(0, platformIndices.getOrDefault(city.id, 0));
        for (int i = 0; i < candidates.size(); i++) {
            int idx = (startIndex + i) % candidates.size();
            Location candidate = candidates.get(idx);
            if (candidate == null) {
                continue;
            }
            World world = candidate.getWorld();
            if (world == null) {
                continue;
            }
            int x = candidate.getBlockX();
            int z = candidate.getBlockZ();
            if (!ensureChunkLoaded(world, x, z)) {
                continue;
            }
            int floorY = candidate.getBlockY();
            if (!isCandidateValid(world, x, floorY, z, teleportSettings)) {
                continue;
            }
            platformIndices.put(city.id, (idx + 1) % candidates.size());
            return candidate.clone();
        }
        platformIndices.remove(city.id);
        logNoPlatformCandidates(city, teleportSettings, sawNonWallSigns);
        if (platformResolver != null) {
            platformResolver.invalidateCity(city);
        }
        return null;
    }

    private Location preferredFallbackAnchor(List<StationPlatformResolver.StationSpots> stationSpots, Location defaultAnchor) {
        if (stationSpots != null) {
            for (StationPlatformResolver.StationSpots station : stationSpots) {
                if (station == null) {
                    continue;
                }
                Location sign = station.signLocation();
                if (sign != null) {
                    return sign.clone();
                }
            }
        }
        return defaultAnchor != null ? defaultAnchor.clone() : null;
    }

    private boolean isVillagerEligible(Villager villager, City city, long now) {
        if (villager == null || !villager.isValid() || villager.isDead()) {
            return false;
        }
        if (villager.isSleeping() || !villager.isAdult() || villager.isInsideVehicle()) {
            return false;
        }
        if (villager.getProfession() == Profession.NITWIT) {
            return false;
        }
        Location location = villager.getLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!city.contains(location)) {
            return false;
        }
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long until = pdc.get(cooldownKey, PersistentDataType.LONG);
        return until == null || until <= now;
    }

    private Location findDestinationSpot(Location anchor, TeleportSettings teleportSettings) {
        return findDestinationSpot(anchor, teleportSettings, teleportSettings.radius, Integer.MAX_VALUE);
    }

    private Location findDestinationSpot(Location anchor, TeleportSettings teleportSettings, int radiusLimit, int maxStartY) {
        if (anchor == null || teleportSettings == null) {
            return null;
        }
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        int baseX = anchor.getBlockX();
        int baseY = anchor.getBlockY();
        int baseZ = anchor.getBlockZ();

        if (!ensureChunkLoaded(world, baseX, baseZ)) {
            return null;
        }

        int railY = findLocalRailY(world, baseX, baseY, baseZ, teleportSettings);
        int radius = Math.max(0, Math.min(teleportSettings.radius, Math.max(0, radiusLimit)));
        int maxSamples = Math.max(1, teleportSettings.maxSamples);

        Set<Long> visited = new HashSet<>();
        visited.add(positionKey(baseX, baseZ));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int sample = 0; sample < maxSamples; sample++) {
            int candidateX = baseX;
            int candidateZ = baseZ;
            if (sample > 0 && radius > 0) {
                boolean found = false;
                for (int tries = 0; tries < 6 && !found; tries++) {
                    int dx = random.nextInt(-radius, radius + 1);
                    int dz = random.nextInt(-radius, radius + 1);
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    long key = positionKey(x, z);
                    if (visited.add(key)) {
                        candidateX = x;
                        candidateZ = z;
                        found = true;
                    }
                }
                if (!found) {
                    continue;
                }
            }

            if (!ensureChunkLoaded(world, candidateX, candidateZ)) {
                continue;
            }

            int surfaceY = Math.max(world.getMinHeight(), world.getHighestBlockYAt(candidateX, candidateZ));
            int startY = teleportSettings.requireYAtLeastRail ? Math.max(surfaceY, railY) : surfaceY;
            startY = Math.min(startY, Math.min(world.getMaxHeight() - 1, Math.max(world.getMinHeight(), maxStartY)));

            for (int down = 0; down <= 2; down++) {
                int floorY = startY - down;
                if (floorY < world.getMinHeight()) {
                    break;
                }
                if (!isCandidateValid(world, candidateX, floorY, candidateZ, teleportSettings)) {
                    continue;
                }
                return new Location(world, candidateX + 0.5, floorY + 0.1, candidateZ + 0.5);
            }
        }
        return null;
    }

    private boolean isCandidateValid(World world, int x, int floorY, int z, TeleportSettings teleportSettings) {
        Block floor = world.getBlockAt(x, floorY, z);
        if (!isStandableFloor(floor, teleportSettings)) {
            return false;
        }
        if (!hasHeadroom(world, floorY, x, z)) {
            return false;
        }
        if (teleportSettings.disallowOnRail) {
            if (teleportSettings.railMaterials.contains(floor.getType())) {
                return false;
            }
            Material feet = world.getBlockAt(x, floorY + 1, z).getType();
            if (teleportSettings.railMaterials.contains(feet)) {
                return false;
            }
        }
        if (teleportSettings.disallowBelowRail && isBelowAnyRail(world, teleportSettings, x, floorY, z)) {
            return false;
        }
        if (teleportSettings.railAvoidHorizRadius > 0 && railsNearSameY(world, teleportSettings, x, floorY, z)) {
            return false;
        }
        return true;
    }

    private boolean isStandableFloor(Block block, TeleportSettings teleportSettings) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (teleportSettings.floorAllowlist != null && !teleportSettings.floorAllowlist.isEmpty() && !teleportSettings.floorAllowlist.contains(type)) {
            return false;
        }
        if (teleportSettings.floorBlacklist.contains(type)) {
            return false;
        }
        return type.isSolid() && type.isOccluding();
    }

    private boolean hasHeadroom(World world, int floorY, int x, int z) {
        if (floorY + 2 > world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, floorY + 1, z);
        Block head = world.getBlockAt(x, floorY + 2, z);
        return feet.isPassable() && head.isPassable();
    }

    private boolean isBelowAnyRail(World world, TeleportSettings teleportSettings, int x, int floorY, int z) {
        int maxDy = teleportSettings.railAvoidVertAbove;
        for (int dy = 1; dy <= maxDy; dy++) {
            int y = floorY + dy;
            if (y > world.getMaxHeight()) {
                break;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            if (teleportSettings.railMaterials.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean railsNearSameY(World world, TeleportSettings teleportSettings, int x, int floorY, int z) {
        int radius = teleportSettings.railAvoidHorizRadius;
        if (radius <= 0) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int checkX = x + dx;
                int checkZ = z + dz;
                Material type = world.getBlockAt(checkX, floorY + 1, checkZ).getType();
                if (teleportSettings.railMaterials.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findLocalRailY(World world, int centerX, int centerY, int centerZ, TeleportSettings teleportSettings) {
        int radius = Math.min(teleportSettings.radius, 5);
        int minY = Math.max(world.getMinHeight(), centerY - 3);
        int maxY = Math.min(world.getMaxHeight(), centerY + 3);
        int highest = Integer.MIN_VALUE;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!ensureChunkLoaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (teleportSettings.railMaterials.contains(type) && y > highest) {
                        highest = y;
                    }
                }
            }
        }
        if (highest != Integer.MIN_VALUE) {
            return highest;
        }
        return centerY;
    }

    /**
     * Ensures the chunk containing the supplied block coordinates is loaded, synchronously loading if necessary.
     * Migration approvals are rate-limited, so the occasional sync load stays within safe server budgets.
     */
    private boolean ensureChunkLoaded(World world, int blockX, int blockZ) {
        if (world == null) {
            return false;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        world.loadChunk(chunkX, chunkZ);
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    private Location stationAnchor(City city) {
        if (city == null || city.world == null) {
            return null;
        }
        World world = Bukkit.getWorld(city.world);
        if (world == null) {
            return null;
        }
        CityBounds bounds = computeBounds(city);
        if (bounds == null) {
            return null;
        }
        int x = bounds.centerX();
        int z = bounds.centerZ();
        if (!ensureChunkLoaded(world, x, z)) {
            return null;
        }
        int y = Math.max(world.getMinHeight(), world.getHighestBlockYAt(x, z));
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private static CityBounds computeBounds(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            if (city.world != null && !Objects.equals(city.world, cuboid.world)) {
                continue;
            }
            minX = Math.min(minX, cuboid.minX);
            minZ = Math.min(minZ, cuboid.minZ);
            maxX = Math.max(maxX, cuboid.maxX);
            maxZ = Math.max(maxZ, cuboid.maxZ);
            found = true;
        }
        if (!found) {
            return null;
        }
        int centerX = (int) Math.round((minX + maxX) / 2.0);
        int centerZ = (int) Math.round((minZ + maxZ) / 2.0);
        return new CityBounds(minX, maxX, minZ, maxZ, centerX, centerZ);
    }

    private static double[] centroidXZ(City city) {
        CityBounds bounds = computeBounds(city);
        if (bounds == null) {
            return new double[]{0.0d, 0.0d};
        }
        double centerX = (bounds.minX + bounds.maxX) * 0.5d;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5d;
        return new double[]{centerX, centerZ};
    }

    private static double horizDistance(City a, City b) {
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        double[] ca = centroidXZ(a);
        double[] cb = centroidXZ(b);
        double dx = ca[0] - cb[0];
        double dz = ca[1] - cb[1];
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void recordDeparture(City origin, City destination) {
        if (origin == null || origin.id == null || destination == null || destination.id == null) {
            return;
        }
        CityMigrationCounters counter = counters.computeIfAbsent(origin.id, id -> new CityMigrationCounters());
        counter.departures++;
        counter.links.computeIfAbsent(destination.id, id -> new LinkMigrationCounters()).departures++;
    }

    private void recordArrival(City destination, City origin) {
        if (destination == null || destination.id == null || origin == null || origin.id == null) {
            return;
        }
        CityMigrationCounters counter = counters.computeIfAbsent(destination.id, id -> new CityMigrationCounters());
        counter.arrivals++;
        counter.links.computeIfAbsent(origin.id, id -> new LinkMigrationCounters()).arrivals++;
    }

    public CityMigrationSnapshot snapshot(String cityId) {
        if (cityId == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        CityMigrationCounters counter = counters.get(cityId);
        if (counter == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        Map<String, LinkMigrationSnapshot> linkSnapshots = new LinkedHashMap<>();
        for (Map.Entry<String, LinkMigrationCounters> entry : counter.links.entrySet()) {
            LinkMigrationCounters value = entry.getValue();
            linkSnapshots.put(entry.getKey(), new LinkMigrationSnapshot(value.arrivals, value.departures));
        }
        return new CityMigrationSnapshot(counter.arrivals, counter.departures, Collections.unmodifiableMap(linkSnapshots));
    }

    public CityMigrationSnapshot snapshot(City city) {
        if (city == null || city.id == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        return snapshot(city.id);
    }

    private static double distanceSquared(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null || !Objects.equals(a.getWorld().getUID(), b.getWorld().getUID())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private long positionKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private record CityBounds(int minX, int maxX, int minZ, int maxZ, int centerX, int centerZ) {
        double maxDimension() {
            return Math.max(maxX - minX, maxZ - minZ);
        }
    }

    private record StatsTimestamps(long maxTimestamp, Map<String, Long> perSource) {
        String describeSources(long nowMillis) {
            if (perSource == null || perSource.isEmpty()) {
                return "{}";
            }
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> entry : perSource.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                long timestamp = entry.getValue() != null ? entry.getValue() : 0L;
                builder.append(entry.getKey()).append('=');
                if (timestamp <= 0L) {
                    builder.append("never");
                    continue;
                }
                long ageSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(Math.max(0L, nowMillis - timestamp)));
                builder.append(timestamp).append(" (age ").append(ageSeconds).append("s)");
            }
            builder.append('}');
            return builder.toString();
        }
    }

    private static class CityMigrationCounters {
        long arrivals;
        long departures;
        final Map<String, LinkMigrationCounters> links = new LinkedHashMap<>();
    }

    private static class LinkMigrationCounters {
        long arrivals;
        long departures;
    }

    public record CityMigrationSnapshot(long arrivals, long departures, Map<String, LinkMigrationSnapshot> links) {
        static final CityMigrationSnapshot EMPTY = new CityMigrationSnapshot(0L, 0L, Map.of());

        public long net() {
            return arrivals - departures;
        }
    }

    public record LinkMigrationSnapshot(long arrivals, long departures) {
        public long net() {
            return arrivals - departures;
        }
    }

    private static class CityEma {
        private static final double ALPHA = 0.3d;

        private double employment;
        private double housing;
        private double prosperity;
        private boolean initialized;

        void update(double employment, double housing, double prosperity) {
            if (!initialized) {
                this.employment = sanitize(employment, 0.0d);
                this.housing = sanitize(housing, 1.0d);
                this.prosperity = sanitize(prosperity, 0.0d);
                this.initialized = true;
                return;
            }
            this.employment = smooth(this.employment, sanitize(employment, this.employment));
            this.housing = smooth(this.housing, sanitize(housing, this.housing));
            this.prosperity = smooth(this.prosperity, sanitize(prosperity, this.prosperity));
        }

        private double smooth(double previous, double current) {
            return ALPHA * current + (1.0d - ALPHA) * previous;
        }

        private double sanitize(double value, double fallback) {
            return Double.isFinite(value) ? value : fallback;
        }

        boolean isInitialized() {
            return initialized;
        }

        double employment() {
            return employment;
        }

        double housing() {
            return housing;
        }

        double prosperity() {
            return prosperity;
        }
    }

    private static class DestinationCandidate {
        final City destination;
        final CityLink link;
        final double linkStrength;
        final double prosperityDelta;
        final double vacancies;
        double score;

        DestinationCandidate(City destination, CityLink link, double linkStrength, double prosperityDelta, double vacancies) {
            this.destination = destination;
            this.link = link;
            this.linkStrength = linkStrength;
            this.prosperityDelta = prosperityDelta;
            this.vacancies = vacancies;
        }
    }

    private static class DelayedMove {
        final long executeTick;
        final String originId;
        final String destinationId;

        DelayedMove(long executeTick, String originId, String destinationId) {
            this.executeTick = executeTick;
            this.originId = originId;
            this.destinationId = destinationId;
        }

        long executeTick() {
            return executeTick;
        }
    }

    private static class ConsistencyCounter {
        private int streak = 0;
        private long lastTick = Long.MIN_VALUE;

        int increment(long currentTick, int tickWindow) {
            if (lastTick != Long.MIN_VALUE && tickWindow > 0 && currentTick - lastTick <= tickWindow) {
                streak++;
            } else {
                streak = 1;
            }
            lastTick = currentTick;
            return streak;
        }

        void reset(long currentTick) {
            streak = 0;
            lastTick = currentTick;
        }
    }

    /**
     * Token bucket with coarse refill windows. Buckets refill when {@code logicalTick - lastRefillTick}
     * meets or exceeds {@code migration.rate.interval_ticks}. {@code logicalTick} advances by
     * {@code migration.interval_ticks} every service run, so approvals are capped per configured window.
     */
    private static class TokenBucket {
        private int capacity = 0;
        private int tokens = Integer.MAX_VALUE;
        private long lastRefillTick = Long.MIN_VALUE;

        void configure(int capacity) {
            int newCapacity = Math.max(0, capacity);
            if (this.capacity == newCapacity) {
                return;
            }
            this.capacity = newCapacity;
            reset();
        }

        void reset() {
            tokens = capacity <= 0 ? Integer.MAX_VALUE : capacity;
            lastRefillTick = Long.MIN_VALUE;
        }

        boolean hasTokens(long tick, int interval) {
            refill(tick, interval);
            return capacity <= 0 || tokens > 0;
        }

        void consume() {
            if (capacity <= 0) {
                return;
            }
            if (tokens > 0) {
                tokens--;
            }
        }

        private void refill(long tick, int interval) {
            if (capacity <= 0) {
                tokens = Integer.MAX_VALUE;
                lastRefillTick = tick;
                return;
            }
            if (interval <= 0) {
                tokens = capacity;
                lastRefillTick = tick;
                return;
            }
            if (lastRefillTick == Long.MIN_VALUE || tick - lastRefillTick >= interval) {
                tokens = capacity;
                lastRefillTick = tick;
            }
        }
    }

    private static class MigrationSettings {
        final boolean enabled;
        final int intervalTicks;
        final int maxMovesPerTick;
        final long cooldownMillis;
        final int minPopulationFloor;
        final TeleportSettings teleport;
        final LogicSettings logic;
        final RateSettings rate;

        private MigrationSettings(boolean enabled, int intervalTicks, int maxMovesPerTick, long cooldownMillis,
                                  int minPopulationFloor, TeleportSettings teleport,
                                  LogicSettings logic, RateSettings rate) {
            this.enabled = enabled;
            this.intervalTicks = intervalTicks;
            this.maxMovesPerTick = maxMovesPerTick;
            this.cooldownMillis = cooldownMillis;
            this.minPopulationFloor = minPopulationFloor;
            this.teleport = teleport;
            this.logic = logic;
            this.rate = rate;
        }

        static MigrationSettings disabled() {
            return new MigrationSettings(false, 0, 0, 0L, 0, TeleportSettings.defaults(), LogicSettings.defaults(), RateSettings.defaults());
        }

        static MigrationSettings fromConfig(Plugin plugin, FileConfiguration config) {
            if (config == null) {
                return disabled();
            }
            boolean enabled = config.getBoolean("migration.enabled", false);
            int interval = Math.max(0, config.getInt("migration.interval_ticks", 100));
            int maxMoves = Math.max(0, config.getInt("migration.max_moves_per_tick", 2));
            int cooldownMinutes = Math.max(0, config.getInt("migration.cooldown_minutes", 10));
            long cooldownMillis = TimeUnit.MINUTES.toMillis(cooldownMinutes);
            int populationFloor = Math.max(0, config.getInt("migration.min_city_population_floor", 10));
            TeleportSettings teleport = TeleportSettings.fromConfig(plugin, config, "migration.teleport");
            LogicSettings logic = LogicSettings.fromConfig(config);
            RateSettings rate = RateSettings.fromConfig(config);
            return new MigrationSettings(enabled, interval, maxMoves, cooldownMillis, populationFloor, teleport, logic, rate);
        }
    }

    private static class LogicSettings {
        final long freshnessMillis;
        final int requireConsistencyScans;
        final double minProsperityDelta;
        final double destMinHousingRatio;
        final double destMinEmploymentFloor;
        final double postMoveHousingFloor;
        final ScoreWeights scoreWeights;

        private LogicSettings(long freshnessMillis, int requireConsistencyScans, double minProsperityDelta,
                              double destMinHousingRatio, double destMinEmploymentFloor, double postMoveHousingFloor,
                              ScoreWeights scoreWeights) {
            this.freshnessMillis = freshnessMillis;
            this.requireConsistencyScans = requireConsistencyScans;
            this.minProsperityDelta = minProsperityDelta;
            this.destMinHousingRatio = destMinHousingRatio;
            this.destMinEmploymentFloor = destMinEmploymentFloor;
            this.postMoveHousingFloor = postMoveHousingFloor;
            this.scoreWeights = scoreWeights;
        }

        static LogicSettings defaults() {
            return new LogicSettings(TimeUnit.SECONDS.toMillis(60), 3, 5.0d, 1.05d, 0.75d, 1.0d,
                    new ScoreWeights(0.6d, 0.3d, 0.1d));
        }

        static LogicSettings fromConfig(FileConfiguration config) {
            long freshnessMillis = TimeUnit.SECONDS.toMillis(Math.max(0, config.getLong("migration.logic.freshness_max_secs", 60)));
            int consistency = Math.max(1, config.getInt("migration.logic.require_consistency_scans", 3));
            double minProsperityDelta = config.getDouble("migration.logic.min_prosperity_delta", 5.0d);
            double destHousing = config.getDouble("migration.logic.dest_min_housing_ratio", 1.05d);
            double destEmployment = config.getDouble("migration.logic.dest_min_employment_floor", 0.75d);
            double postMoveHousing = config.getDouble("migration.logic.post_move_housing_floor", 1.0d);
            double linkWeight = config.getDouble("migration.logic.score_weights.link_strength", 0.6d);
            double prosperityWeight = config.getDouble("migration.logic.score_weights.prosperity_delta", 0.3d);
            double vacanciesWeight = config.getDouble("migration.logic.score_weights.vacancies", 0.1d);
            ScoreWeights weights = new ScoreWeights(linkWeight, prosperityWeight, vacanciesWeight);
            return new LogicSettings(freshnessMillis, consistency, minProsperityDelta, destHousing, destEmployment, postMoveHousing, weights);
        }
    }

    private static class ScoreWeights {
        final double linkStrength;
        final double prosperityDelta;
        final double vacancies;

        ScoreWeights(double linkStrength, double prosperityDelta, double vacancies) {
            double total = linkStrength + prosperityDelta + vacancies;
            if (total <= 0) {
                this.linkStrength = 0.6d;
                this.prosperityDelta = 0.3d;
                this.vacancies = 0.1d;
            } else {
                double scale = 1.0d / total;
                this.linkStrength = linkStrength * scale;
                this.prosperityDelta = prosperityDelta * scale;
                this.vacancies = vacancies * scale;
            }
        }
    }

    private static class RateSettings {
        final int intervalTicks;
        final int globalPerInterval;
        final int perOriginPerInterval;
        final int perDestinationPerInterval;
        final int perLinkPerInterval;
        final int jitterTicksMax;

        private RateSettings(int intervalTicks, int globalPerInterval, int perOriginPerInterval,
                             int perDestinationPerInterval, int perLinkPerInterval, int jitterTicksMax) {
            this.intervalTicks = Math.max(1, intervalTicks);
            this.globalPerInterval = Math.max(0, globalPerInterval);
            this.perOriginPerInterval = Math.max(0, perOriginPerInterval);
            this.perDestinationPerInterval = Math.max(0, perDestinationPerInterval);
            this.perLinkPerInterval = Math.max(0, perLinkPerInterval);
            this.jitterTicksMax = Math.max(0, jitterTicksMax);
        }

        static RateSettings defaults() {
            return new RateSettings(200, 10, 2, 2, 1, 40);
        }

        static RateSettings fromConfig(FileConfiguration config) {
            int interval = Math.max(1, config.getInt("migration.rate.interval_ticks", 200));
            int global = Math.max(0, config.getInt("migration.rate.global_per_interval", 10));
            int perOrigin = Math.max(0, config.getInt("migration.rate.per_origin_per_interval", 2));
            int perDestination = Math.max(0, config.getInt("migration.rate.per_destination_per_interval", 2));
            int perLink = Math.max(0, config.getInt("migration.rate.per_link_per_interval", 1));
            int jitter = Math.max(0, config.getInt("migration.rate.jitter_ticks_max", 40));
            return new RateSettings(interval, global, perOrigin, perDestination, perLink, jitter);
        }
    }

}
