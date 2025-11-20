package dev.citysim.api;

/**
 * Captures secondary economy and maintenance metrics.
 */
public record CityEconomySnapshot(
        int baseScore,
        double employmentUtilization,
        double housingBalance,
        double transitCoverage,
        double lighting,
        double nature,
        double pollutionPenalty,
        double overcrowdingPenalty,
        double maintenanceArea,
        double maintenanceLighting,
        double maintenanceTransit,
        int total,
        boolean ghostTown
) {}
