package dev.citysim.api;

/**
 * Listener for city structure changes.
 */
public interface CityLifecycleListener {

    default void onCityCreated(CitySnapshot snapshot) {}

    default void onCityUpdated(CitySnapshot snapshot) {}

    default void onCityRenamed(String previousId, CitySnapshot snapshot) {}

    default void onCityDeleted(String cityId) {}
}
