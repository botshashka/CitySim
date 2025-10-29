package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class StatsServiceTest {

    @Test
    void processNextScheduledCityReturnsFalseWhenAllCitiesActive() throws Exception {
        File dataFolder = Files.createTempDirectory("dummy-plugin").toFile();
        dataFolder.deleteOnExit();
        Plugin plugin = createPluginStub(dataFolder);

        City alpha = city("alpha");
        City beta = city("beta");
        StubCityManager cityManager = new StubCityManager(plugin, List.of(alpha, beta));
        TestStatsService statsService = new TestStatsService(plugin, cityManager);

        Map<String, Object> activeJobs = activeJobs(statsService);
        activeJobs.put(alpha.id, null);
        activeJobs.put(beta.id, null);

        Method method = StatsService.class.getDeclaredMethod("processNextScheduledCity");
        method.setAccessible(true);

        boolean result = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> (boolean) method.invoke(statsService));
        assertFalse(result);
    }

    private static City city(String id) {
        City city = new City();
        city.id = id;
        city.name = id;
        return city;
    }

    private static Map<String, Object> activeJobs(StatsService statsService) throws Exception {
        Field field = StatsService.class.getDeclaredField("activeCityJobs");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> activeJobs = (Map<String, Object>) field.get(statsService);
        return activeJobs;
    }

    private static Plugin createPluginStub(File dataFolder) {
        Logger logger = Logger.getLogger("DummyPlugin");
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "getDataFolder" -> dataFolder;
                case "getDataPath" -> dataFolder.toPath();
                case "getLogger" -> logger;
                case "getName" -> "DummyPlugin";
                case "isEnabled" -> true;
                default -> defaultValue(method.getReturnType());
            };
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static final class StubCityManager extends CityManager {
        private final Map<String, City> cities = new LinkedHashMap<>();

        StubCityManager(Plugin plugin, List<City> initialCities) {
            super(plugin);
            for (City city : initialCities) {
                if (city != null && city.id != null) {
                    cities.put(city.id, city);
                }
            }
        }

        @Override
        public java.util.Collection<City> all() {
            return cities.values();
        }

        @Override
        public City get(String id) {
            if (id == null) {
                return null;
            }
            return cities.get(id);
        }
    }

    private static final class TestStatsService extends StatsService {
        TestStatsService(Plugin plugin, CityManager cityManager) {
            super(plugin, cityManager);
        }

        @Override
        public void updateConfig() {
            // Avoid touching Bukkit configuration during tests.
        }
    }
}
