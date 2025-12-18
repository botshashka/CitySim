package dev.citysim.budget;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.EconomyBreakdown;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

public class BudgetService {

    private final Plugin plugin;
    private final CityManager cityManager;
    private final BudgetUpdateScheduler scheduler;

    private boolean landTaxEnabled = true;
    private double landTaxRateDefault = BudgetDefaults.DEFAULT_LAND_TAX_RATE;
    private double adminPerCapita = BudgetDefaults.ADMIN_PER_CAPITA;
    private double transitCost = BudgetDefaults.TRANSIT_COST;
    private double lightingCost = BudgetDefaults.LIGHTING_COST;
    private double areaCost = BudgetDefaults.AREA_COST;

    public BudgetService(Plugin plugin, CityManager cityManager) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.scheduler = new BudgetUpdateScheduler(plugin, this::tickAllCities);
    }

    public void reload(FileConfiguration config) {
        scheduler.updateConfig(config);
        if (config == null) {
            return;
        }
        landTaxEnabled = config.getBoolean("budget.land_tax.enabled", true);
        double configuredLandRate = config.contains("budget.land_tax.rate")
                ? config.getDouble("budget.land_tax.rate", BudgetDefaults.DEFAULT_LAND_TAX_RATE)
                : config.getDouble("budget.land_tax.per_capita", BudgetDefaults.DEFAULT_LAND_TAX_RATE);
        landTaxRateDefault = safeNonNegative(configuredLandRate, BudgetDefaults.DEFAULT_LAND_TAX_RATE);
        adminPerCapita = safeNonNegative(config.getDouble("budget.admin_per_capita", BudgetDefaults.ADMIN_PER_CAPITA), BudgetDefaults.ADMIN_PER_CAPITA);
        transitCost = safeNonNegative(config.getDouble("budget.transit_cost", BudgetDefaults.TRANSIT_COST), BudgetDefaults.TRANSIT_COST);
        lightingCost = safeNonNegative(config.getDouble("budget.lighting_cost", BudgetDefaults.LIGHTING_COST), BudgetDefaults.LIGHTING_COST);
        areaCost = safeNonNegative(config.getDouble("budget.area_cost", BudgetDefaults.AREA_COST), BudgetDefaults.AREA_COST);
    }

    public void start() {
        if (scheduler.isRunning()) {
            return;
        }
        scheduler.start();
    }

    public void stop() {
        scheduler.stop();
    }

    public void restart() {
        scheduler.restart();
    }

    public BudgetSnapshot ensureSnapshot(City city) {
        if (city == null) {
            return null;
        }
        if (city.lastBudgetSnapshot == null) {
            return tickCity(city);
        }
        return city.lastBudgetSnapshot;
    }

    public BudgetSnapshot tickCity(City city) {
        if (city == null) {
            return null;
        }
        BudgetSnapshot snapshot = computeSnapshot(city, false);
        if (snapshot == null) {
            return null;
        }
        city.treasury = snapshot.treasuryAfter;
        city.lastBudgetSnapshot = snapshot;
        return snapshot;
    }

    public BudgetSnapshot previewCity(City city) {
        return computeSnapshot(city, true);
    }

    public void tickAllCities() {
        for (City city : cityManager.all()) {
            if (city == null) {
                continue;
            }
            try {
                tickCity(city);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Budget tick failed for city " + city.id + ": " + ex.getMessage(), ex);
            }
        }
    }

    public void setTaxRate(City city, double rate) {
        if (city == null) {
            return;
        }
        double sanitized = Math.max(0.0, Math.min(1.0, rate));
        city.taxRate = sanitized;
    }

    public void setLandTaxRate(City city, double rate) {
        if (city == null) {
            return;
        }
        double sanitized = Math.max(0.0, Math.min(1.0, rate));
        city.landTaxRate = sanitized;
    }

    public boolean isLandTaxEnabled() {
        return landTaxEnabled;
    }

    public double getLandTaxPerCapita() {
        return landTaxRateDefault;
    }

    public double getAdminPerCapita() {
        return adminPerCapita;
    }

    public double getTransitCost() {
        return transitCost;
    }

    public double getLightingCost() {
        return lightingCost;
    }

    public double getAreaCost() {
        return areaCost;
    }

    public long getBudgetIntervalTicks() {
        return scheduler.getBudgetIntervalTicks();
    }

    private BudgetSnapshot computeSnapshot(City city, boolean preview) {
        if (city == null) {
            return null;
        }
        long timestamp = System.currentTimeMillis();
        BudgetSnapshot snapshot = new BudgetSnapshot();
        snapshot.timestamp = timestamp;
        snapshot.preview = preview;
        snapshot.treasuryBefore = city.treasury;

        double sanitizedTax = sanitizeRate(city.taxRate, BudgetDefaults.DEFAULT_TAX_RATE);
        double sanitizedLandTax = sanitizeRate(city.landTaxRate, landTaxRateDefault);
        if (sanitizedTax <= 0.0 && city.lastBudgetSnapshot == null) {
            sanitizedTax = BudgetDefaults.DEFAULT_TAX_RATE;
        }
        city.taxRate = sanitizedTax;
        city.landTaxRate = sanitizedLandTax;

        BudgetIncome income = computeIncome(city);
        BudgetExpenses expenses = computeExpenses(city, income);

        snapshot.income = income;
        snapshot.expenses = expenses;
        snapshot.adminMultiplier = income.adminMultiplier;
        snapshot.logisticsMultiplier = expenses.logistics != null ? expenses.logistics.multiplier : 1.0;
        snapshot.publicWorksMultiplier = expenses.publicWorks != null ? expenses.publicWorks.multiplier : 1.0;
        snapshot.landManagementMultiplier = expenses.landManagement != null ? expenses.landManagement.multiplier : 1.0;

        snapshot.net = income.effectiveTotal - expenses.totalPaid;
        snapshot.treasuryAfter = snapshot.treasuryBefore + snapshot.net;
        return snapshot;
    }

    private BudgetIncome computeIncome(City city) {
        BudgetIncome income = new BudgetIncome();
        income.taxRate = Math.max(0.0, Math.min(1.0, city.taxRate));
        city.taxRate = income.taxRate;

        double gdp = Math.max(0.0, city.gdp);
        income.gdpTax = gdp * income.taxRate;

        double landTax = 0.0;
        if (landTaxEnabled) {
            double landValueRatio = Math.max(0.0, city.landValue) / 100.0;
            double landRate = city.landTaxRate;
            if (!Double.isFinite(landRate) || landRate < 0.0) {
                landRate = landTaxRateDefault;
            }
            landRate = Math.max(0.0, Math.min(1.0, landRate));
            city.landTaxRate = landRate;
            landTax = Math.max(0.0, city.population) * landValueRatio * landRate;
        }
        income.landTaxEnabled = landTaxEnabled;
        income.landTax = landTax;
        income.rawTotal = income.gdpTax + income.landTax;
        return income;
    }

    private BudgetExpenses computeExpenses(City city, BudgetIncome income) {
        double available = snapshotAvailableForSpending(city, income);
        EconomyBreakdown breakdown = city.economyBreakdown;
        double maintenanceTransit = breakdown != null ? breakdown.maintenanceTransit : 0.0;
        double maintenanceLighting = breakdown != null ? breakdown.maintenanceLighting : 0.0;
        double maintenanceArea = breakdown != null ? breakdown.maintenanceArea : 0.0;

        double adminRequired = Math.max(0.0, city.population * adminPerCapita);
        double adminPaid = Math.min(adminRequired, available);
        available -= adminPaid;
        SubsystemBudget admin = SubsystemBudget.of(BudgetSubsystem.ADMINISTRATION, adminRequired, adminPaid);

        double logisticsRequired = Math.max(0.0, maintenanceTransit * transitCost);
        double logisticsPaid = Math.min(logisticsRequired, available);
        available -= logisticsPaid;
        SubsystemBudget logistics = SubsystemBudget.of(BudgetSubsystem.LOGISTICS, logisticsRequired, logisticsPaid);

        double publicWorksRequired = Math.max(0.0, maintenanceLighting * lightingCost);
        double publicWorksPaid = Math.min(publicWorksRequired, available);
        available -= publicWorksPaid;
        SubsystemBudget publicWorks = SubsystemBudget.of(BudgetSubsystem.PUBLIC_WORKS, publicWorksRequired, publicWorksPaid);

        double landManagementRequired = Math.max(0.0, maintenanceArea * areaCost);
        double landManagementPaid = Math.min(landManagementRequired, available);
        SubsystemBudget landManagement = SubsystemBudget.of(BudgetSubsystem.LAND_MANAGEMENT, landManagementRequired, landManagementPaid);

        double effectiveIncome = income.rawTotal * admin.multiplier;
        income.adminMultiplier = admin.multiplier;
        income.effectiveTotal = effectiveIncome;

        BudgetExpenses expenses = new BudgetExpenses();
        expenses.administration = admin;
        expenses.logistics = logistics;
        expenses.publicWorks = publicWorks;
        expenses.landManagement = landManagement;
        expenses.totalRequired = adminRequired + logisticsRequired + publicWorksRequired + landManagementRequired;
        expenses.totalPaid = adminPaid + logisticsPaid + publicWorksPaid + landManagementPaid;
        return expenses;
    }

    private double snapshotAvailableForSpending(City city, BudgetIncome income) {
        double treasury = city.treasury;
        if (!Double.isFinite(treasury)) {
            treasury = 0.0;
        }
        double available = treasury + income.rawTotal;
        if (available < 0.0) {
            return 0.0;
        }
        return available;
    }

    private double safeNonNegative(double configured, double fallback) {
        if (!Double.isFinite(configured) || configured < 0.0) {
            return fallback;
        }
        return configured;
    }

    private double sanitizeRate(double value, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
