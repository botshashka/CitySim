package dev.citysim.api;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Optional;

/**
 * Public entry point for third-party plugins to query CitySim data.
 */
public interface CitySimApi {

    int CURRENT_VERSION = 1;

    /**
     * Returns the API version supported by this server. Plugins should verify compatibility.
     */
    default int getApiVersion() {
        return CURRENT_VERSION;
    }

    Collection<CitySnapshot> getCities();

    Optional<CitySnapshot> getCity(String cityId);

    Optional<CitySnapshot> cityAt(Location location);

    ListenerSubscription registerLifecycleListener(Plugin plugin, CityLifecycleListener listener);

    ListenerSubscription registerStatsListener(Plugin plugin, CityStatsListener listener);
}
