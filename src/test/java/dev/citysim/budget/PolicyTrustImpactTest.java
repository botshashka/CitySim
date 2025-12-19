package dev.citysim.budget;

import dev.citysim.TestPluginFactory;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyTrustImpactTest {

    @Test
    void overTaxCalculatesImpact() {
        Plugin plugin = TestPluginFactory.create("test");
        CityManager cityManager = new CityManager(plugin);
        BudgetService service = new BudgetService(plugin, cityManager);
        City city = new City();
        city.taxRate = 0.20;
        city.landTaxRate = 0.05;
        city.trust = 100;

        BudgetSnapshot snapshot = service.previewPolicySnapshot(city);
        double impact = service.projectedTrustAfterPolicy(city, snapshot);
        assertTrue(impact < city.trust, "Policy impact should reduce trust when over tolerance");
    }
}
