package dev.citysim.papi;

import dev.citysim.TestPluginFactory;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitySimExpansionTest {
    private StubCityManager cityManager;
    private CitySimExpansion expansion;

    @BeforeEach
    void setUp() {
        cityManager = new StubCityManager();
        expansion = new CitySimExpansion(cityManager);
    }

    @Test
    void cityNamePlaceholderIsEmptyWhenCityMissing() {
        assertEquals("", expansion.onRequest(null, "cityname_unknown"));
    }

    @Test
    void numericPlaceholderFallsBackToZeroWhenCityMissing() {
        assertEquals("0", expansion.onRequest(null, "pop_unknown"));
    }

    @Test
    void explicitCityIdLookupStillReturnsData() {
        City city = new City();
        city.id = "alpha";
        city.name = "Alpha City";
        city.population = 42;
        cityManager.setCityById(city.id, city);

        assertEquals("Alpha City", expansion.onRequest(null, "cityname_alpha"));
        assertEquals("42", expansion.onRequest(null, "pop_alpha"));
    }

    private static class StubCityManager extends CityManager {
        private final Map<String, City> byId = new HashMap<>();

        StubCityManager() {
            super(TestPluginFactory.create("citysim-expansion"));
        }

        void setCityById(String id, City city) {
            byId.put(id.toLowerCase(Locale.ROOT), city);
        }

        @Override
        public City get(String id) {
            if (id == null) {
                return null;
            }
            return byId.get(id.toLowerCase(Locale.ROOT));
        }
    }
}
