package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static java.nio.file.Files.writeString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StatsServiceTest {

    @Test
    void processNextScheduledCityReturnsFalseWhenAllCitiesActive() throws Exception {
        DummyPlugin plugin = new DummyPlugin();
        CityManager cityManager = new CityManager(plugin);
        City cityOne = cityManager.create("Alpha");
        City cityTwo = cityManager.create("Beta");

        TestStatsService statsService = new TestStatsService(plugin, cityManager);

        Field activeJobsField = StatsService.class.getDeclaredField("activeCityJobs");
        activeJobsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> activeJobs = (Map<String, Object>) activeJobsField.get(statsService);
        activeJobs.put(cityOne.id, null);
        activeJobs.put(cityTwo.id, null);

        Method method = StatsService.class.getDeclaredMethod("processNextScheduledCity");
        method.setAccessible(true);

        boolean result = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> (boolean) method.invoke(statsService));
        assertFalse(result);
    }

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

    private static final class TestStatsService extends StatsService {
        TestStatsService(Plugin plugin, CityManager cityManager) {
            super(plugin, cityManager, null);
        }

        @Override
        public void updateConfig() {
            // Avoid touching Bukkit configuration during tests.
        }
    }

    private static final class DummyPlugin implements Plugin {
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
}
