package dev.citysim.api;

/**
 * Represents a saved cuboid area for a city.
 */
public record CityAreaSnapshot(
        String world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        boolean fullHeight,
        long volumeBlocks
) {}
