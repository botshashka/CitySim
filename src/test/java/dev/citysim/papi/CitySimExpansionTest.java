package dev.citysim.papi;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.apache.logging.log4j.Logger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitySimExpansionTest {
    private StubCityManager cityManager;
    private CitySimExpansion expansion;

    @BeforeEach
    void setUp() {
        cityManager = new StubCityManager();
        expansion = new CitySimExpansion(cityManager, null);
    }

    @Test
    void cityNamePlaceholderIsEmptyWhenCityMissing() {
        assertEquals("", expansion.onRequest(null, "cityname_unknown"));
    }

    @Test
    void numericPlaceholderFallsBackToZeroWhenCityMissing() {
        assertEquals("0", expansion.onRequest(null, "pop_unknown"));
    }

    @Test
    void explicitCityIdLookupStillReturnsData() {
        City city = new City();
        city.id = "alpha";
        city.name = "Alpha City";
        city.population = 42;
        cityManager.setCityById(city.id, city);

        assertEquals("Alpha City", expansion.onRequest(null, "cityname_alpha"));
        assertEquals("42", expansion.onRequest(null, "pop_alpha"));
    }

    private static class StubCityManager extends CityManager {
        private final Map<String, City> byId = new HashMap<>();

        StubCityManager() {
            super(new DummyPlugin());
        }

        void setCityById(String id, City city) {
            byId.put(id.toLowerCase(Locale.ROOT), city);
        }

        @Override
        public City get(String id) {
            if (id == null) {
                return null;
            }
            return byId.get(id.toLowerCase(Locale.ROOT));
        }
    }

    private static class DummyPlugin implements Plugin {
        private final File dataFolder = new File("build/tmp/dummy-plugin");
        private boolean naggable = true;

        DummyPlugin() {
            if (!dataFolder.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dataFolder.mkdirs();
            }
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
            return false;
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
        public java.util.logging.Logger getLogger() {
            return java.util.logging.Logger.getLogger("DummyPlugin");
        }

        @Override
        public ComponentLogger getComponentLogger() {
            return null;
        }

        @Override
        public org.slf4j.Logger getSLF4JLogger() {
            return LoggerFactory.getLogger("DummyPlugin");
        }

        @Override
        public Logger getLog4JLogger() {
            return null;
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
        public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
            return false;
        }

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
            return null;
        }
    }
}
