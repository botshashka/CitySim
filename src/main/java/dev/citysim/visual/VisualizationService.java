package dev.citysim.visual;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.visual.ShapeSampler.CuboidSnapshot;
import dev.citysim.visual.ShapeSampler.SelectionSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Coordinates the selection and city cuboid visualizations.
 */
public final class VisualizationService {

    private final JavaPlugin plugin;
    private final CityManager cityManager;
    private final Logger logger;

    private volatile VisualizationSettings settings;
    private final ParticleRenderer renderer;
    private final BudgetScheduler scheduler;

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> cityViewers = new ConcurrentHashMap<>();

    private SelectionTracker selectionTracker;

    public VisualizationService(JavaPlugin plugin,
                                CityManager cityManager,
                                VisualizationSettings settings) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.renderer = new ParticleRenderer(settings.particle(), settings.dustColor());
        this.scheduler = new BudgetScheduler(plugin, this);
    }

    public void setSelectionTracker(SelectionTracker tracker) {
        this.selectionTracker = tracker;
    }

    public VisualizationSettings settings() {
        return settings;
    }

    public void reload(VisualizationSettings updated) {
        this.settings = updated;
        for (PlayerSession session : sessions.values()) {
            session.selectionBuffer.markDirty();
            for (CityViewState state : session.cityViews.values()) {
                state.markDirty();
            }
        }
    }

    public void enableCityView(Player player, String cityId) {
        if (!settings.enabled()) {
            return;
        }
        PlayerSession session = session(player);
        CityViewState state = session.cityViews.computeIfAbsent(cityId, CityViewState::new);
        registerViewer(cityId, player.getUniqueId());
        state.enabled = true;
        rebuildCityView(state);
        scheduler.ensure(session, settings.refreshTicks());
    }

    public void disableCityView(Player player, String cityId) {
        UUID playerId = player.getUniqueId();
        PlayerSession session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        CityViewState state = session.cityViews.remove(cityId);
        unregisterViewer(cityId, playerId);
        if (state != null) {
            state.clear();
        }
        if (!hasActiveSelection(player) && !hasActiveCityViews(session)) {
            scheduler.cancel(playerId);
            sessions.remove(playerId, session);
        } else {
            scheduler.ensure(session, settings.refreshTicks());
        }
    }

    public void disableAllCityViews(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        for (String cityId : new ArrayList<>(session.cityViews.keySet())) {
            disableCityView(player, cityId);
        }
    }

    public boolean isCityViewEnabled(Player player, String cityId) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        CityViewState state = session.cityViews.get(cityId);
        return state != null && state.enabled;
    }

    public Collection<Player> getCityViewers(String cityId) {
        List<UUID> ids = cityViewers.get(cityId);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Player> players = new ArrayList<>();
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    public void updateCityView(Collection<Player> viewers, String cityId) {
        if (!settings.enabled()) {
            return;
        }
        if (viewers == null) {
            viewers = getCityViewers(cityId);
        }
        for (Player player : viewers) {
            PlayerSession session = session(player);
            CityViewState state = session.cityViews.computeIfAbsent(cityId, CityViewState::new);
            rebuildCityView(state);
            scheduler.ensure(session, settings.refreshTicks());
        }
    }

    public void updateSelectionView(Player player) {
        if (!settings.enabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerSession existing = sessions.get(playerId);
        boolean selectionActive = hasActiveSelection(player);
        boolean cityViewsActive = existing != null && hasActiveCityViews(existing);

        if (!selectionActive && !cityViewsActive) {
            if (existing != null) {
                existing.selectionBuffer.clear();
                scheduler.cancel(playerId);
                sessions.remove(playerId);
            }
            return;
        }

        PlayerSession session = existing != null ? existing : session(player);
        session.selectionBuffer.markDirty();
        scheduler.ensure(session, settings.refreshTicks());
    }

    public void shutdown() {
        scheduler.shutdown();
        sessions.clear();
        cityViewers.clear();
    }

    void render(UUID playerId) {
        if (!settings.enabled()) {
            scheduler.cancel(playerId);
            sessions.remove(playerId);
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            scheduler.cancel(playerId);
            return;
        }
        PlayerSession session = sessions.get(playerId);
        if (session == null) {
            scheduler.cancel(playerId);
            return;
        }

        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        int budgetRemaining = settings.maxPointsPerTick();
        if (budgetRemaining <= 0) {
            return;
        }

        boolean budgetExhausted = false;

        // Selection
        budgetRemaining = renderSelection(player, session, location, world, budgetRemaining);
        if (budgetRemaining <= 0) {
            budgetExhausted = true;
        } else {
            // City cuboids
            for (CityViewState state : session.cityViews.values()) {
                if (budgetRemaining <= 0) {
                    budgetExhausted = true;
                    break;
                }
                if (!state.enabled) {
                    continue;
                }
                CityWorldBuffer buffer = state.forWorld(world.getName());
                if (buffer == null || buffer.cuboids.isEmpty()) {
                    continue;
                }
                double distance = nearestDistance(location, buffer.cuboids);
                if (distance > settings.viewDistance()) {
                    continue;
                }
                if (buffer.shouldPrepare(distance)) {
                    scheduleCityPrepare(buffer, distance);
                }
                budgetRemaining = emitFromBuffer(player, buffer, budgetRemaining);
            }
        }

        descheduleIfIdle(player, session);

        if (budgetExhausted) {
            return;
        }
    }

    private int renderSelection(Player player,
                                PlayerSession session,
                                Location location,
                                World world,
                                int budgetRemaining) {
        if (selectionTracker == null) {
            return budgetRemaining;
        }
        Optional<SelectionTracker.Selection> state = selectionTracker.getIfPresent(player);
        if (state.isEmpty()) {
            session.selectionBuffer.clear();
            return budgetRemaining;
        }
        SelectionTracker.Selection selection = state.get();
        if (!selection.ready()) {
            session.selectionBuffer.clear();
            return budgetRemaining;
        }
        if (!Objects.equals(selection.world(), world)) {
            session.selectionBuffer.clear();
            return budgetRemaining;
        }

        SelectionSnapshot snapshot = SelectionTracker.toShapeSnapshot(selection);
        if (snapshot == null) {
            session.selectionBuffer.clear();
            return budgetRemaining;
        }

        double distance = nearestDistance(location, snapshot);
        if (distance > settings.viewDistance()) {
            session.selectionBuffer.clear();
            return budgetRemaining;
        }

        SelectionBuffer buffer = session.selectionBuffer;
        int sliceBucket = selection.mode() == YMode.FULL ? (int) Math.floor(location.getY()) : Integer.MIN_VALUE;
        long selectionHash = selection.hash();
        if (buffer.shouldPrepare(selectionHash, selection.mode(), sliceBucket, distance)) {
            scheduleSelectionPrepare(buffer, snapshot, selection.mode(), sliceBucket, distance, location.getY(), selectionHash);
        }
        return emitFromBuffer(player, buffer, budgetRemaining);
    }

    private void scheduleSelectionPrepare(SelectionBuffer buffer,
                                          SelectionSnapshot snapshot,
                                          YMode mode,
                                          int sliceBucket,
                                          double distance,
                                          double playerY,
                                          long selectionHash) {
        if (!buffer.beginPrepare()) {
            return;
        }
        double sliceY = mode == YMode.FULL ? Math.floor(playerY) + 0.5 : Double.NaN;
        Runnable work = () -> {
            try {
                SamplingContext context = new SamplingContext(
                        distance,
                        settings.viewDistance(),
                        settings.farDistanceStepMultiplier(),
                        settings.maxPointsPerTick(),
                        settings.jitter(),
                        settings.sliceThickness(),
                        snapshot.hashCode()
                );
                List<Vec3> points = ShapeSampler.sampleSelectionEdges(
                        snapshot,
                        mode,
                        settings.baseStep(),
                        mode == YMode.FULL ? sliceY : null,
                        context
                );
                Bukkit.getScheduler().runTask(plugin, () -> buffer.finishPrepare(points, selectionHash, mode, sliceBucket, distance));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(plugin, buffer::failPrepare);
                if (settings.debug()) {
                    logger.warning("Selection visualization failed: " + t.getMessage());
                }
            }
        };
        if (settings.asyncPrepare()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        } else {
            work.run();
        }
    }

    private void scheduleCityPrepare(CityWorldBuffer buffer, double distance) {
        if (!buffer.beginPrepare()) {
            return;
        }
        int shapeKey = buffer.cuboids.hashCode();
        Runnable work = () -> {
            try {
                SamplingContext context = new SamplingContext(
                        distance,
                        settings.viewDistance(),
                        settings.farDistanceStepMultiplier(),
                        settings.maxPointsPerTick(),
                        settings.jitter(),
                        settings.sliceThickness(),
                        buffer.worldName.hashCode()
                );
                List<Vec3> aggregated = new ArrayList<>();
                for (CuboidSnapshot cuboid : buffer.cuboids) {
                    aggregated.addAll(ShapeSampler.sampleCuboidEdges(
                            cuboid,
                            YMode.SPAN,
                            settings.baseStep(),
                            null,
                            context
                    ));
                }
                Bukkit.getScheduler().runTask(plugin, () -> buffer.finishPrepare(aggregated, shapeKey, distance));
            } catch (Throwable t) {
                Bukkit.getScheduler().runTask(plugin, buffer::failPrepare);
                if (settings.debug()) {
                    logger.warning("City visualization failed: " + t.getMessage());
                }
            }
        };
        if (settings.asyncPrepare()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        } else {
            work.run();
        }
    }

    private int emitFromBuffer(Player player, ShapeBuffer buffer, int budgetRemaining) {
        List<Vec3> points = buffer.points;
        if (points.isEmpty()) {
            return budgetRemaining;
        }
        int emitted = 0;
        int size = points.size();
        int cursor = buffer.cursor;
        while (emitted < budgetRemaining && emitted < size) {
            Vec3 point = points.get(cursor);
            renderer.emit(player, point);
            emitted++;
            cursor++;
            if (cursor >= size) {
                cursor = 0;
            }
        }
        buffer.cursor = cursor;
        return budgetRemaining - emitted;
    }

    private void rebuildCityView(CityViewState state) {
        City city = cityManager.get(state.cityId);
        if (city == null) {
            state.clear();
            return;
        }
        Map<String, List<CuboidSnapshot>> byWorld = new ConcurrentHashMap<>();
        if (city.cuboids != null) {
            for (Cuboid cuboid : city.cuboids) {
                if (cuboid == null || cuboid.world == null) {
                    continue;
                }
                World world = Bukkit.getWorld(cuboid.world);
                if (world == null) {
                    continue;
                }
                CuboidSnapshot snapshot = toSnapshot(cuboid);
                if (snapshot != null) {
                    byWorld.computeIfAbsent(world.getName(), ignored -> new CopyOnWriteArrayList<>()).add(snapshot);
                }
            }
        }
        state.setCuboids(byWorld);
    }

    private CuboidSnapshot toSnapshot(Cuboid cuboid) {
        double minX = Math.min(cuboid.minX, cuboid.maxX);
        double minY = Math.min(cuboid.minY, cuboid.maxY);
        double minZ = Math.min(cuboid.minZ, cuboid.maxZ);
        double maxX = Math.max(cuboid.minX, cuboid.maxX) + 1;
        double maxY = Math.max(cuboid.minY, cuboid.maxY) + 1;
        double maxZ = Math.max(cuboid.minZ, cuboid.maxZ) + 1;
        return new CuboidSnapshot(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private PlayerSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), PlayerSession::new);
    }

    private boolean hasActiveSelection(Player player) {
        if (selectionTracker == null) {
            return false;
        }
        return selectionTracker.getIfPresent(player)
                .map(SelectionTracker.Selection::ready)
                .orElse(false);
    }

    private boolean hasActiveCityViews(PlayerSession session) {
        if (session == null) {
            return false;
        }
        for (CityViewState state : session.cityViews.values()) {
            if (!state.enabled) {
                continue;
            }
            if (state.hasRenderable()) {
                return true;
            }
        }
        return false;
    }

    private void descheduleIfIdle(Player player, PlayerSession session) {
        if (session == null) {
            return;
        }
        if (!hasActiveSelection(player) && !hasActiveCityViews(session)) {
            session.selectionBuffer.clear();
            scheduler.cancel(player.getUniqueId());
            sessions.remove(player.getUniqueId(), session);
        }
    }

    private void registerViewer(String cityId, UUID playerId) {
        cityViewers.computeIfAbsent(cityId, ignored -> new CopyOnWriteArrayList<>()).add(playerId);
    }

    private void unregisterViewer(String cityId, UUID playerId) {
        List<UUID> ids = cityViewers.get(cityId);
        if (ids != null) {
            ids.remove(playerId);
            if (ids.isEmpty()) {
                cityViewers.remove(cityId);
            }
        }
    }

    private double nearestDistance(Location location, SelectionSnapshot bounds) {
        double px = location.getX();
        double py = location.getY();
        double pz = location.getZ();
        double dx = clampToBox(px, bounds.minX(), bounds.maxX());
        double dy = clampToBox(py, bounds.minY(), bounds.maxY());
        double dz = clampToBox(pz, bounds.minZ(), bounds.maxZ());
        double diffX = px - dx;
        double diffY = py - dy;
        double diffZ = pz - dz;
        return Math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ);
    }

    private double nearestDistance(Location location, CuboidSnapshot bounds) {
        double px = location.getX();
        double py = location.getY();
        double pz = location.getZ();
        double dx = clampToBox(px, bounds.minX(), bounds.maxX());
        double dy = clampToBox(py, bounds.minY(), bounds.maxY());
        double dz = clampToBox(pz, bounds.minZ(), bounds.maxZ());
        double diffX = px - dx;
        double diffY = py - dy;
        double diffZ = pz - dz;
        return Math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ);
    }

    private double nearestDistance(Location location, List<CuboidSnapshot> cuboids) {
        double best = Double.MAX_VALUE;
        for (CuboidSnapshot snapshot : cuboids) {
            double dist = nearestDistance(location, snapshot);
            if (dist < best) {
                best = dist;
            }
        }
        return best;
    }

    private double clampToBox(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    static final class PlayerSession {
        final UUID playerId;
        final SelectionBuffer selectionBuffer = new SelectionBuffer();
        final Map<String, CityViewState> cityViews = new ConcurrentHashMap<>();

        PlayerSession(UUID playerId) {
            this.playerId = playerId;
        }
    }

    private abstract static class ShapeBuffer {
        volatile List<Vec3> points = Collections.emptyList();
        volatile boolean dirty = true;
        volatile boolean preparing = false;
        volatile long key = Long.MIN_VALUE;
        volatile int sliceBucket = Integer.MIN_VALUE;
        volatile int distanceBucket = Integer.MIN_VALUE;
        volatile YMode mode = null;
        int cursor = 0;

        void markDirty() {
            dirty = true;
        }

        void clear() {
            points = Collections.emptyList();
            cursor = 0;
            dirty = true;
            preparing = false;
        }

        boolean beginPrepare() {
            if (!dirty || preparing) {
                return false;
            }
            preparing = true;
            return true;
        }
    }

    private static final class SelectionBuffer extends ShapeBuffer {

        boolean shouldPrepare(long hash, YMode mode, int sliceBucket, double distance) {
            int distanceBucket = (int) Math.floor(distance / 4.0);
            if (!dirty && hash == this.key && mode == this.mode && sliceBucket == this.sliceBucket && distanceBucket == this.distanceBucket) {
                return false;
            }
            this.key = hash;
            this.mode = mode;
            this.sliceBucket = sliceBucket;
            this.distanceBucket = distanceBucket;
            dirty = true;
            return true;
        }

        void finishPrepare(List<Vec3> points, long key, YMode mode, int sliceBucket, double distance) {
            this.points = points != null ? points : Collections.emptyList();
            this.cursor = 0;
            this.key = key;
            this.mode = mode;
            this.sliceBucket = sliceBucket;
            this.distanceBucket = (int) Math.floor(distance / 4.0);
            this.dirty = false;
            this.preparing = false;
        }

        void failPrepare() {
            this.points = Collections.emptyList();
            this.cursor = 0;
            this.dirty = false;
            this.preparing = false;
        }
    }

    private static final class CityWorldBuffer extends ShapeBuffer {
        final String worldName;
        volatile List<CuboidSnapshot> cuboids = Collections.emptyList();

        CityWorldBuffer(String worldName) {
            this.worldName = worldName;
        }

        boolean shouldPrepare(double distance) {
            int distanceBucket = (int) Math.floor(distance / 4.0);
            if (!dirty && distanceBucket == this.distanceBucket) {
                return false;
            }
            this.distanceBucket = distanceBucket;
            dirty = true;
            return true;
        }

        void finishPrepare(List<Vec3> points, long key, double distance) {
            this.points = points != null ? points : Collections.emptyList();
            this.cursor = 0;
            this.key = key;
            this.distanceBucket = (int) Math.floor(distance / 4.0);
            this.dirty = false;
            this.preparing = false;
        }

        void failPrepare() {
            this.points = Collections.emptyList();
            this.cursor = 0;
            this.dirty = false;
            this.preparing = false;
        }
    }

    private static final class CityViewState {
        final String cityId;
        final Map<String, CityWorldBuffer> buffers = new ConcurrentHashMap<>();
        volatile boolean enabled = true;

        CityViewState(String cityId) {
            this.cityId = cityId;
        }

        void setCuboids(Map<String, List<CuboidSnapshot>> grouped) {
            buffers.clear();
            for (Map.Entry<String, List<CuboidSnapshot>> entry : grouped.entrySet()) {
                CityWorldBuffer buffer = new CityWorldBuffer(entry.getKey());
                buffer.cuboids = List.copyOf(entry.getValue());
                buffer.markDirty();
                buffers.put(entry.getKey(), buffer);
            }
        }

        CityWorldBuffer forWorld(String world) {
            return buffers.get(world);
        }

        void markDirty() {
            for (CityWorldBuffer buffer : buffers.values()) {
                buffer.markDirty();
            }
        }

        void clear() {
            for (CityWorldBuffer buffer : buffers.values()) {
                buffer.clear();
            }
            buffers.clear();
        }

        boolean hasRenderable() {
            for (CityWorldBuffer buffer : buffers.values()) {
                if (buffer.preparing) {
                    return true;
                }
                if (buffer.cuboids != null && !buffer.cuboids.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
