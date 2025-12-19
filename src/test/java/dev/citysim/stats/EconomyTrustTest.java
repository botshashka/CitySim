package dev.citysim.stats;

import dev.citysim.city.City;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomyTrustTest {

    @Test
    void trustNeutralHasNoEffect() {
        EconomyCalculator calculator = new EconomyCalculator(new ProsperityCalculator());
        City city = baselineCity();
        city.trust = 60;

        EconomyCalculator.EconomyComputation result = calculator.compute(city, null, null);
        assertEquals(0.0, result.breakdown().trustPoints, 0.01);
    }

    @Test
    void trustHighReachesPositiveCap() {
        EconomyCalculator calculator = new EconomyCalculator(new ProsperityCalculator());
        City city = baselineCity();
        city.trust = 100;

        EconomyCalculator.EconomyComputation result = calculator.compute(city, null, null);
        assertEquals(8.0, result.breakdown().trustPoints, 0.05);
    }

    @Test
    void trustLowReachesNegativeCap() {
        EconomyCalculator calculator = new EconomyCalculator(new ProsperityCalculator());
        City city = baselineCity();
        city.trust = 0;

        EconomyCalculator.EconomyComputation result = calculator.compute(city, null, null);
        assertEquals(-18.0, result.breakdown().trustPoints, 0.05);
    }

    private City baselineCity() {
        City city = new City();
        city.population = 10;
        city.employed = 5;
        city.housingRatio = 1.0;
        city.transitCoverage = 0.5;
        city.transitPressure = 0;
        city.sectorAgri = 1.0;
        city.sectorInd = 1.0;
        city.sectorServ = 1.0;
        city.gdp = 100.0;
        city.landValue = 50.0;
        city.stations = 1;
        city.trust = 60;
        return city;
    }
}
