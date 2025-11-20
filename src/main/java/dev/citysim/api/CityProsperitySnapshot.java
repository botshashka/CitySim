package dev.citysim.api;

/**
 * Captures the prosperity breakdown for a city.
 */
public record CityProsperitySnapshot(
        int baseScore,
        double lightPoints,
        double employmentPoints,
        double overcrowdingPenalty,
        double naturePoints,
        double pollutionPenalty,
        double housingPoints,
        double transitPoints,
        int total,
        boolean ghostTown
) {}
