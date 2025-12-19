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
        city.adminFundingMultiplier = city.lastBudgetSnapshot.adminMultiplier;
        city.logisticsFundingMultiplier = city.lastBudgetSnapshot.logisticsMultiplier;
        city.publicWorksFundingMultiplier = city.lastBudgetSnapshot.publicWorksMultiplier;
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
        city.adminFundingMultiplier = snapshot.adminMultiplier;
        city.logisticsFundingMultiplier = snapshot.logisticsMultiplier;
        city.publicWorksFundingMultiplier = snapshot.publicWorksMultiplier;
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
        double sanitized = Math.max(0.0, Math.min(BudgetDefaults.MAX_TAX_RATE, rate));
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
        snapshot.adminEffectiveMultiplier = effectiveAdminMultiplier(city, snapshot.adminMultiplier);
        snapshot.logisticsEffectiveMultiplier = effectiveLogisticsMultiplier(city, snapshot.logisticsMultiplier);
        snapshot.publicWorksEffectiveMultiplier = effectivePublicWorksMultiplier(city, snapshot.publicWorksMultiplier);

        snapshot.toleratedTax = toleratedTax(city);
        snapshot.collectionEfficiency = collectionEfficiency(city);
        snapshot.trustDelta = computeTrustDelta(city, snapshot);
        snapshot.trust = clampTrust(city.trust + snapshot.trustDelta);
        snapshot.trustState = trustState(snapshot.trust);
        snapshot.austerityEnabled = city.austerityEnabled;

        snapshot.net = income.effectiveTotal - expenses.totalPaid;
        snapshot.treasuryAfter = snapshot.treasuryBefore + snapshot.net;

        city.trust = snapshot.trust;
        city.adminFundingMultiplier = snapshot.adminEffectiveMultiplier;
        city.logisticsFundingMultiplier = snapshot.logisticsEffectiveMultiplier;
        city.publicWorksFundingMultiplier = snapshot.publicWorksEffectiveMultiplier;
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

        double austerityFactor = city.austerityEnabled ? BudgetDefaults.AUSTERITY_UPKEEP_MULT : 1.0;

        double adminRequired = Math.max(0.0, city.population * adminPerCapita * austerityFactor);
        double adminPaid = Math.min(adminRequired, available);
        available -= adminPaid;
        SubsystemBudget admin = SubsystemBudget.of(BudgetSubsystem.ADMINISTRATION, adminRequired, adminPaid);

        double logisticsRequired = Math.max(0.0, maintenanceTransit * transitCost * austerityFactor);
        double logisticsPaid = Math.min(logisticsRequired, available);
        available -= logisticsPaid;
        SubsystemBudget logistics = SubsystemBudget.of(BudgetSubsystem.LOGISTICS, logisticsRequired, logisticsPaid);

        double publicWorksRequired = Math.max(0.0, maintenanceLighting * lightingCost * austerityFactor);
        double publicWorksPaid = Math.min(publicWorksRequired, available);
        available -= publicWorksPaid;
        SubsystemBudget publicWorks = SubsystemBudget.of(BudgetSubsystem.PUBLIC_WORKS, publicWorksRequired, publicWorksPaid);

        income.toleratedTax = toleratedTax(city);
        income.collectionEfficiency = collectionEfficiency(city);

        double effectiveIncome = income.rawTotal * effectiveAdminMultiplier(city, admin.multiplier) * income.collectionEfficiency;
        income.adminMultiplier = admin.multiplier;
        income.effectiveTotal = effectiveIncome;

        BudgetExpenses expenses = new BudgetExpenses();
        expenses.administration = admin;
        expenses.logistics = logistics;
        expenses.publicWorks = publicWorks;
        expenses.totalRequired = adminRequired + logisticsRequired + publicWorksRequired;
        expenses.totalPaid = adminPaid + logisticsPaid + publicWorksPaid;
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
        return Math.max(0.0, Math.min(BudgetDefaults.MAX_TAX_RATE, value));
    }

    private double effectiveAdminMultiplier(City city, double actual) {
        double capped = city.austerityEnabled ? Math.min(actual, BudgetDefaults.AUSTERITY_CAP) : actual;
        return clampMultiplier(capped);
    }

    private double effectiveLogisticsMultiplier(City city, double actual) {
        double capped = city.austerityEnabled ? Math.min(actual, BudgetDefaults.AUSTERITY_CAP) : actual;
        return clampMultiplier(capped);
    }

    private double effectivePublicWorksMultiplier(City city, double actual) {
        double capped = city.austerityEnabled ? Math.min(actual, BudgetDefaults.AUSTERITY_CAP) : actual;
        return clampMultiplier(capped);
    }

    private double collectionEfficiency(City city) {
        double trustRatio = clampMultiplier(city.trust / 100.0);
        double floor = BudgetDefaults.TRUST_COLLECTION_FLOOR;
        return floor + (1.0 - floor) * trustRatio;
    }

    private double toleratedTax(City city) {
        double tolerated = BudgetDefaults.TRUST_BASE_TOLERANCE
                + (clampMultiplier(city.trust / 100.0) * BudgetDefaults.TRUST_TOLERANCE_BONUS);
        tolerated = Math.max(BudgetDefaults.TRUST_TOLERANCE_MIN, Math.min(BudgetDefaults.MAX_TAX_RATE, tolerated));
        return tolerated;
    }

    private int computeTrustDelta(City city, BudgetSnapshot snapshot) {
        int delta = 0;
        boolean adminOffline = snapshot.expenses.administration != null && snapshot.expenses.administration.state == BudgetSubsystemState.OFFLINE;
        boolean logiOffline = snapshot.expenses.logistics != null && snapshot.expenses.logistics.state == BudgetSubsystemState.OFFLINE;
        boolean worksOffline = snapshot.expenses.publicWorks != null && snapshot.expenses.publicWorks.state == BudgetSubsystemState.OFFLINE;
        boolean anyOffline = adminOffline || logiOffline || worksOffline;
        boolean allFunded = snapshot.expenses.administration != null && snapshot.expenses.administration.state == BudgetSubsystemState.FUNDED
                && snapshot.expenses.logistics != null && snapshot.expenses.logistics.state == BudgetSubsystemState.FUNDED
                && snapshot.expenses.publicWorks != null && snapshot.expenses.publicWorks.state == BudgetSubsystemState.FUNDED;
        boolean noneOffline = !anyOffline;

        double toleratedTax = snapshot.toleratedTax;
        if (allFunded && city.taxRate <= toleratedTax && !city.austerityEnabled) {
            delta += 2;
        } else if (noneOffline && city.taxRate <= toleratedTax && !city.austerityEnabled) {
            delta += 1;
        }
        if (anyOffline) {
            delta -= 2;
        }

        if (city.taxRate > toleratedTax) {
            double over = city.taxRate - toleratedTax;
            int taxPenalty = (int) Math.min(3, Math.round(over * BudgetDefaults.OVER_TAX_SCALE));
            delta -= taxPenalty;
        }

        delta = Math.max(-3, Math.min(2, delta));
        if (city.austerityEnabled && delta > 0) {
            delta = 0;
        }
        return delta;
    }

    private int clampTrust(int trust) {
        if (trust < 0) {
            return 0;
        }
        if (trust > 100) {
            return 100;
        }
        return trust;
    }

    private String trustState(int trust) {
        if (trust >= 60) {
            return "STABLE";
        }
        if (trust >= 40) {
            return "TENSE";
        }
        if (trust >= 20) {
            return "UNREST";
        }
        return "CRISIS";
    }

    private double clampMultiplier(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
