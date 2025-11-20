package dev.citysim.city;

/**
 * Listener for structural city changes managed by {@link CityManager}.
 */
public interface CityManagerListener {

    default void onCityCreated(City city) {}

    default void onCityRemoved(City city) {}

    default void onCityRenamed(String previousId, City city) {}

    default void onCityUpdated(City city) {}
}
