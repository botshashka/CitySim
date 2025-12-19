package dev.citysim.api.internal;

import dev.citysim.api.CityAreaSnapshot;
import dev.citysim.api.CityEconomySnapshot;
import dev.citysim.api.CityLifecycleListener;
import dev.citysim.api.CityProsperitySnapshot;
import dev.citysim.api.CitySimApi;
import dev.citysim.api.CitySnapshot;
import dev.citysim.api.CityStatsListener;
import dev.citysim.api.CityStatsSnapshot;
import dev.citysim.api.ListenerSubscription;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.CityManagerListener;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.stats.ProsperityBreakdown;
import dev.citysim.stats.StatsService;
import dev.citysim.stats.StatsUpdateListener;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Default implementation exposed as a Bukkit service.
 */
public class CitySimApiImpl implements CitySimApi, CityManagerListener, StatsUpdateListener, Listener {

    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private final List<LifecycleHandle> lifecycleHandles = new CopyOnWriteArrayList<>();
    private final List<StatsHandle> statsHandles = new CopyOnWriteArrayList<>();

    public CitySimApiImpl(Plugin plugin, CityManager cityManager, StatsService statsService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cityManager = Objects.requireNonNull(cityManager, "cityManager");
        this.statsService = Objects.requireNonNull(statsService, "statsService");
        cityManager.addListener(this);
        statsService.addStatsUpdateListener(this);
    }

    public void shutdown() {
        cityManager.removeListener(this);
        statsService.removeStatsUpdateListener(this);
        for (LifecycleHandle handle : List.copyOf(lifecycleHandles)) {
            handle.unregister();
        }
        for (StatsHandle handle : List.copyOf(statsHandles)) {
            handle.unregister();
        }
    }

    @Override
    public Collection<CitySnapshot> getCities() {
        List<CitySnapshot> snapshots = new ArrayList<>();
        for (City city : cityManager.all()) {
            CitySnapshot snapshot = snapshotOf(city);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return Collections.unmodifiableList(snapshots);
    }

    @Override
    public Optional<CitySnapshot> getCity(String cityId) {
        if (cityId == null) {
            return Optional.empty();
        }
        City city = cityManager.get(cityId);
        return Optional.ofNullable(snapshotOf(city));
    }

    @Override
    public Optional<CitySnapshot> cityAt(Location location) {
        if (location == null) {
            return Optional.empty();
        }
        City city = cityManager.cityAt(location);
        return Optional.ofNullable(snapshotOf(city));
    }

    @Override
    public ListenerSubscription registerLifecycleListener(Plugin owner, CityLifecycleListener listener) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(listener, "listener");
        LifecycleHandle handle = new LifecycleHandle(owner, listener);
        lifecycleHandles.add(handle);
        return handle;
    }

    @Override
    public ListenerSubscription registerStatsListener(Plugin owner, CityStatsListener listener) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(listener, "listener");
        StatsHandle handle = new StatsHandle(owner, listener);
        statsHandles.add(handle);
        return handle;
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        handlePluginDisabled(event.getPlugin());
    }

    private void handlePluginDisabled(Plugin disabled) {
        for (LifecycleHandle handle : List.copyOf(lifecycleHandles)) {
            if (handle.owner().equals(disabled)) {
                handle.unregister();
            }
        }
        for (StatsHandle handle : List.copyOf(statsHandles)) {
            if (handle.owner().equals(disabled)) {
                handle.unregister();
            }
        }
    }

    @Override
    public void onCityCreated(City city) {
        CitySnapshot snapshot = snapshotOf(city);
        if (snapshot == null) {
            return;
        }
        broadcastLifecycle(listener -> listener.onCityCreated(snapshot));
    }

    @Override
    public void onCityRemoved(City city) {
        String cityId = city != null ? city.id : null;
        broadcastLifecycle(listener -> listener.onCityDeleted(cityId));
    }

    @Override
    public void onCityRenamed(String previousId, City city) {
        CitySnapshot snapshot = snapshotOf(city);
        if (snapshot == null) {
            return;
        }
        broadcastLifecycle(listener -> listener.onCityRenamed(previousId, snapshot));
    }

    @Override
    public void onCityUpdated(City city) {
        CitySnapshot snapshot = snapshotOf(city);
        if (snapshot == null) {
            return;
        }
        broadcastLifecycle(listener -> listener.onCityUpdated(snapshot));
    }

    @Override
    public void onCityStatsUpdated(City city) {
        CitySnapshot snapshot = snapshotOf(city);
        if (snapshot == null) {
            return;
        }
        CityStatsSnapshot stats = snapshot.stats();
        broadcastStats(listener -> listener.onCityStatsUpdated(snapshot, stats));
    }

    private void broadcastLifecycle(Consumer<CityLifecycleListener> consumer) {
        for (LifecycleHandle handle : lifecycleHandles) {
            if (!handle.isActive()) {
                continue;
            }
            try {
                consumer.accept(handle.listener());
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityLifecycleListener threw an exception", ex);
            }
        }
    }

    private void broadcastStats(Consumer<CityStatsListener> consumer) {
        for (StatsHandle handle : statsHandles) {
            if (!handle.isActive()) {
                continue;
            }
            try {
                consumer.accept(handle.listener());
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityStatsListener threw an exception", ex);
            }
        }
    }

    private CitySnapshot snapshotOf(City city) {
        if (city == null) {
            return null;
        }
        List<String> mayorList = new ArrayList<>();
        if (city.mayors != null) {
            for (String mayor : city.mayors) {
                if (mayor != null) {
                    mayorList.add(mayor);
                }
            }
        }
        List<String> mayors = List.copyOf(mayorList);
        List<CityAreaSnapshot> areas = new ArrayList<>();
        if (city.cuboids != null) {
            for (Cuboid cuboid : city.cuboids) {
                CityAreaSnapshot area = areaOf(cuboid);
                if (area != null) {
                    areas.add(area);
                }
            }
        }
        CityStatsSnapshot stats = statsOf(city);
        return new CitySnapshot(city.id, city.name, city.world, city.highrise, city.priority, mayors, List.copyOf(areas), stats);
    }

    private CityAreaSnapshot areaOf(Cuboid cuboid) {
        if (cuboid == null || cuboid.world == null) {
            return null;
        }
        long sizeX = (long) cuboid.maxX - (long) cuboid.minX + 1L;
        long sizeY = (long) cuboid.maxY - (long) cuboid.minY + 1L;
        long sizeZ = (long) cuboid.maxZ - (long) cuboid.minZ + 1L;
        long volume = Math.max(0L, sizeX) * Math.max(0L, sizeY) * Math.max(0L, sizeZ);
        return new CityAreaSnapshot(
                cuboid.world,
                cuboid.minX,
                cuboid.minY,
                cuboid.minZ,
                cuboid.maxX,
                cuboid.maxY,
                cuboid.maxZ,
                cuboid.fullHeight,
                volume
        );
    }

    private CityStatsSnapshot statsOf(City city) {
        CityProsperitySnapshot prosperity = prosperityOf(city.prosperityBreakdown);
        CityEconomySnapshot economy = economyOf(city.economyBreakdown);
        boolean ghostTown = city.isGhostTown();
        return new CityStatsSnapshot(
                city.population,
                city.adultPopulation,
                city.employed,
                city.unemployed,
                city.adultNone,
                city.adultNitwit,
                city.beds,
                city.prosperity,
                city.stations,
                city.level,
                city.levelProgress,
                city.employmentRate,
                city.housingRatio,
                city.transitCoverage,
                city.statsTimestamp,
                city.gdp,
                city.gdpPerCapita,
                city.sectorAgri,
                city.sectorInd,
                city.sectorServ,
                city.jobsPressure,
                city.housingPressure,
                city.transitPressure,
                city.landValue,
                city.migrationZeroPopArrivals,
                ghostTown,
                prosperity,
                economy
        );
    }

    private CityProsperitySnapshot prosperityOf(ProsperityBreakdown breakdown) {
        if (breakdown == null) {
            return null;
        }
        return new CityProsperitySnapshot(
                breakdown.base,
                breakdown.lightPoints,
                breakdown.employmentPoints,
                breakdown.overcrowdingPenalty,
                breakdown.naturePoints,
                breakdown.pollutionPenalty,
                breakdown.housingPoints,
                breakdown.transitPoints,
                breakdown.total,
                breakdown.isGhostTown()
        );
    }

    private CityEconomySnapshot economyOf(EconomyBreakdown breakdown) {
        if (breakdown == null) {
            return null;
        }
        return new CityEconomySnapshot(
                breakdown.base,
                breakdown.employmentUtilization,
                breakdown.housingBalance,
                breakdown.transitCoverage,
                breakdown.lighting,
                breakdown.nature,
                breakdown.pollutionPenalty,
                breakdown.overcrowdingPenalty,
                breakdown.maintenanceLighting,
                breakdown.maintenanceTransit,
                breakdown.total,
                breakdown.isGhostTown()
        );
    }

    private abstract static class AbstractHandle<T> implements ListenerSubscription {
        private final Plugin owner;
        private final T listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private AbstractHandle(Plugin owner, T listener) {
            this.owner = owner;
            this.listener = listener;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        protected boolean deactivate() {
            return active.compareAndSet(true, false);
        }

        protected Plugin owner() {
            return owner;
        }

        protected T listener() {
            return listener;
        }
    }

    private final class LifecycleHandle extends AbstractHandle<CityLifecycleListener> {
        private LifecycleHandle(Plugin owner, CityLifecycleListener listener) {
            super(owner, listener);
        }

        @Override
        public void unregister() {
            if (!deactivate()) {
                return;
            }
            lifecycleHandles.remove(this);
        }
    }

    private final class StatsHandle extends AbstractHandle<CityStatsListener> {
        private StatsHandle(Plugin owner, CityStatsListener listener) {
            super(owner, listener);
        }

        @Override
        public void unregister() {
            if (!deactivate()) {
                return;
            }
            statsHandles.remove(this);
        }
    }
}
