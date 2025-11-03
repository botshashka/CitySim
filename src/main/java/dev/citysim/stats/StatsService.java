
package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.schedule.ScanScheduler;
import dev.citysim.stats.scan.CityScanCallbacks;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.ScanContext;
import dev.citysim.stats.scan.ScanDebugManager;
import dev.citysim.stats.scan.ScanRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

public class StatsService {

    private final Plugin plugin;
    private final CityManager cityManager;
    private final HappinessCalculator happinessCalculator;
    private final BlockScanService blockScanService;
    private final ScanDebugManager scanDebugManager;
    private final CityScanCallbacks scanCallbacks;
    private final CityScanRunner scanRunner;
    private final ScanScheduler scanScheduler;
    private final StatsUpdateScheduler statsUpdateScheduler;
    private volatile StationCounter stationCounter;
    private StationCountingMode stationCountingMode = StationCountingMode.MANUAL;
    private boolean stationCountingWarningLogged = false;

    public StatsService(Plugin plugin, CityManager cityManager, StationCounter stationCounter) {
        this(plugin, cityManager, stationCounter, null, null, null);
    }

    StatsService(Plugin plugin, CityManager cityManager, StationCounter stationCounter,
                 HappinessCalculator happinessCalculator,
                 BlockScanService blockScanService,
                 StatsUpdateScheduler statsUpdateScheduler) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.stationCounter = stationCounter;
        this.happinessCalculator = happinessCalculator != null ? happinessCalculator : new HappinessCalculator();
        this.blockScanService = blockScanService != null ? blockScanService : new BlockScanService(this.happinessCalculator);
        this.scanDebugManager = new ScanDebugManager();
        this.scanCallbacks = new StatsScanCallbacks();
        this.scanRunner = new CityScanRunner(scanCallbacks, scanDebugManager);
        this.scanScheduler = new ScanScheduler(cityManager, scanRunner);
        this.statsUpdateScheduler = statsUpdateScheduler != null ? statsUpdateScheduler : new StatsUpdateScheduler(plugin, this::tick);
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
        if (statsUpdateScheduler.isRunning()) {
            return;
        }
        scanScheduler.clear();
        scheduleInitialStartupScans();
        statsUpdateScheduler.start();
    }

    public void stop() {
        statsUpdateScheduler.stop();
        scanScheduler.clear();
    }

    public void restartTask() {
        updateConfig();
        statsUpdateScheduler.stop();
        scanScheduler.clear();
        scheduleInitialStartupScans();
        statsUpdateScheduler.start();
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
        scanScheduler.queueCity(cityId, forceRefresh, forceChunkLoad, reason, context);
    }

    private ScanContext createContext(Location location) {
        if (location == null) {
            return null;
        }
        String worldName = location.getWorld() != null ? location.getWorld().getName() : null;
        return new ScanContext(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void tick() {
        scanScheduler.tick();
    }

    private void scheduleInitialStartupScans() {
        Bukkit.getScheduler().runTask(plugin, this::runInitialStartupScans);
    }

    private void runInitialStartupScans() {
        scanRunner.clearActiveJobs();
        for (City city : cityManager.all()) {
            if (city == null || city.id == null || city.id.isEmpty()) {
                continue;
            }
            scanRunner.runSynchronously(city, new ScanRequest(true, true, "initial startup", null));
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
        HappinessBreakdown result = scanRunner.runSynchronously(city, new ScanRequest(forceRefresh, true, "synchronous update", null));
        if (result != null) {
            result.setGhostTown(city.isGhostTown() || result.isGhostTown());
            return result;
        }
        HappinessBreakdown fallback = new HappinessBreakdown();
        fallback.setGhostTown(city.isGhostTown() || city.population <= 0);
        return fallback;
    }

    private void cancelActiveJob(City city) {
        if (city == null || city.id == null) {
            return;
        }
        scanScheduler.cancel(city.id);
    }

    public HappinessBreakdown computeHappinessBreakdown(City city) {
        if (city == null) {
            return new HappinessBreakdown();
        }
        if (city.happinessBreakdown != null && city.blockScanCache != null) {
            city.happinessBreakdown.setGhostTown(city.isGhostTown() || city.population <= 0);
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
        if (city.happinessBreakdown != null) {
            city.happinessBreakdown.setGhostTown(city.isGhostTown() || city.population <= 0);
            return city.happinessBreakdown;
        }
        HappinessBreakdown fallback = new HappinessBreakdown();
        fallback.setGhostTown(city.isGhostTown() || city.population <= 0);
        return fallback;
    }

    private StationCountResult refreshStationCount(City city) {
        if (city == null) {
            return null;
        }
        switch (stationCountingMode) {
            case DISABLED -> {
                return null;
            }
            case TRAIN_CARTS -> {
                StationCounter counter = stationCounter;
                if (counter == null) {
                    if (!stationCountingWarningLogged) {
                        plugin.getLogger().warning("TrainCarts station counting requested but integration is unavailable; using manual station totals.");
                        stationCountingWarningLogged = true;
                    }
                    return null;
                }
                try {
                    Optional<StationCountResult> counted = counter.countStations(city);
                    if (counted.isPresent()) {
                        StationCountResult result = counted.get();
                        city.stations = Math.max(0, result.stations());
                        stationCountingWarningLogged = false;
                        return new StationCountResult(city.stations, result.signs());
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
                return null;
            }
            case MANUAL -> {
                stationCountingWarningLogged = false;
                return null;
            }
        }
        return null;
    }

    private HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache metrics) {
        return happinessCalculator.calculate(city, metrics);
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
        return blockScanService.refreshBlockScanCache(city);
    }

    public void updateConfig() {
        var config = plugin.getConfig();

        statsUpdateScheduler.updateConfig(config);
        blockScanService.updateConfig(config);

        StationCountingMode configuredMode = StationCountingMode.MANUAL;
        if (config != null) {
            configuredMode = StationCountingMode.fromConfig(config.getString("stations.counting_mode", "manual"));
        }
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

        int maxCitiesPerTick = 1;
        int maxEntityChunksPerTick = 2;
        int maxBedBlocksPerTick = 2048;
        double lightNeutral = 2.0;
        double lightMaxPts = 10.0;
        double employmentMaxPts = 15.0;
        double overcrowdingMaxPenalty = 10.0;
        double natureMaxPts = 10.0;
        double pollutionMaxPenalty = 15.0;
        double housingMaxPts = 10.0;
        double transitMaxPts = 5.0;

        if (config != null) {
            maxCitiesPerTick = Math.max(1, config.getInt("updates.max_cities_per_tick", maxCitiesPerTick));
            maxEntityChunksPerTick = Math.max(1, config.getInt("updates.max_entity_chunks_per_tick", maxEntityChunksPerTick));
            maxBedBlocksPerTick = Math.max(1, config.getInt("updates.max_bed_blocks_per_tick", maxBedBlocksPerTick));

            lightNeutral = config.getDouble("happiness_weights.light_neutral_level", lightNeutral);
            lightMaxPts = config.getDouble("happiness_weights.light_max_points", lightMaxPts);
            employmentMaxPts = config.getDouble("happiness_weights.employment_max_points", employmentMaxPts);
            overcrowdingMaxPenalty = config.getDouble("happiness_weights.overcrowding_max_penalty", overcrowdingMaxPenalty);
            natureMaxPts = config.getDouble("happiness_weights.nature_max_points", natureMaxPts);
            pollutionMaxPenalty = config.getDouble("happiness_weights.pollution_max_penalty", pollutionMaxPenalty);
            housingMaxPts = config.getDouble("happiness_weights.housing_max_points", housingMaxPts);
            transitMaxPts = config.getDouble("happiness_weights.transit_max_points", transitMaxPts);
        }

        scanScheduler.setLimits(maxCitiesPerTick, maxEntityChunksPerTick, maxBedBlocksPerTick);

        happinessCalculator.setLightNeutral(lightNeutral);
        happinessCalculator.setLightMaxPts(lightMaxPts);
        happinessCalculator.setEmploymentMaxPts(employmentMaxPts);
        happinessCalculator.setOvercrowdMaxPenalty(overcrowdingMaxPenalty);
        happinessCalculator.setNatureMaxPts(natureMaxPts);
        happinessCalculator.setPollutionMaxPenalty(pollutionMaxPenalty);
        happinessCalculator.setHousingMaxPts(housingMaxPts);
        happinessCalculator.setTransitMaxPts(transitMaxPts);
        happinessCalculator.setStationCountingMode(stationCountingMode);
    }

    private class StatsScanCallbacks implements CityScanCallbacks {
        @Override
        public StationCountResult refreshStationCount(City city) {
            return StatsService.this.refreshStationCount(city);
        }

        @Override
        public City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
            return blockScanService.ensureBlockScanCache(city, forceRefresh);
        }

        @Override
        public HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache cache) {
            return StatsService.this.calculateHappinessBreakdown(city, cache);
        }
    }
}
