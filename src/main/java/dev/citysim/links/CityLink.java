package dev.citysim.links;

import dev.citysim.city.City;

/**
 * Represents a lightweight link between two cities computed on demand.
 */
public record CityLink(City neighbor, double distance, int strength) {
}
