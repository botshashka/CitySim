package dev.citysim.api;

/**
 * Listener notified when new stats are available for a city.
 */
public interface CityStatsListener {

    default void onCityStatsUpdated(CitySnapshot snapshot, CityStatsSnapshot stats) {}
}
