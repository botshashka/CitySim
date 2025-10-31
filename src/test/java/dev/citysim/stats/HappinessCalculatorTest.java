package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HappinessCalculatorTest {

    @Test
    void calculatesWeightedBreakdownWithConfiguredInputs() {
        HappinessCalculator calculator = new HappinessCalculator();
        calculator.setLightNeutral(2.0);
        calculator.setLightMaxPts(10.0);
        calculator.setEmploymentMaxPts(10.0);
        calculator.setOvercrowdMaxPenalty(10.0);
        calculator.setNatureMaxPts(10.0);
        calculator.setPollutionMaxPenalty(10.0);
        calculator.setHousingMaxPts(10.0);
        calculator.setTransitMaxPts(5.0);
        calculator.setStationCountingMode(StationCountingMode.MANUAL);

        City city = new City();
        city.id = "test-city";
        city.population = 100;
        city.employed = 80;
        city.beds = 120;
        city.stations = 4;

        Cuboid cuboid = new Cuboid();
        cuboid.world = "world";
        cuboid.minX = 0;
        cuboid.minY = 0;
        cuboid.minZ = 0;
        cuboid.maxX = 99;
        cuboid.maxY = 20;
        cuboid.maxZ = 99;
        city.cuboids.add(cuboid);

        City.BlockScanCache cache = new City.BlockScanCache();
        cache.light = 4.0;
        cache.nature = 0.2;
        cache.natureSamples = 50;
        cache.pollution = 0.01;
        cache.pollutingBlocks = 10;
        cache.overcrowdingPenalty = calculator.computeOvercrowdingPenalty(city);

        HappinessBreakdown breakdown = calculator.calculate(city, cache);

        assertEquals(10.0, breakdown.lightPoints, 0.001);
        assertEquals(2.0, breakdown.employmentPoints, 0.001);
        assertEquals(cache.overcrowdingPenalty, breakdown.overcrowdingPenalty, 0.001);
        assertEquals(10.0, breakdown.naturePoints, 0.001);
        assertEquals(0.0, breakdown.pollutionPenalty, 0.001);
        assertEquals(10.0, breakdown.housingPoints, 0.001);
        assertEquals(5.0, breakdown.transitPoints, 0.001);
        assertEquals(85, breakdown.total);
    }

    @Test
    void rewardsHousingSurplusMoreGenerously() {
        HappinessCalculator calculator = new HappinessCalculator();

        City city = new City();
        city.id = "surplus-city";
        city.population = 329;
        city.beds = 437;

        City.BlockScanCache cache = new City.BlockScanCache();
        cache.light = calculator.getLightNeutral();

        HappinessBreakdown breakdown = calculator.calculate(city, cache);

        assertEquals(10.0, breakdown.housingPoints, 0.01);
    }
}
