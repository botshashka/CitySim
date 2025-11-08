package dev.citysim;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import static java.util.Collections.emptyList;

/**
 * Utility factory for lightweight {@link Plugin} doubles used in tests. The implementation
 * relies on dynamic proxies so we never reference deprecated Bukkit types directly.
 */
public final class TestPluginFactory {

    private TestPluginFactory() {
    }

    public static Plugin create(String name) {
        return create(name, null);
    }

    public static Plugin create(String name, FileConfiguration configuration) {
        PluginInvocationHandler handler = new PluginInvocationHandler(name, configuration);
        return (Plugin) Proxy.newProxyInstance(
                TestPluginFactory.class.getClassLoader(),
                new Class[]{Plugin.class},
                handler
        );
    }

    private static final class PluginInvocationHandler implements InvocationHandler {
        private final File dataFolder;
        private final FileConfiguration configuration;
        private final Logger logger;
        private final String name;
        private boolean naggable;

        PluginInvocationHandler(String name, FileConfiguration configuration) {
            this.name = Objects.requireNonNullElse(name, "test-plugin");
            String safe = this.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
            this.dataFolder = new File("build/tmp/test-plugins/" + safe);
            //noinspection ResultOfMethodCallIgnored
            this.dataFolder.mkdirs();
            this.logger = Logger.getLogger("PluginStub-" + this.name);
            this.configuration = configuration != null ? configuration : new YamlConfiguration();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            return switch (name) {
                case "getDataFolder" -> dataFolder;
                case "getDataPath" -> dataFolder.toPath();
                case "getConfig" -> configuration;
                case "reloadConfig", "saveConfig", "saveDefaultConfig", "saveResource" -> null;
                case "getPluginLoader" -> null; // legacy API, intentionally null
                case "getServer" -> null;
                case "isEnabled" -> true;
                case "onDisable", "onLoad", "onEnable" -> null;
                case "isNaggable" -> naggable;
                case "setNaggable" -> {
                    naggable = args != null && args.length > 0 && args[0] instanceof Boolean b && b;
                    yield null;
                }
                case "getDefaultWorldGenerator" -> null;
                case "getDefaultBiomeProvider" -> null;
                case "getLogger" -> logger;
                case "getName" -> this.name;
                case "getLifecycleManager" -> null;
                case "getComponentLogger" -> ComponentLogger.logger("PluginStub-" + this.name);
                case "getSLF4JLogger" -> LoggerFactory.getLogger("PluginStub-" + this.name);
                case "getDescription" -> null;
                case "getPluginMeta" -> null;
                case "getResource" -> null;
                case "onCommand" -> false;
                case "onTabComplete" -> emptyList();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "PluginStub{" + this.name + "}";
                default -> defaultValue(method.getReturnType());
            };
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == void.class) {
                return null;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == double.class) {
                return 0d;
            }
            if (type == char.class) {
                return '\0';
            }
            return null;
        }
    }
}
