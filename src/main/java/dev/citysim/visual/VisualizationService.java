package dev.citysim.visual;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class VisualizationService {
    private final JavaPlugin plugin;
    private final CityManager cityManager;
    private volatile VisualizationSettings settings;
    private final ShapeSampler sampler = new ShapeSampler();
    private ParticleRenderer renderer;
    private final BudgetScheduler scheduler;
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private SelectionTracker selectionTracker;

    public VisualizationService(JavaPlugin plugin, CityManager cityManager, VisualizationSettings settings) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.settings = settings;
        this.renderer = new ParticleRenderer(settings);
        this.scheduler = new BudgetScheduler(plugin, this);
    }

    public void setSelectionTracker(SelectionTracker tracker) {
        this.selectionTracker = tracker;
    }

    public VisualizationSettings getSettings() {
        return settings;
    }

    public void reload(VisualizationSettings newSettings) {
        this.settings = newSettings;
        this.renderer = new ParticleRenderer(newSettings);
        for (PlayerSession session : sessions.values()) {
            session.invalidateCaches();
        }
        scheduler.reload();
    }

    public void enableCityView(Player player, String cityId) {
        if (player == null || cityId == null) {
            return;
        }
        City city = cityManager.get(cityId);
        if (city == null) {
            return;
        }
        PlayerSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerSession::new);
        CityView view = session.cityViews.computeIfAbsent(cityId, CityView::new);
        view.update(city);
        ensureSessionState(player.getUniqueId(), session);
    }

    public void disableCityView(Player player, String cityId) {
        if (player == null || cityId == null) {
            return;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.cityViews.remove(cityId) != null) {
            ensureSessionState(player.getUniqueId(), session);
        }
    }

    public boolean isCityViewEnabled(Player player, String cityId) {
        if (player == null || cityId == null) {
            return false;
        }
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        return session.cityViews.containsKey(cityId);
    }

    public void updateCityView(Collection<Player> viewers, String cityId) {
        if (viewers == null || cityId == null) {
            return;
        }
        City city = cityManager.get(cityId);
        if (city == null) {
            return;
        }
        for (Player viewer : viewers) {
            if (viewer == null) {
                continue;
            }
            PlayerSession session = sessions.get(viewer.getUniqueId());
            if (session == null) {
                continue;
            }
            CityView view = session.cityViews.get(cityId);
            if (view == null) {
                continue;
            }
            view.update(city);
            ensureSessionState(viewer.getUniqueId(), session);
        }
    }

    public void updateSelectionView(Player player) {
        if (player == null) {
            return;
        }
        PlayerSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerSession::new);
        Optional<SelectionTracker.SelectionSnapshot> snapshot = selectionTracker == null ? Optional.empty() : selectionTracker.snapshot(player);
        session.selectionView.update(snapshot.orElse(null));
        ensureSessionState(player.getUniqueId(), session);
    }

    public void handlePlayerQuit(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerSession session = sessions.remove(playerId);
        if (session != null) {
            scheduler.stop(playerId);
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        sessions.clear();
    }

    void ensureSessionState(UUID playerId, PlayerSession session) {
        if (session == null) {
            scheduler.stop(playerId);
            sessions.remove(playerId);
            return;
        }
        if (session.hasActiveShapes()) {
            scheduler.ensureTask(session);
        } else {
            scheduler.stop(playerId);
            sessions.remove(playerId);
        }
    }

    int renderSelection(PlayerSession session, Player player, VisualizationSettings settings, int budget) {
        return session.selectionView.emit(player, settings, this, budget);
    }

    int renderCityShapes(PlayerSession session, Player player, VisualizationSettings settings, int budget) {
        if (session.cityViews.isEmpty() || budget <= 0) {
            return 0;
        }
        int emitted = 0;
        for (CityView view : session.cityViews.values()) {
            emitted += view.emit(player, settings, this, budget - emitted);
            if (emitted >= budget) {
                break;
            }
        }
        return emitted;
    }

    CompletableFuture<List<Vec3>> prepareSelectionPoints(SelectionBounds bounds,
                                                          YMode mode,
                                                          Double sliceY,
                                                          SamplingContext context) {
        return submit(() -> sampler.sampleSelectionEdges(bounds, mode, settings.baseStep(), sliceY, context));
    }

    CompletableFuture<List<Vec3>> prepareCuboidPoints(CityCuboidSnapshot cuboid,
                                                       YMode mode,
                                                       Double sliceY,
                                                       SamplingContext context) {
        Cuboid copy = cuboid.toCuboid();
        return submit(() -> sampler.sampleCuboidEdges(copy, mode, settings.baseStep(), sliceY, context));
    }

    private CompletableFuture<List<Vec3>> submit(SupplierWithException<List<Vec3>> supplier) {
        CompletableFuture<List<Vec3>> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.complete(Collections.emptyList());
            }
        };
        if (settings.asyncPrepare()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
        return future;
    }

    ParticleRenderer renderer() {
        return renderer;
    }

    double viewDistance() {
        return settings.viewDistance();
    }

    static double distanceToBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Location location) {
        double minXEdge = minX;
        double maxXEdge = maxX + 1.0;
        double minYEdge = minY;
        double maxYEdge = maxY + 1.0;
        double minZEdge = minZ;
        double maxZEdge = maxZ + 1.0;
        double px = location.getX();
        double py = location.getY();
        double pz = location.getZ();
        double dx = clamp(px, minXEdge, maxXEdge) - px;
        double dy = clamp(py, minYEdge, maxYEdge) - py;
        double dz = clamp(pz, minZEdge, maxZEdge) - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    static final class PlayerSession {
        private final UUID playerId;
        private final SelectionView selectionView = new SelectionView();
        private final Map<String, CityView> cityViews = new ConcurrentHashMap<>();

        private PlayerSession(UUID playerId) {
            this.playerId = playerId;
        }

        UUID playerId() {
            return playerId;
        }

        boolean hasActiveShapes() {
            if (selectionView.active()) {
                return true;
            }
            for (CityView view : cityViews.values()) {
                if (view.active()) {
                    return true;
                }
            }
            return false;
        }

        void invalidateCaches() {
            selectionView.invalidate();
            for (CityView view : cityViews.values()) {
                view.invalidate();
            }
        }
    }

    static final class SelectionView {
        private SelectionTracker.SelectionSnapshot snapshot;
        private final SelectionShape shape = new SelectionShape();

        void update(SelectionTracker.SelectionSnapshot snapshot) {
            this.snapshot = snapshot;
            shape.update(snapshot != null ? snapshot.bounds() : null);
        }

        boolean active() {
            return snapshot != null && snapshot.ready();
        }

        int emit(Player player, VisualizationSettings settings, VisualizationService service, int budget) {
            if (!active() || budget <= 0) {
                return 0;
            }
            if (player.getWorld() == null || snapshot.world() == null) {
                return 0;
            }
            if (!Objects.equals(player.getWorld().getUID(), snapshot.world().getUID())) {
                return 0;
            }
            return shape.emit(player, snapshot, settings, service, budget);
        }

        void invalidate() {
            shape.clear();
        }
    }

    static final class CityView {
        private final String cityId;
        private final List<CityShape> shapes = new CopyOnWriteArrayList<>();

        CityView(String cityId) {
            this.cityId = cityId;
        }

        void update(City city) {
            shapes.clear();
            if (city == null || city.cuboids == null) {
                return;
            }
            for (int i = 0; i < city.cuboids.size(); i++) {
                Cuboid cuboid = city.cuboids.get(i);
                if (cuboid == null || cuboid.world == null) {
                    continue;
                }
                shapes.add(new CityShape(cityId, i, CityCuboidSnapshot.from(cityId, i, cuboid)));
            }
        }

        int emit(Player player, VisualizationSettings settings, VisualizationService service, int budget) {
            if (budget <= 0) {
                return 0;
            }
            int emitted = 0;
            for (CityShape shape : shapes) {
                emitted += shape.emit(player, settings, service, budget - emitted);
                if (emitted >= budget) {
                    break;
                }
            }
            return emitted;
        }

        boolean active() {
            return !shapes.isEmpty();
        }

        void invalidate() {
            for (CityShape shape : shapes) {
                shape.clear();
            }
        }
    }

    static final class SelectionShape extends ShapeCache {
        private SelectionBounds bounds;

        void update(SelectionBounds bounds) {
            this.bounds = bounds;
            clear();
        }

        int emit(Player player,
                 SelectionTracker.SelectionSnapshot snapshot,
                 VisualizationSettings settings,
                 VisualizationService service,
                 int budget) {
            if (bounds == null || budget <= 0) {
                return 0;
            }
            Location location = player.getLocation();
            int sliceBucket = snapshot.mode() == YMode.FULL ? (int) Math.floor(location.getY()) : -1;
            Double sliceY = snapshot.mode() == YMode.FULL ? Math.floor(location.getY()) + 0.5 : null;
            CacheKey key = new CacheKey(bounds.hash(), snapshot.mode(), sliceBucket);
            updateKey(key);
            double distance = distanceToBounds(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), location);
            if (distance > settings.viewDistance()) {
                return 0;
            }
            SamplingContext context = new SamplingContext(distance,
                    settings.viewDistance(),
                    settings.farDistanceStepMultiplier(),
                    settings.maxPointsPerTick(),
                    settings.jitter(),
                    settings.sliceThickness(),
                    bounds.hash() ^ sliceBucket);
            poll();
            if (!hasPoints()) {
                if (!hasPending()) {
                    CompletableFuture<List<Vec3>> future = service.prepareSelectionPoints(bounds, snapshot.mode(), sliceY, context);
                    setPending(future);
                }
                return 0;
            }
            return emitPoints(player, service.renderer(), budget);
        }
    }

    static final class CityShape extends ShapeCache {
        private final CityCuboidSnapshot cuboid;

        CityShape(String cityId, int index, CityCuboidSnapshot cuboid) {
            this.cuboid = cuboid;
            updateKey(new CacheKey(cuboid.hash(), YMode.SPAN, -1));
        }

        int emit(Player player, VisualizationSettings settings, VisualizationService service, int budget) {
            if (budget <= 0) {
                return 0;
            }
            if (cuboid.world() == null) {
                return 0;
            }
            World playerWorld = player.getWorld();
            if (playerWorld == null || !playerWorld.getName().equals(cuboid.world())) {
                return 0;
            }
            Location location = player.getLocation();
            double distance = distanceToBounds(cuboid.minX(), cuboid.minY(), cuboid.minZ(), cuboid.maxX(), cuboid.maxY(), cuboid.maxZ(), location);
            if (distance > settings.viewDistance()) {
                return 0;
            }
            SamplingContext context = new SamplingContext(distance,
                    settings.viewDistance(),
                    settings.farDistanceStepMultiplier(),
                    settings.maxPointsPerTick(),
                    settings.jitter(),
                    settings.sliceThickness(),
                    cuboid.hash());
            poll();
            if (!hasPoints()) {
                if (!hasPending()) {
                    CompletableFuture<List<Vec3>> future = service.prepareCuboidPoints(cuboid, YMode.SPAN, null, context);
                    setPending(future);
                }
                return 0;
            }
            return emitPoints(player, service.renderer(), budget);
        }
    }

    static final class CacheKey {
        private final long hash;
        private final YMode mode;
        private final int sliceBucket;

        CacheKey(long hash, YMode mode, int sliceBucket) {
            this.hash = hash;
            this.mode = mode;
            this.sliceBucket = sliceBucket;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey key)) return false;
            return hash == key.hash && mode == key.mode && sliceBucket == key.sliceBucket;
        }

        @Override
        public int hashCode() {
            return Objects.hash(hash, mode, sliceBucket);
        }
    }

    static class ShapeCache {
        private CacheKey key;
        private List<Vec3> points = Collections.emptyList();
        private int cursor;
        private CompletableFuture<List<Vec3>> pending;

        void updateKey(CacheKey key) {
            if (!Objects.equals(this.key, key)) {
                this.key = key;
                clear();
            }
        }

        void clear() {
            points = Collections.emptyList();
            cursor = 0;
            pending = null;
        }

        boolean hasPoints() {
            return points != null && !points.isEmpty();
        }

        boolean hasPending() {
            return pending != null && !pending.isDone();
        }

        void setPending(CompletableFuture<List<Vec3>> future) {
            this.pending = future;
        }

        void poll() {
            if (pending == null || !pending.isDone()) {
                return;
            }
            try {
                List<Vec3> result = pending.get();
                if (result == null) {
                    result = Collections.emptyList();
                }
                this.points = result;
                this.cursor = 0;
            } catch (Exception ex) {
                this.points = Collections.emptyList();
                this.cursor = 0;
            }
            pending = null;
        }

        int emitPoints(Player player, ParticleRenderer renderer, int budget) {
            if (!hasPoints() || budget <= 0) {
                return 0;
            }
            int emitted = renderer.emit(player, points, cursor, budget);
            cursor += emitted;
            if (cursor >= points.size()) {
                cursor = 0;
            }
            return emitted;
        }
    }

    record CityCuboidSnapshot(String cityId,
                              int index,
                              String world,
                              int minX,
                              int minY,
                              int minZ,
                              int maxX,
                              int maxY,
                              int maxZ,
                              boolean fullHeight,
                              long hash) {
        static CityCuboidSnapshot from(String cityId, int index, Cuboid cuboid) {
            long hash = Objects.hash(cityId,
                    index,
                    cuboid.world,
                    cuboid.minX,
                    cuboid.minY,
                    cuboid.minZ,
                    cuboid.maxX,
                    cuboid.maxY,
                    cuboid.maxZ,
                    cuboid.fullHeight);
            return new CityCuboidSnapshot(cityId,
                    index,
                    cuboid.world,
                    cuboid.minX,
                    cuboid.minY,
                    cuboid.minZ,
                    cuboid.maxX,
                    cuboid.maxY,
                    cuboid.maxZ,
                    cuboid.fullHeight,
                    hash);
        }

        Cuboid toCuboid() {
            Cuboid copy = new Cuboid();
            copy.world = world;
            copy.minX = minX;
            copy.minY = minY;
            copy.minZ = minZ;
            copy.maxX = maxX;
            copy.maxY = maxY;
            copy.maxZ = maxZ;
            copy.fullHeight = fullHeight;
            return copy;
        }
    }

    @FunctionalInterface
    interface SupplierWithException<T> {
        T get() throws Exception;
    }
}
