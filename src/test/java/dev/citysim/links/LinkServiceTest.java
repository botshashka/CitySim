package dev.citysim.links;

import dev.citysim.TestPluginFactory;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkServiceTest {

    @Test
    void computeLinksKeepsLinkWhenOpsZero() {
        Plugin plugin = TestPluginFactory.create("link-service");
        CityManager cityManager = new CityManager(plugin);
        City a = cityManager.create("alpha");
        City b = cityManager.create("beta");
        setupCity(a, "world", 10, 0.0);
        setupCity(b, "world", 10, 1.0);
        cityManager.all(); // ensure cities are registered

        LinkService service = new LinkService(cityManager);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("links.enabled", true);
        config.set("links.link_distance_blocks", 1000);
        service.reload(config);
        assertTrue(service.isEnabled(), "LinkService should be enabled in test setup");

        List<CityLink> links = service.computeLinks(a);
        assertFalse(links.isEmpty(), "Links should be present even with ops=0");
        CityLink link = links.get(0);
        assertEquals(b, link.neighbor());
        assertTrue(link.rawStrength() > 0, "Raw strength should be positive");
        assertEquals(0, link.opsStrength(), "Ops strength should be zero when logistics multiplier is zero");
    }

    private void setupCity(City city, String world, int stations, double logiMultiplier) {
        city.world = world;
        city.stations = stations;
        city.logisticsFundingMultiplier = logiMultiplier;
        city.lastBudgetSnapshot = new dev.citysim.budget.BudgetSnapshot();
        city.lastBudgetSnapshot.logisticsMultiplier = logiMultiplier;
        Cuboid cuboid = new Cuboid();
        cuboid.world = world;
        cuboid.minX = 0;
        cuboid.maxX = 10;
        cuboid.minZ = 0;
        cuboid.maxZ = 10;
        cuboid.minY = 0;
        cuboid.maxY = 0;
        cuboid.fullHeight = false;
        city.cuboids.add(cuboid);
    }

}
