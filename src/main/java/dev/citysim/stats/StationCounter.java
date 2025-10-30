package dev.citysim.stats;

import dev.citysim.city.City;

import java.util.Optional;

public interface StationCounter {
    Optional<StationCountResult> countStations(City city);
}
