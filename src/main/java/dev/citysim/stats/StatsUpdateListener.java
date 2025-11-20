package dev.citysim.stats;

import dev.citysim.city.City;

/**
 * Listener notified whenever fresh stats for a city are applied.
 */
public interface StatsUpdateListener {
    void onCityStatsUpdated(City city);
}
