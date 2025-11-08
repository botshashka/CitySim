package dev.citysim.stats;

import dev.citysim.TestPluginFactory;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.schedule.ScanScheduler;
import dev.citysim.stats.scan.CityScanCallbacks;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.ScanDebugManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSchedulerTest {

    @Test
    void processesPendingCitiesInQueueOrder() {
        Plugin plugin = TestPluginFactory.create("scan-scheduler");
        CityManager cityManager = new CityManager(plugin);
        City alpha = cityManager.create("Alpha");
        City beta = cityManager.create("Beta");

        CityScanRunner runner = new CityScanRunner(new StubCallbacks(), new ScanDebugManager());
        ScanScheduler scheduler = new ScanScheduler(cityManager, runner);
        scheduler.setLimits(1, 16, 128);

        scheduler.queueCity(alpha.id, true, false, "alpha update", null);
        scheduler.queueCity(beta.id, true, false, "beta update", null);

        for (int i = 0; i < 5; i++) {
            scheduler.tick();
        }
        assertEquals(42, alpha.prosperity);
        assertEquals(42, beta.prosperity);
    }

    private static final class StubCallbacks implements CityScanCallbacks {
        @Override
        public StationCountResult refreshStationCount(City city) {
            return null;
        }

        @Override
        public City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
            City.BlockScanCache cache = city.blockScanCache;
            if (cache == null) {
                cache = new City.BlockScanCache();
                city.blockScanCache = cache;
            }
            return cache;
        }

        @Override
        public ProsperityBreakdown calculateProsperityBreakdown(City city, City.BlockScanCache cache) {
            ProsperityBreakdown breakdown = new ProsperityBreakdown();
            breakdown.total = 42;
            return breakdown;
        }

    }
}
