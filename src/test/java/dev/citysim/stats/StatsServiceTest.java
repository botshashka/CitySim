package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StatsServiceTest {

    @Test
    void updateCityHandlesNullCuboidEntries() throws Exception {
        DummyPlugin plugin = new DummyPlugin();
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
        ConfigurableDummyPlugin plugin = new ConfigurableDummyPlugin();
        CityManager cityManager = new CityManager(plugin);
        HappinessCalculator calculator = new HappinessCalculator();
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
        ConfigurableDummyPlugin plugin = new ConfigurableDummyPlugin();
        CityManager cityManager = new CityManager(plugin);
        HappinessCalculator calculator = new HappinessCalculator();
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
        ConfigurableDummyPlugin plugin = new ConfigurableDummyPlugin();
        CityManager cityManager = new CityManager(plugin);
        HappinessCalculator calculator = new HappinessCalculator();
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

    private static class DummyPlugin implements Plugin {
        private final File dataFolder = new File("build/tmp/dummy-plugin");
        private final Logger logger = Logger.getLogger("DummyPlugin");
        private boolean naggable;

        DummyPlugin() {
            dataFolder.mkdirs();
        }

        @Override
        public File getDataFolder() {
            return dataFolder;
        }

        @Override
        public Path getDataPath() {
            return dataFolder.toPath();
        }

        @Override
        public PluginDescriptionFile getDescription() {
            return null;
        }

        @Override
        public PluginMeta getPluginMeta() {
            return null;
        }

        @Override
        public FileConfiguration getConfig() {
            return null;
        }

        @Override
        public InputStream getResource(String s) {
            return null;
        }

        @Override
        public void saveConfig() {
        }

        @Override
        public void saveDefaultConfig() {
        }

        @Override
        public void saveResource(String s, boolean b) {
        }

        @Override
        public void reloadConfig() {
        }

        @Override
        public PluginLoader getPluginLoader() {
            return null;
        }

        @Override
        public Server getServer() {
            return null;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void onDisable() {
        }

        @Override
        public void onLoad() {
        }

        @Override
        public void onEnable() {
        }

        @Override
        public boolean isNaggable() {
            return naggable;
        }

        @Override
        public void setNaggable(boolean naggable) {
            this.naggable = naggable;
        }

        @Override
        public ChunkGenerator getDefaultWorldGenerator(String s, String s1) {
            return null;
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(String s, String s1) {
            return null;
        }

        @Override
        public Logger getLogger() {
            return logger;
        }

        @Override
        public String getName() {
            return "DummyPlugin";
        }

        @Override
        public LifecycleEventManager<Plugin> getLifecycleManager() {
            return null;
        }

        @Override
        public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
            return false;
        }

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private static final class ConfigurableDummyPlugin extends DummyPlugin {
        private final YamlConfiguration configuration = new YamlConfiguration();

        @Override
        public FileConfiguration getConfig() {
            return configuration;
        }
    }

    private static final class StubBlockScanService extends BlockScanService {
        City lastRefreshCity;
        City lastEnsureCity;
        boolean lastEnsureForce;
        int updateCalls;
        City.BlockScanCache valueToReturn = new City.BlockScanCache();

        StubBlockScanService(HappinessCalculator calculator) {
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
