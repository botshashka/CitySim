package dev.citysim.integration.traincarts;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Utility for locating the TrainCarts plugin on the server. The lookup logic is shared between
 * the integration service and the main plugin bootstrap so that name normalization stays
 * consistent.
 */
public final class TrainCartsLocator {

    private TrainCartsLocator() {
    }

    public static Optional<Plugin> locate(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        PluginManager manager = owner.getServer() != null ? owner.getServer().getPluginManager() : null;
        if (manager == null) {
            return Optional.empty();
        }

        Plugin direct = manager.getPlugin("TrainCarts");
        if (isTrainCartsPlugin(direct)) {
            return Optional.of(direct);
        }

        Plugin underscored = manager.getPlugin("Train_Carts");
        if (isTrainCartsPlugin(underscored)) {
            return Optional.of(underscored);
        }

        for (Plugin candidate : manager.getPlugins()) {
            if (isTrainCartsPlugin(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static boolean isTrainCartsPlugin(Plugin plugin) {
        return plugin != null && isTrainCartsName(plugin.getName());
    }

    public static boolean isTrainCartsName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return "traincarts".equals(normalized);
    }
}
