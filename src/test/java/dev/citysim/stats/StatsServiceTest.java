package dev.citysim.stats;

import dev.citysim.TestPluginFactory;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.File;

import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StatsServiceTest {

    @Test
    void updateCityHandlesNullCuboidEntries() throws Exception {
        Plugin plugin = TestPluginFactory.create("stats-service-nullcity");
        CityManager cityManager = new CityManager(plugin);

        File dataFile = new File(plugin.getDataFolder(), "cities.json");
        String json = """
                [
                  {
                    \"id\": \"nulltown\",
                    \"name\": \"Nulltown\",
                    \"world\": null,
                    \"cuboids\": [ null ]
                  }
                ]
                """;
        writeString(dataFile.toPath(), json);

        cityManager.load();
        City city = cityManager.get("nulltown");
        assertNotNull(city);

        city.cuboids.add(null);

        TestStatsService statsService = new TestStatsService(plugin, cityManager);

        assertDoesNotThrow(() -> statsService.updateCity(city));
    }

    @Test
    void refreshBlockScanCacheDelegatesToBlockScanService() {
        Plugin plugin = TestPluginFactory.create("stats-service-block-scan");
        CityManager cityManager = new CityManager(plugin);
        ProsperityCalculator calculator = new ProsperityCalculator();
        StubBlockScanService blockScanService = new StubBlockScanService(calculator);
        StubStatsUpdateScheduler scheduler = new StubStatsUpdateScheduler(plugin);

        StatsService statsService = new StatsService(plugin, cityManager, null, calculator, blockScanService, scheduler);
        blockScanService.reset();
        scheduler.reset();

        City city = new City();
        City.BlockScanCache cache = new City.BlockScanCache();
        blockScanService.valueToReturn = cache;

        City.BlockScanCache result = statsService.refreshBlockScanCache(city);

        assertSame(cache, result);
        assertSame(city, blockScanService.lastRefreshCity);
    }

    @Test
    void updateConfigDelegatesToHelpers() {
        Plugin plugin = TestPluginFactory.create("stats-service-update-config");
        CityManager cityManager = new CityManager(plugin);
        ProsperityCalculator calculator = new ProsperityCalculator();
        StubBlockScanService blockScanService = new StubBlockScanService(calculator);
        StubStatsUpdateScheduler scheduler = new StubStatsUpdateScheduler(plugin);

        StatsService statsService = new StatsService(plugin, cityManager, null, calculator, blockScanService, scheduler);
        blockScanService.reset();
        scheduler.reset();

        statsService.updateConfig();

        assertEquals(1, blockScanService.updateCalls);
        assertEquals(1, scheduler.updateCalls);
    }

    @Test
    void stopDelegatesToScheduler() {
        Plugin plugin = TestPluginFactory.create("stats-service-stop");
        CityManager cityManager = new CityManager(plugin);
        ProsperityCalculator calculator = new ProsperityCalculator();
        StubBlockScanService blockScanService = new StubBlockScanService(calculator);
        StubStatsUpdateScheduler scheduler = new StubStatsUpdateScheduler(plugin);

        StatsService statsService = new StatsService(plugin, cityManager, null, calculator, blockScanService, scheduler);
        blockScanService.reset();
        scheduler.reset();
        scheduler.running = true;

        statsService.stop();

        assertEquals(1, scheduler.stopCalls);
    }

    private static final class TestStatsService extends StatsService {
        TestStatsService(Plugin plugin, CityManager cityManager) {
            super(plugin, cityManager, null);
        }

        @Override
        public void updateConfig() {
            // Avoid touching Bukkit configuration during tests.
        }
    }

    private static final class StubBlockScanService extends BlockScanService {
        City lastRefreshCity;
        City lastEnsureCity;
        boolean lastEnsureForce;
        int updateCalls;
        City.BlockScanCache valueToReturn = new City.BlockScanCache();

        StubBlockScanService(ProsperityCalculator calculator) {
            super(calculator);
        }

        @Override
        public City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh) {
            lastEnsureCity = city;
            lastEnsureForce = forceRefresh;
            return valueToReturn;
        }

        @Override
        public City.BlockScanCache refreshBlockScanCache(City city) {
            lastRefreshCity = city;
            return valueToReturn;
        }

        @Override
        public void updateConfig(FileConfiguration configuration) {
            updateCalls++;
        }

        void reset() {
            lastRefreshCity = null;
            lastEnsureCity = null;
            lastEnsureForce = false;
            updateCalls = 0;
        }
    }

    private static class StubStatsUpdateScheduler extends StatsUpdateScheduler {
        int updateCalls;
        int startCalls;
        int stopCalls;
        boolean running;

        StubStatsUpdateScheduler(Plugin plugin) {
            super(plugin, () -> {});
        }

        @Override
        public void updateConfig(FileConfiguration config) {
            updateCalls++;
        }

        @Override
        public void start() {
            startCalls++;
            running = true;
        }

        @Override
        public void stop() {
            stopCalls++;
            running = false;
        }

        @Override
        public void restart() {
            stop();
            start();
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        void reset() {
            updateCalls = 0;
            startCalls = 0;
            stopCalls = 0;
            running = false;
        }
    }
}
