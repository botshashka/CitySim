package dev.citysim.stats.scan;

import dev.citysim.city.City;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.StationCountResult;

public interface CityScanCallbacks {
    StationCountResult refreshStationCount(City city);

    City.BlockScanCache ensureBlockScanCache(City city, boolean forceRefresh);

    HappinessBreakdown calculateHappinessBreakdown(City city, City.BlockScanCache cache);
}
