package dev.citysim.api;

/**
 * Active stats for a city at the time of the latest scan.
 */
public record CityStatsSnapshot(
        int population,
        int adultPopulation,
        int employed,
        int unemployed,
        int adultNone,
        int adultNitwit,
        int beds,
        int prosperity,
        int stations,
        int level,
        double levelProgress,
        double employmentRate,
        double housingRatio,
        double transitCoverage,
        long statsTimestamp,
        double gdp,
        double gdpPerCapita,
        double sectorAgri,
        double sectorInd,
        double sectorServ,
        double jobsPressure,
        double housingPressure,
        double transitPressure,
        double landValue,
        int migrationZeroPopArrivals,
        boolean ghostTown,
        CityProsperitySnapshot prosperityBreakdown,
        CityEconomySnapshot economyBreakdown
) {}
