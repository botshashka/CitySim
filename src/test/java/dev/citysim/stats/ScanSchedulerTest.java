package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.schedule.ScanScheduler;
import dev.citysim.stats.scan.CityScanCallbacks;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.ScanDebugManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSchedulerTest {

    @Test
    void processesPendingCitiesInQueueOrder() {
        DummyPlugin plugin = new DummyPlugin();
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
        assertEquals(42, alpha.happiness);
        assertEquals(42, beta.happiness);
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
        public HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache cache) {
            HappinessBreakdown breakdown = new HappinessBreakdown();
            breakdown.total = 42;
            return breakdown;
        }
    }

    private static final class DummyPlugin implements Plugin {
        private final File dataFolder = new File("build/tmp/dummy-plugin-scheduler");
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
        public void setNaggable(boolean b) {
            this.naggable = b;
        }

        @Override
        public ChunkGenerator getDefaultWorldGenerator(String s, String s1) {
            return null;
        }

        @Override
        public BiomeProvider getDefaultBiomeProvider(String s, String s1) {
            return null;
        }

        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
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
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return false;
        }
    }
}
