package dev.citysim.stats;

import dev.citysim.city.City;

import java.util.OptionalInt;

public interface StationCounter {
    OptionalInt countStations(City city);
}
