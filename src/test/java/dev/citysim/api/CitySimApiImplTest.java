package dev.citysim.api;

import dev.citysim.TestPluginFactory;
import dev.citysim.api.internal.CitySimApiImpl;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.stats.ProsperityBreakdown;
import dev.citysim.stats.StatsService;
import org.bukkit.Location;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CitySimApiImplTest {

    private Plugin owningPlugin;
    private CityManager cityManager;
    private StatsService statsService;
    private CitySimApiImpl api;

    @BeforeEach
    void setUp() {
        owningPlugin = TestPluginFactory.create("citysim-test");
        cityManager = mock(CityManager.class);
        statsService = mock(StatsService.class);
        when(cityManager.all()).thenReturn(List.of());
        api = new CitySimApiImpl(owningPlugin, cityManager, statsService);
        verify(cityManager).addListener(api);
        verify(statsService).addStatsUpdateListener(api);
    }

    @AfterEach
    void tearDown() {
        api.shutdown();
    }

    @Test
    void snapshotsExposeCityData() {
        City city = sampleCity();
        when(cityManager.all()).thenReturn(List.of(city));
        when(cityManager.get("alpha_city")).thenReturn(city);

        Collection<CitySnapshot> cities = api.getCities();
        assertEquals(1, cities.size());
        CitySnapshot snapshot = cities.iterator().next();
        assertEquals("alpha_city", snapshot.id());
        assertEquals("Alpha City", snapshot.name());
        assertEquals("world", snapshot.world());
        assertTrue(snapshot.highrise());
        assertEquals(List.of("Alice"), snapshot.mayors());
        assertEquals(1, snapshot.areas().size());
        CityAreaSnapshot area = snapshot.areas().get(0);
        assertEquals(0, area.minX());
        assertFalse(area.fullHeight());

        CityStatsSnapshot stats = snapshot.stats();
        assertNotNull(stats);
        assertEquals(42, stats.population());
        assertEquals(10, stats.stations());
        assertEquals(12345.0, stats.gdp());
        assertEquals(2.5, stats.sectorServ());
        assertNotNull(stats.prosperityBreakdown());
        assertNotNull(stats.economyBreakdown());

        Optional<CitySnapshot> byId = api.getCity("alpha_city");
        assertTrue(byId.isPresent());
        assertEquals("Alpha City", byId.get().name());

        Location location = mock(Location.class);
        when(cityManager.cityAt(location)).thenReturn(city);
        assertTrue(api.cityAt(location).isPresent());
    }

    @Test
    void lifecycleListenerReceivesEventsAndCanUnregister() {
        City city = sampleCity();
        CityLifecycleListener listener = mock(CityLifecycleListener.class);
        Plugin listenerPlugin = TestPluginFactory.create("listener");
        ListenerSubscription subscription = api.registerLifecycleListener(listenerPlugin, listener);

        api.onCityCreated(city);
        verify(listener).onCityCreated(any());

        subscription.unregister();
        api.onCityUpdated(city);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void listenersRemovedWhenOwnerDisables() {
        City city = sampleCity();
        CityLifecycleListener lifecycleListener = mock(CityLifecycleListener.class);
        CityStatsListener statsListener = mock(CityStatsListener.class);
        Plugin dependent = TestPluginFactory.create("dependent");
        api.registerLifecycleListener(dependent, lifecycleListener);
        api.registerStatsListener(dependent, statsListener);

        PluginDisableEvent event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(dependent);
        api.onPluginDisable(event);

        api.onCityCreated(city);
        api.onCityStatsUpdated(city);
        verifyNoInteractions(lifecycleListener);
        verifyNoInteractions(statsListener);
    }

    @Test
    void statsListenersReceiveUpdates() {
        City city = sampleCity();
        CityStatsListener listener = mock(CityStatsListener.class);
        ListenerSubscription subscription = api.registerStatsListener(TestPluginFactory.create("stats"), listener);

        api.onCityStatsUpdated(city);
        verify(listener).onCityStatsUpdated(any(), any());

        subscription.unregister();
        api.onCityStatsUpdated(city);
        verifyNoMoreInteractions(listener);
    }

    private static City sampleCity() {
        City city = new City();
        city.id = "alpha_city";
        city.name = "Alpha City";
        city.world = "world";
        city.highrise = true;
        city.priority = 3;
        city.mayors.add("Alice");

        Cuboid cuboid = new Cuboid();
        cuboid.world = "world";
        cuboid.minX = 0;
        cuboid.minY = 10;
        cuboid.minZ = 0;
        cuboid.maxX = 9;
        cuboid.maxY = 19;
        cuboid.maxZ = 9;
        city.cuboids.add(cuboid);

        city.population = 42;
        city.adultPopulation = 30;
        city.employed = 20;
        city.unemployed = 5;
        city.adultNone = 2;
        city.adultNitwit = 1;
        city.beds = 50;
        city.prosperity = 88;
        city.stations = 10;
        city.level = 2;
        city.levelProgress = 0.5;
        city.employmentRate = 0.66;
        city.housingRatio = 1.2;
        city.transitCoverage = 0.75;
        city.statsTimestamp = 123456789L;
        city.gdp = 12345.0;
        city.gdpPerCapita = 321.0;
        city.sectorAgri = 1.0;
        city.sectorInd = 2.0;
        city.sectorServ = 2.5;
        city.jobsPressure = 0.4;
        city.housingPressure = 0.1;
        city.transitPressure = 0.3;
        city.landValue = 9876.0;
        city.migrationZeroPopArrivals = 2;

        ProsperityBreakdown prosperity = new ProsperityBreakdown();
        prosperity.base = 50;
        prosperity.lightPoints = 5.0;
        prosperity.employmentPoints = 6.0;
        prosperity.overcrowdingPenalty = -2.0;
        prosperity.naturePoints = 4.0;
        prosperity.pollutionPenalty = -1.0;
        prosperity.housingPoints = 3.0;
        prosperity.transitPoints = 2.0;
        prosperity.total = 88;
        city.prosperityBreakdown = prosperity;

        EconomyBreakdown economy = new EconomyBreakdown();
        economy.base = 50;
        economy.employmentUtilization = 0.8;
        economy.housingBalance = 0.7;
        economy.transitCoverage = 0.75;
        economy.lighting = 0.9;
        economy.nature = 0.6;
        economy.pollutionPenalty = -0.2;
        economy.overcrowdingPenalty = -0.1;
        economy.maintenanceLighting = 1.5;
        economy.maintenanceTransit = 1.0;
        economy.total = 90;
        city.economyBreakdown = economy;

        return city;
    }
}
