package dev.citysim.city;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityManagerTest {

    private static final Logger LOGGER = Logger.getLogger("CityManagerTest");

    @AfterEach
    void tearDown() throws Exception {
        setBukkitServer(null);
    }

    @Test
    void highriseRejectsSpanHeightCuboids() throws Exception {
        Plugin plugin = createPluginStub();
        CityManager manager = new CityManager(plugin);

        World world = createWorldStub("testworld", -64, 320);
        setBukkitServer(createServerStub(world));

        City city = manager.create("Highrise City");
        manager.setHighrise(city.id, true);

        Cuboid cuboid = new Cuboid();
        cuboid.world = "testworld";
        cuboid.minX = 0;
        cuboid.maxX = 10;
        cuboid.minZ = 0;
        cuboid.maxZ = 10;
        cuboid.minY = -64;
        cuboid.maxY = 319;
        cuboid.fullHeight = false;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> manager.addCuboid(city.id, cuboid));
        assertEquals("Highrise cities cannot contain cuboids with full Y mode.", ex.getMessage());
    }

    @Test
    void loadHandlesInvalidJsonGracefully(@TempDir Path tempDir) throws IOException {
        File dataFolder = tempDir.toFile();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Failed to create data folder for test");
        }
        File dataFile = new File(dataFolder, "cities.json");
        Files.writeString(dataFile.toPath(), "{ not valid json");

        TestLogHandler handler = new TestLogHandler();
        Logger logger = Logger.getLogger("CityManagerTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);

        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class[]{Plugin.class},
                new InvalidDataPluginHandler(dataFolder, logger)
        );

        CityManager manager = new CityManager(plugin);

        manager.load();

        logger.removeHandler(handler);

        assertTrue(manager.all().isEmpty(), "City list should be empty when data file is invalid");

        boolean sawWarning = handler.getRecords().stream()
                .anyMatch(record -> record.getLevel().equals(Level.WARNING)
                        && record.getMessage().contains("Failed parsing cities data"));
        assertTrue(sawWarning, "Expected warning about invalid cities data");
    }

    @Test
    void cityAtReturnsNullWhenLocationWorldMissing() throws Exception {
        Plugin plugin = createPluginStub();
        CityManager manager = new CityManager(plugin);

        Location location = new Location(null, 5, 64, 5);

        assertNull(manager.cityAt(location));
    }

    private static Plugin createPluginStub() throws Exception {
        Path tempDir = Files.createTempDirectory("citysim-plugin");
        File dataFolder = tempDir.toFile();
        dataFolder.mkdirs();

        PluginDescriptionFile description = new PluginDescriptionFile("CitySimTest", "1.0", "dev.citysim.TestPlugin");

        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class[]{Plugin.class},
                new InvocationHandler() {
                    private boolean naggable = false;
                    private final Logger logger = LOGGER;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        switch (name) {
                            case "getDataFolder":
                                return dataFolder;
                            case "getDescription":
                                return description;
                            case "getPluginMeta":
                                return null;
                            case "getConfig":
                                return null;
                            case "getResource":
                                return null;
                            case "saveConfig":
                            case "saveDefaultConfig":
                            case "reloadConfig":
                                return null;
                            case "saveResource":
                                return null;
                            case "getPluginLoader":
                                return null;
                            case "getServer":
                                return Bukkit.getServer();
                            case "isEnabled":
                                return true;
                            case "onDisable":
                            case "onLoad":
                            case "onEnable":
                                return null;
                            case "isNaggable":
                                return naggable;
                            case "setNaggable":
                                naggable = (boolean) args[0];
                                return null;
                            case "getDefaultWorldGenerator":
                            case "getDefaultBiomeProvider":
                                return null;
                            case "getLogger":
                                logger.setLevel(Level.ALL);
                                return logger;
                            case "getName":
                                return description.getName();
                            case "getLifecycleManager":
                                return null;
                            case "onCommand":
                                return false;
                            case "onTabComplete":
                                return Collections.emptyList();
                            case "getDataPath":
                                return dataFolder.toPath();
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    }
                }
        );
    }

    private static World createWorldStub(String name, int minHeight, int maxHeight) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    switch (methodName) {
                        case "getName":
                            return name;
                        case "getMinHeight":
                            return minHeight;
                        case "getMaxHeight":
                            return maxHeight;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "WorldStub{" + name + "}";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    private static org.bukkit.Server createServerStub(World world) {
        return (org.bukkit.Server) Proxy.newProxyInstance(
                org.bukkit.Server.class.getClassLoader(),
                new Class[]{org.bukkit.Server.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    switch (methodName) {
                        case "getWorld":
                            if (args != null && args.length == 1 && args[0] instanceof String name) {
                                return name.equalsIgnoreCase(world.getName()) ? world : null;
                            }
                            return null;
                        case "getWorlds":
                            return Collections.singletonList(world);
                        case "getLogger":
                            return LOGGER;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "ServerStub{" + world.getName() + "}";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
        );
    }

    private static void setBukkitServer(org.bukkit.Server server) throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
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
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class InvalidDataPluginHandler implements InvocationHandler {
        private final File dataFolder;
        private final Logger logger;

        private InvalidDataPluginHandler(File dataFolder, Logger logger) {
            this.dataFolder = dataFolder;
            this.logger = logger;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "getDataFolder":
                    return dataFolder;
                case "getLogger":
                    return logger;
                case "isEnabled":
                    return true;
                case "getName":
                    return "CityManagerTestPlugin";
                default:
                    if (method.getDeclaringClass().equals(Object.class)) {
                        return method.invoke(this, args);
                    }
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private static final class TestLogHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing to flush
        }

        @Override
        public void close() {
            // nothing to close
        }

        List<LogRecord> getRecords() {
            return records;
        }
    }
}

