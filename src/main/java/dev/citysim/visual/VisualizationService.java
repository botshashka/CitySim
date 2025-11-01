package dev.citysim.visual;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.visual.SelectionTracker.SelectionSnapshot;
import dev.citysim.visual.SelectionTracker.YMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class VisualizationService implements SelectionTracker.Listener {
    private final JavaPlugin plugin;
    private final CityManager cityManager;
    private final SelectionTracker selectionTracker;
    private final ShapeSampler shapeSampler = new ShapeSampler();
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> cityViewers = new ConcurrentHashMap<>();
    private final Map<SelectionCacheKey, CompletableFuture<float[]>> selectionCache = new ConcurrentHashMap<>();
    private final Map<CityCacheKey, CompletableFuture<float[]>> cityCache = new ConcurrentHashMap<>();
    private final ParticleRenderer renderer;
    private VisualizationConfig config;
    private final AtomicBoolean debugEnabled = new AtomicBoolean(false);

    public VisualizationService(JavaPlugin plugin,
                                CityManager cityManager,
                                SelectionTracker selectionTracker,
                                VisualizationConfig config) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.selectionTracker = selectionTracker;
        this.config = config;
        this.renderer = new ParticleRenderer(config);
        this.selectionTracker.setListener(this);
    }

    public void reload(VisualizationConfig updatedConfig) {
        this.config = updatedConfig;
        this.renderer.updateConfig(updatedConfig);
        for (PlayerSession session : sessions.values()) {
            if (session.scheduler != null) {
                if (updatedConfig.enabled()) {
                    session.scheduler.start(updatedConfig.refreshTicks());
                } else {
                    session.scheduler.stop();
                }
            }
        }
        invalidateAllCaches();
    }

    public void enableCityView(Player player, String cityId) {
        if (player == null || cityId == null) {
            return;
        }
        PlayerSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerSession::new);
        session.enableCity(cityId);
        cityViewers.computeIfAbsent(cityId, ignored -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
        ensureScheduler(session);
        markCityDirty(session, cityId);
    }

    public void disableCityView(Player player, String cityId) {
        if (player == null || cityId == null) {
            return;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.disableCity(cityId);
            if (session.isEmpty()) {
                stopSession(session);
            }
        }
        Set<UUID> viewers = cityViewers.get(cityId);
        if (viewers != null) {
            viewers.remove(player.getUniqueId());
            if (viewers.isEmpty()) {
                cityViewers.remove(cityId);
            }
        }
    }

    public void updateCityView(Collection<Player> viewers, String cityId) {
        if (cityId == null) {
            return;
        }
        Set<UUID> ids = new HashSet<>();
        if (viewers != null) {
            for (Player player : viewers) {
                if (player != null) {
                    ids.add(player.getUniqueId());
                }
            }
        }
        Set<UUID> tracked = cityViewers.get(cityId);
        if (tracked != null) {
            ids.addAll(tracked);
        }
        if (ids.isEmpty()) {
            return;
        }
        invalidateCityCache(cityId);
        for (UUID id : ids) {
            PlayerSession session = sessions.get(id);
            if (session != null) {
                markCityDirty(session, cityId);
                ensureScheduler(session);
            }
        }
    }

    public void updateSelectionView(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOnline()) {
            PlayerSession removed = sessions.remove(player.getUniqueId());
            if (removed != null) {
                stopSession(removed);
            }
            return;
        }
        PlayerSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerSession::new);
        session.selectionState.markDirty();
        ensureScheduler(session);
    }

    public void handlePlayerQuit(Player player) {
        if (player == null) {
            return;
        }
        PlayerSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            stopSession(session);
        }
        for (Set<UUID> viewers : cityViewers.values()) {
            viewers.remove(player.getUniqueId());
        }
        selectionTracker.clear(player);
    }

    public void shutdown() {
        for (PlayerSession session : sessions.values()) {
            stopSession(session);
        }
        sessions.clear();
        cityViewers.clear();
        selectionCache.clear();
        cityCache.clear();
    }

    public boolean isCityViewEnabled(Player player, String cityId) {
        if (player == null || cityId == null) {
            return false;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        return session != null && session.isCityActive(cityId);
    }

    public void setDebugEnabled(boolean enabled) {
        debugEnabled.set(enabled);
    }

    public boolean isDebugEnabled() {
        return debugEnabled.get();
    }

    @Override
    public void onSelectionUpdated(Player player) {
        updateSelectionView(player);
    }

    @Override
    public void onSelectionCleared(Player player) {
        updateSelectionView(player);
    }

    void render(PlayerSession session) {
        if (config == null || !config.enabled()) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            stopSession(session);
            sessions.remove(session.playerId);
            return;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        SamplingContext context = new SamplingContext(
                location.getX(),
                location.getY(),
                location.getZ(),
                config.viewDistance(),
                config.baseStep(),
                config.farDistanceStepMultiplier(),
                config.maxPointsPerTick(),
                config.jitter(),
                config.sliceThickness());

        List<Emission> emissions = new ArrayList<>();

        SelectionSnapshot selection = selectionTracker.snapshot(player).orElse(null);
        if (selection != null && selection.ready() && selection.world() == world) {
            double distance = distanceToSelection(location, selection);
            SelectionRenderState state = session.selectionState;
            state.active = distance <= config.viewDistance();
            if (!state.active) {
                SelectionCacheKey existingKey = state.key;
                if (existingKey != null) {
                    selectionCache.remove(existingKey);
                }
                state.markDirty();
            } else {
                int sliceBucket = selection.mode() == YMode.FULL ? (int) Math.floor(location.getY()) : 0;
                Double sliceY = selection.mode() == YMode.FULL ? Math.floor(location.getY()) + 0.5 : null;
                SelectionCacheKey key = new SelectionCacheKey(player.getUniqueId(), selection.hash(), selection.mode(), sliceBucket);
                if (!key.equals(state.key)) {
                    state.key = key;
                    state.cursor = 0;
                    state.points = null;
                    CompletableFuture<float[]> future = selectionCache.computeIfAbsent(key, ignored -> prepareSelectionPoints(selection, sliceY, context));
                    state.future = future;
                }
                float[] points = resolveFuture(state.future);
                if (points != null && points.length > 0) {
                    state.points = points;
                    emissions.add(new Emission(world, points, state));
                }
            }
        } else {
            session.selectionState.reset();
        }

        for (String cityId : session.cityViews.keySet()) {
            City city = cityManager.get(cityId);
            if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
                continue;
            }
            CityRenderState cityState = session.cityViews.computeIfAbsent(cityId, CityRenderState::new);
            for (int idx = 0; idx < city.cuboids.size(); idx++) {
                Cuboid cuboid = city.cuboids.get(idx);
                if (cuboid == null || cuboid.world == null) {
                    continue;
                }
                World cuboidWorld = Bukkit.getWorld(cuboid.world);
                if (cuboidWorld == null || cuboidWorld != world) {
                    continue;
                }
                YMode mode = resolveMode(cuboid, world);
                if (!withinViewDistance(location, cuboid, cuboidWorld, mode)) {
                    continue;
                }
                int sliceBucket = mode == YMode.FULL ? (int) Math.floor(location.getY()) : 0;
                Double sliceY = mode == YMode.FULL ? Math.floor(location.getY()) + 0.5 : null;
                long geometryHash = computeCuboidHash(cuboid);
                CityCacheKey key = new CityCacheKey(cityId, geometryHash, mode, sliceBucket);
                CuboidRenderState cuboidState = cityState.cuboids.computeIfAbsent(geometryHash, CuboidRenderState::new);
                if (!key.equals(cuboidState.key)) {
                    cuboidState.key = key;
                    cuboidState.cursor = 0;
                    cuboidState.points = null;
                    CompletableFuture<float[]> future = cityCache.computeIfAbsent(key, ignored -> prepareCuboidPoints(cuboid, mode, sliceY, context));
                    cuboidState.future = future;
                }
                float[] points = resolveFuture(cuboidState.future);
                if (points != null && points.length > 0) {
                    cuboidState.points = points;
                    emissions.add(new Emission(world, points, cuboidState));
                }
            }
        }

        int budget = config.maxPointsPerTick();
        for (Emission emission : emissions) {
            if (!emission.world.equals(world)) {
                continue;
            }
            float[] points = emission.points();
            if (points == null || points.length == 0) {
                continue;
            }
            int totalPoints = points.length / 3;
            if (totalPoints == 0) {
                continue;
            }
            int cursor = emission.state.cursor;
            if (cursor >= totalPoints) {
                cursor = 0;
            }
            int remaining = totalPoints - cursor;
            int allowance = Math.min(remaining, budget);
            if (allowance <= 0) {
                break;
            }
            renderer.emit(player, points, cursor, allowance);
            emission.state.cursor = (cursor + allowance) % totalPoints;
            budget -= allowance;
            if (budget <= 0) {
                break;
            }
        }
    }

    private CompletableFuture<float[]> prepareSelectionPoints(SelectionSnapshot snapshot,
                                                              Double sliceY,
                                                              SamplingContext context) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        Runnable job = () -> {
            try {
                List<Vec3> vectors = shapeSampler.sampleSelectionEdges(snapshot, snapshot.mode(), context.baseStep(), sliceY, context);
                future.complete(toFloatArray(vectors));
                logDebug("Selection points prepared: " + (vectors != null ? vectors.size() : 0));
            } catch (Throwable t) {
                future.completeExceptionally(t);
                plugin.getLogger().warning("Failed to prepare selection points: " + t.getMessage());
            }
        };
        if (config.asyncPrepare()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, job);
        } else {
            job.run();
        }
        return future;
    }

    private CompletableFuture<float[]> prepareCuboidPoints(Cuboid cuboid,
                                                           YMode mode,
                                                           Double sliceY,
                                                           SamplingContext context) {
        CompletableFuture<float[]> future = new CompletableFuture<>();
        Runnable job = () -> {
            try {
                List<Vec3> vectors = shapeSampler.sampleCuboidEdges(cuboid, mode, context.baseStep(), sliceY, context);
                future.complete(toFloatArray(vectors));
                logDebug("Cuboid points prepared: " + (vectors != null ? vectors.size() : 0));
            } catch (Throwable t) {
                future.completeExceptionally(t);
                plugin.getLogger().warning("Failed to prepare cuboid points: " + t.getMessage());
            }
        };
        if (config.asyncPrepare()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, job);
        } else {
            job.run();
        }
        return future;
    }

    private float[] resolveFuture(CompletableFuture<float[]> future) {
        if (future == null) {
            return null;
        }
        if (!future.isDone()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void ensureScheduler(PlayerSession session) {
        if (session.scheduler == null) {
            session.scheduler = new BudgetScheduler(plugin, this, session);
        }
        if (config != null && config.enabled()) {
            session.scheduler.start(config.refreshTicks());
        } else if (session.scheduler != null) {
            session.scheduler.stop();
        }
    }

    private void stopSession(PlayerSession session) {
        if (session.scheduler != null) {
            session.scheduler.stop();
            session.scheduler = null;
        }
        session.selectionState.reset();
        session.cityViews.clear();
    }

    private void markCityDirty(PlayerSession session, String cityId) {
        CityRenderState state = session.cityViews.computeIfAbsent(cityId, CityRenderState::new);
        state.markDirty();
    }

    private void invalidateCityCache(String cityId) {
        cityCache.keySet().removeIf(key -> key.cityId.equals(cityId));
    }

    private void invalidateAllCaches() {
        selectionCache.clear();
        cityCache.clear();
        for (PlayerSession session : sessions.values()) {
            session.selectionState.markDirty();
            for (CityRenderState state : session.cityViews.values()) {
                state.markDirty();
            }
        }
    }

    private void logDebug(String message) {
        if (!debugEnabled.get()) {
            return;
        }
        Logger logger = plugin.getLogger();
        logger.info("[Visualization] " + message);
    }

    private float[] toFloatArray(List<Vec3> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return new float[0];
        }
        float[] array = new float[vectors.size() * 3];
        for (int i = 0; i < vectors.size(); i++) {
            Vec3 vec = vectors.get(i);
            int base = i * 3;
            array[base] = (float) vec.x();
            array[base + 1] = (float) vec.y();
            array[base + 2] = (float) vec.z();
        }
        return array;
    }

    private YMode resolveMode(Cuboid cuboid, World world) {
        if (cuboid == null) {
            return YMode.SPAN;
        }
        if (cuboid.fullHeight || cuboid.isFullHeight(world)) {
            return YMode.FULL;
        }
        return YMode.SPAN;
    }

    private long computeCuboidHash(Cuboid cuboid) {
        if (cuboid == null) {
            return 0L;
        }
        long hash = 0xCBF29CE484222325L;
        hash = fnv(hash, cuboid.world != null ? cuboid.world : "");
        hash = fnv(hash, cuboid.minX);
        hash = fnv(hash, cuboid.minY);
        hash = fnv(hash, cuboid.minZ);
        hash = fnv(hash, cuboid.maxX);
        hash = fnv(hash, cuboid.maxY);
        hash = fnv(hash, cuboid.maxZ);
        hash = fnv(hash, cuboid.fullHeight ? 1 : 0);
        return hash;
    }

    private long fnv(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001B3L;
        return hash;
    }

    private long fnv(long hash, String value) {
        if (value == null) {
            return hash;
        }
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private double distanceToSelection(Location location, SelectionSnapshot selection) {
        if (location == null || selection == null) {
            return Double.POSITIVE_INFINITY;
        }
        double minX = Math.min(selection.minX(), selection.maxX());
        double maxX = Math.max(selection.minX(), selection.maxX()) + 1.0;
        double minY = Math.min(selection.minY(), selection.maxY());
        double maxY = Math.max(selection.minY(), selection.maxY()) + 1.0;
        double minZ = Math.min(selection.minZ(), selection.maxZ());
        double maxZ = Math.max(selection.minZ(), selection.maxZ()) + 1.0;
        return distanceToAabb(location.getX(), location.getY(), location.getZ(), minX, minY, minZ, maxX, maxY, maxZ);
    }

    private boolean withinViewDistance(Location location, Cuboid cuboid, World world, YMode mode) {
        if (location == null || cuboid == null || world == null) {
            return false;
        }
        double minX = Math.min(cuboid.minX, cuboid.maxX);
        double maxX = Math.max(cuboid.minX, cuboid.maxX) + 1.0;
        double minZ = Math.min(cuboid.minZ, cuboid.maxZ);
        double maxZ = Math.max(cuboid.minZ, cuboid.maxZ) + 1.0;
        double minY;
        double maxY;
        if (mode == YMode.FULL) {
            minY = world.getMinHeight();
            maxY = world.getMaxHeight();
        } else {
            minY = Math.min(cuboid.minY, cuboid.maxY);
            maxY = Math.max(cuboid.minY, cuboid.maxY) + 1.0;
        }
        double distance = distanceToAabb(location.getX(), location.getY(), location.getZ(), minX, minY, minZ, maxX, maxY, maxZ);
        return distance <= config.viewDistance();
    }

    private double distanceToAabb(double px,
                                   double py,
                                   double pz,
                                   double minX,
                                   double minY,
                                   double minZ,
                                   double maxX,
                                   double maxY,
                                   double maxZ) {
        double dx = Math.max(Math.max(minX - px, 0.0), px - maxX);
        double dy = Math.max(Math.max(minY - py, 0.0), py - maxY);
        double dz = Math.max(Math.max(minZ - pz, 0.0), pz - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private record SelectionCacheKey(UUID playerId, long selectionHash, YMode mode, int sliceBucket) {
    }

    private record CityCacheKey(String cityId, long geometryHash, YMode mode, int sliceBucket) {
    }

    private static final class Emission {
        private final World world;
        private final float[] points;
        private final RenderState state;

        private Emission(World world, float[] points, RenderState state) {
            this.world = world;
            this.points = points;
            this.state = state;
        }

        public World world() {
            return world;
        }

        public float[] points() {
            return points;
        }
    }

    private abstract static class RenderState {
        int cursor;
    }

    private static final class SelectionRenderState extends RenderState {
        SelectionCacheKey key;
        float[] points;
        CompletableFuture<float[]> future;
        boolean active;

        void markDirty() {
            key = null;
            cursor = 0;
            points = null;
            future = null;
            active = false;
        }

        void reset() {
            markDirty();
        }
    }

    private static final class CuboidRenderState extends RenderState {
        CityCacheKey key;
        float[] points;
        CompletableFuture<float[]> future;

        private CuboidRenderState(long ignored) {
        }

        void reset() {
            key = null;
            cursor = 0;
            points = null;
            future = null;
        }
    }

    private static final class CityRenderState {
        final String cityId;
        final Map<Long, CuboidRenderState> cuboids = new HashMap<>();

        private CityRenderState(String cityId) {
            this.cityId = cityId;
        }

        void markDirty() {
            for (CuboidRenderState state : cuboids.values()) {
                state.reset();
            }
        }
    }

    static final class PlayerSession {
        final UUID playerId;
        final Map<String, CityRenderState> cityViews = new ConcurrentHashMap<>();
        final SelectionRenderState selectionState = new SelectionRenderState();
        BudgetScheduler scheduler;

        PlayerSession(UUID playerId) {
            this.playerId = playerId;
        }

        void enableCity(String cityId) {
            cityViews.computeIfAbsent(cityId, CityRenderState::new);
        }

        void disableCity(String cityId) {
            cityViews.remove(cityId);
        }

        boolean isCityActive(String cityId) {
            return cityViews.containsKey(cityId);
        }

        boolean isEmpty() {
            return cityViews.isEmpty() && !selectionState.active;
        }
    }
}
