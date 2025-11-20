package dev.citysim.api;

import java.util.List;

/**
 * Immutable view of a city's structure and latest stats.
 */
public record CitySnapshot(
        String id,
        String name,
        String world,
        boolean highrise,
        int priority,
        List<String> mayors,
        List<CityAreaSnapshot> areas,
        CityStatsSnapshot stats
) {}
