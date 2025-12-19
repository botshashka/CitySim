package dev.citysim.budget;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.EconomyBreakdown;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

public class BudgetService {

    private final Plugin plugin;
    private final CityManager cityManager;
    private final BudgetUpdateScheduler scheduler;
    private final Map<String, PreviewCacheEntry> previewCache = new HashMap<>();
    private final Map<String, Double> trustCarry = new HashMap<>();

    private boolean landTaxEnabled = true;
    private double landTaxRateDefault = BudgetDefaults.DEFAULT_LAND_TAX_RATE;
    private double adminPerCapita = BudgetDefaults.ADMIN_PER_CAPITA;
    private double transitCost = BudgetDefaults.TRANSIT_COST;
    private double lightingCost = BudgetDefaults.LIGHTING_COST;
    private double taxBaseTolerance = BudgetDefaults.TRUST_BASE_TOLERANCE;
    private double taxToleranceBonus = BudgetDefaults.TRUST_TOLERANCE_BONUS;
    private double taxToleranceMin = BudgetDefaults.TRUST_TOLERANCE_MIN;
    private double landTaxBaseTolerance = BudgetDefaults.LAND_TRUST_BASE_TOLERANCE;
    private double landTaxToleranceBonus = BudgetDefaults.LAND_TRUST_TOLERANCE_BONUS;
    private double landTaxToleranceMin = BudgetDefaults.LAND_TRUST_TOLERANCE_MIN;
    private long austerityMinOnIntervals = BudgetDefaults.AUSTERITY_MIN_ON_INTERVALS;
    private long austerityCooldownIntervals = BudgetDefaults.AUSTERITY_COOLDOWN_INTERVALS;

    private static final long PREVIEW_TTL_MS = 5000L;

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
        taxBaseTolerance = clamp(config.getDouble("budget.tolerance.base", BudgetDefaults.TRUST_BASE_TOLERANCE), 0.0, BudgetDefaults.MAX_TAX_RATE);
        taxToleranceBonus = clamp(config.getDouble("budget.tolerance.bonus", BudgetDefaults.TRUST_TOLERANCE_BONUS), 0.0, 1.0);
        taxToleranceMin = clamp(config.getDouble("budget.tolerance.min", BudgetDefaults.TRUST_TOLERANCE_MIN), 0.0, BudgetDefaults.MAX_TAX_RATE);
        landTaxBaseTolerance = clamp(config.getDouble("budget.land_tax.tolerance.base", BudgetDefaults.LAND_TRUST_BASE_TOLERANCE), 0.0, BudgetDefaults.MAX_LAND_TAX_RATE);
        landTaxToleranceBonus = clamp(config.getDouble("budget.land_tax.tolerance.bonus", BudgetDefaults.LAND_TRUST_TOLERANCE_BONUS), 0.0, 1.0);
        landTaxToleranceMin = clamp(config.getDouble("budget.land_tax.tolerance.min", BudgetDefaults.LAND_TRUST_TOLERANCE_MIN), 0.0, BudgetDefaults.MAX_LAND_TAX_RATE);
        austerityMinOnIntervals = Math.max(0, config.getLong("budget.austerity.min_on_intervals", BudgetDefaults.AUSTERITY_MIN_ON_INTERVALS));
        austerityCooldownIntervals = Math.max(0, config.getLong("budget.austerity.cooldown_intervals", BudgetDefaults.AUSTERITY_COOLDOWN_INTERVALS));
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
        BudgetSnapshot snapshot = computeSnapshot(city, false, TrustAdjustmentMode.TICK);
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
        return previewCity(city, false);
    }

    public BudgetSnapshot previewCity(City city, boolean force) {
        if (city == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        PreviewCacheEntry cached = previewCache.get(city.id);
        PreviewSignature signature = PreviewSignature.from(city, landTaxEnabled);
        if (!force && cached != null && cached.signature.equals(signature) && (now - cached.timestamp) < PREVIEW_TTL_MS) {
            return cached.snapshot;
        }
        BudgetSnapshot snapshot = computeSnapshot(city, true, TrustAdjustmentMode.PREVIEW);
        if (snapshot != null) {
            previewCache.put(city.id, new PreviewCacheEntry(signature, snapshot, now));
        }
        return snapshot;
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
        invalidatePreview(city);
    }

    public void setLandTaxRate(City city, double rate) {
        if (city == null) {
            return;
        }
        double sanitized = Math.max(0.0, Math.min(BudgetDefaults.MAX_LAND_TAX_RATE, rate));
        city.landTaxRate = sanitized;
        invalidatePreview(city);
    }

    public BudgetSnapshot applyPolicyChangeTrust(City city) {
        BudgetSnapshot snapshot = computeSnapshot(city, true, TrustAdjustmentMode.IMMEDIATE_POLICY);
        if (snapshot != null) {
            double impact = computePolicyTrustImpact(city, snapshot);
            if (impact > 0.0) {
                double target = city.trust - impact;
                city.trust = clampTrust((int) Math.round(Math.max(0.0, target)));
            }
            refreshTrustInEconomyBreakdown(city);
            previewCache.remove(city.id);
        }
        return snapshot;
    }

    public BudgetSnapshot previewPolicySnapshot(City city) {
        return computeSnapshot(city, true, TrustAdjustmentMode.IMMEDIATE_POLICY);
    }

    public int projectedTrustAfterPolicy(City city, BudgetSnapshot snapshot) {
        if (city == null || snapshot == null) {
            return city != null ? clampTrust(city.trust) : 0;
        }
        double impact = computePolicyTrustImpact(city, snapshot);
        double target = city.trust - impact;
        return clampTrust((int) Math.round(Math.max(0.0, target)));
    }

    public void invalidatePreview(City city) {
        if (city != null) {
            previewCache.remove(city.id);
        }
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

    public long getAusterityMinOnTicks() {
        return Math.max(0L, austerityMinOnIntervals * getBudgetIntervalTicks());
    }

    public long getAusterityCooldownTicks() {
        return Math.max(0L, austerityCooldownIntervals * getBudgetIntervalTicks());
    }

    private BudgetSnapshot computeSnapshot(City city, boolean preview, TrustAdjustmentMode trustMode) {
        if (city == null) {
            return null;
        }
        long timestamp = System.currentTimeMillis();
        BudgetSnapshot snapshot = new BudgetSnapshot();
        snapshot.timestamp = timestamp;
        snapshot.preview = preview;
        snapshot.treasuryBefore = city.treasury;

        double sanitizedTax = sanitizeRate(city.taxRate, BudgetDefaults.DEFAULT_TAX_RATE, BudgetDefaults.MAX_TAX_RATE);
        double sanitizedLandTax = sanitizeRate(city.landTaxRate, landTaxRateDefault, BudgetDefaults.MAX_LAND_TAX_RATE);
        if (sanitizedTax <= 0.0 && city.lastBudgetSnapshot == null) {
            sanitizedTax = BudgetDefaults.DEFAULT_TAX_RATE;
        }
        if (!preview) {
            city.taxRate = sanitizedTax;
            city.landTaxRate = sanitizedLandTax;
        }

        BudgetIncome income = computeIncome(city, sanitizedTax, sanitizedLandTax);
        BudgetExpenses expenses = computeExpenses(city, income, sanitizedTax, sanitizedLandTax);

        snapshot.income = income;
        snapshot.expenses = expenses;
        snapshot.adminMultiplier = income.adminMultiplier;
        snapshot.logisticsMultiplier = expenses.logistics != null ? expenses.logistics.multiplier : 1.0;
        snapshot.publicWorksMultiplier = expenses.publicWorks != null ? expenses.publicWorks.multiplier : 1.0;
        snapshot.adminEffectiveMultiplier = effectiveAdminMultiplier(city, snapshot.adminMultiplier);
        snapshot.logisticsEffectiveMultiplier = effectiveLogisticsMultiplier(city, snapshot.logisticsMultiplier);
        snapshot.publicWorksEffectiveMultiplier = effectivePublicWorksMultiplier(city, snapshot.publicWorksMultiplier);

        snapshot.toleratedTax = toleratedTax(city, BudgetDefaults.MAX_TAX_RATE, taxBaseTolerance, taxToleranceBonus, taxToleranceMin);
        snapshot.toleratedLandTax = toleratedTax(city, BudgetDefaults.MAX_LAND_TAX_RATE, landTaxBaseTolerance, landTaxToleranceBonus, landTaxToleranceMin);
        snapshot.collectionEfficiency = collectionEfficiency(city, sanitizedTax, sanitizedLandTax, snapshot.toleratedTax, snapshot.toleratedLandTax);
        double rawDelta = computeTrustDelta(city, snapshot, sanitizedTax, trustMode);
        double carry = trustCarry.getOrDefault(city.id, 0.0);
        double adjustedDelta = rawDelta + carry;
        double appliedDelta;
        double newCarry = carry;
        if (trustMode == TrustAdjustmentMode.IMMEDIATE_POLICY) {
            appliedDelta = adjustedDelta;
            newCarry = 0.0;
        } else {
            appliedDelta = Math.floor(adjustedDelta);
            newCarry = adjustedDelta - appliedDelta;
        }
        snapshot.trustDelta = appliedDelta;
        if (!preview || trustMode == TrustAdjustmentMode.IMMEDIATE_POLICY) {
            trustCarry.put(city.id, newCarry);
        }
        if (trustMode == TrustAdjustmentMode.IMMEDIATE_POLICY) {
            snapshot.trust = clampTrust((int) Math.round(city.trust + appliedDelta));
        } else {
            snapshot.trust = smoothTrust(city.trust, appliedDelta);
        }
        snapshot.trustState = trustState(snapshot.trust);
        snapshot.austerityEnabled = city.austerityEnabled;

        snapshot.net = income.effectiveTotal - expenses.totalPaid;
        snapshot.treasuryAfter = snapshot.treasuryBefore + snapshot.net;

        if (!preview) {
            city.trust = snapshot.trust;
            city.adminFundingMultiplier = snapshot.adminEffectiveMultiplier;
            city.logisticsFundingMultiplier = snapshot.logisticsEffectiveMultiplier;
            city.publicWorksFundingMultiplier = snapshot.publicWorksEffectiveMultiplier;
            refreshTrustInEconomyBreakdown(city);
            previewCache.remove(city.id);
        }

        return snapshot;
    }

    private BudgetIncome computeIncome(City city, double taxRate, double landTaxRate) {
        BudgetIncome income = new BudgetIncome();
        income.taxRate = Math.max(0.0, Math.min(1.0, taxRate));
        income.landTaxRate = Math.max(0.0, Math.min(1.0, landTaxRate));

        double gdp = Math.max(0.0, city.gdp);
        income.gdpTax = gdp * income.taxRate;

        double landTax = 0.0;
        if (landTaxEnabled) {
            double landValueRatio = Math.max(0.0, city.landValue) / 100.0;
            double landRate = landTaxRate;
            if (!Double.isFinite(landRate) || landRate < 0.0) {
                landRate = landTaxRateDefault;
            }
            landRate = Math.max(0.0, Math.min(BudgetDefaults.MAX_LAND_TAX_RATE, landRate));
            landTax = Math.max(0.0, city.population) * landValueRatio * landRate;
        }
        income.landTaxEnabled = landTaxEnabled;
        income.landTax = landTax;
        income.toleratedTax = toleratedTax(city, BudgetDefaults.MAX_TAX_RATE, taxBaseTolerance, taxToleranceBonus, taxToleranceMin);
        income.toleratedLandTax = toleratedTax(city, BudgetDefaults.MAX_LAND_TAX_RATE, landTaxBaseTolerance, landTaxToleranceBonus, landTaxToleranceMin);
        income.rawTotal = income.gdpTax + income.landTax;
        return income;
    }

    private BudgetExpenses computeExpenses(City city, BudgetIncome income, double taxRate, double landTaxRate) {
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

        income.toleratedTax = toleratedTax(city, BudgetDefaults.MAX_TAX_RATE, taxBaseTolerance, taxToleranceBonus, taxToleranceMin);
        income.toleratedLandTax = toleratedTax(city, BudgetDefaults.MAX_LAND_TAX_RATE, landTaxBaseTolerance, landTaxToleranceBonus, landTaxToleranceMin);
        income.collectionEfficiency = collectionEfficiency(city, taxRate, landTaxRate, income.toleratedTax, income.toleratedLandTax);

        double over = TaxOverage.maxOverageAmount(income.taxRate, income.landTaxRate, income.toleratedTax, income.toleratedLandTax);
        double overIncomePenalty = over > 0 ? clamp(1.0 - over * 2.0, 0.25, 1.0) : 1.0;
        double effectiveIncome = income.rawTotal * effectiveAdminMultiplier(city, admin.multiplier) * income.collectionEfficiency * overIncomePenalty;
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

    private double sanitizeRate(double value, double fallback, double maxRate) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0, Math.min(maxRate, value));
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

    private double collectionEfficiency(City city, double gdpTaxRate, double landTaxRate, double toleratedTax, double toleratedLandTax) {
        double trustRatio = clampMultiplier(city.trust / 100.0);
        double floor = BudgetDefaults.TRUST_COLLECTION_FLOOR;
        double overShare = TaxOverage.maxOverageFraction(gdpTaxRate, landTaxRate, toleratedTax, toleratedLandTax);
        if (overShare > 0.0) {
            floor = BudgetDefaults.TRUST_COLLECTION_FLOOR + (BudgetDefaults.OVER_TAX_COLLECTION_FLOOR - BudgetDefaults.TRUST_COLLECTION_FLOOR) * overShare;
        }
        return floor + (1.0 - floor) * trustRatio;
    }

    private double toleratedTax(City city, double maxRate, double base, double bonus, double min) {
        double tolerated = base + (clampMultiplier(city.trust / 100.0) * bonus);
        tolerated = Math.max(min, Math.min(maxRate, tolerated));
        return tolerated;
    }

    private double computeTrustDelta(City city, BudgetSnapshot snapshot, double taxRate, TrustAdjustmentMode mode) {
        double delta = 0;
        boolean adminOffline = snapshot.expenses.administration != null && snapshot.expenses.administration.state == BudgetSubsystemState.OFFLINE;
        boolean logiOffline = snapshot.expenses.logistics != null && snapshot.expenses.logistics.state == BudgetSubsystemState.OFFLINE;
        boolean worksOffline = snapshot.expenses.publicWorks != null && snapshot.expenses.publicWorks.state == BudgetSubsystemState.OFFLINE;
        boolean anyOffline = adminOffline || logiOffline || worksOffline;
        boolean allFunded = snapshot.expenses.administration != null && snapshot.expenses.administration.state == BudgetSubsystemState.FUNDED
                && snapshot.expenses.logistics != null && snapshot.expenses.logistics.state == BudgetSubsystemState.FUNDED
                && snapshot.expenses.publicWorks != null && snapshot.expenses.publicWorks.state == BudgetSubsystemState.FUNDED;
        boolean noneOffline = !anyOffline;

        double toleratedTax = snapshot.toleratedTax;
        if (allFunded && taxRate <= toleratedTax && !city.austerityEnabled) {
            delta += 1;
        } else if (noneOffline && taxRate <= toleratedTax && !city.austerityEnabled) {
            delta += 1;
        }
        if (anyOffline) {
            delta -= 1;
        }

        double overRate = TaxOverage.maxOverageAmount(taxRate,
                snapshot.income != null ? snapshot.income.landTaxRate : city.landTaxRate,
                snapshot.toleratedTax,
                snapshot.toleratedLandTax);
        if (overRate > 0.0) {
            double penalty = overRate * BudgetDefaults.OVER_TAX_SCALE;
            penalty += overRate * overRate * BudgetDefaults.OVER_TAX_SCALE_BONUS;
            if (penalty < 1) {
                penalty = 1;
            }
            delta -= penalty;
        }

        if (mode != TrustAdjustmentMode.IMMEDIATE_POLICY) {
            if (taxRate <= toleratedTax) {
                delta = Math.max(BudgetDefaults.TRUST_DELTA_CAP_NEG, Math.min(BudgetDefaults.TRUST_DELTA_CAP_POS, delta));
            }
            if (city.austerityEnabled && delta > 0) {
                delta = 0;
            }
            if (withinNeutralBand(city.trust) && delta > 0) {
                delta = Math.max(0.5, delta * 0.5);
            }
        }
        return delta;
    }

    private double computePolicyTrustImpact(City city, BudgetSnapshot snapshot) {
        double newTaxRate = snapshot.income != null ? snapshot.income.taxRate : city.taxRate;
        double newLandRate = snapshot.income != null ? snapshot.income.landTaxRate : city.landTaxRate;
        double tolerance = toleratedTax(city, BudgetDefaults.MAX_TAX_RATE, taxBaseTolerance, taxToleranceBonus, taxToleranceMin);
        double toleranceLand = toleratedTax(city, BudgetDefaults.MAX_LAND_TAX_RATE, landTaxBaseTolerance, landTaxToleranceBonus, landTaxToleranceMin);
        double overFraction = TaxOverage.maxOverageFraction(newTaxRate, newLandRate, tolerance, toleranceLand);
        double impact = 0.0;
        if (overFraction > 0.0) {
            impact = overFraction * BudgetDefaults.POLICY_TRUST_IMPACT_SCALE;
            impact = Math.max(BudgetDefaults.POLICY_TRUST_IMPACT_MIN, impact);
        } else if (city.austerityEnabled) {
            boolean healthy = city.treasury > 0.0
                    && (snapshot.expenses.administration == null || snapshot.expenses.administration.state == BudgetSubsystemState.FUNDED)
                    && (snapshot.expenses.logistics == null || snapshot.expenses.logistics.state == BudgetSubsystemState.FUNDED)
                    && (snapshot.expenses.publicWorks == null || snapshot.expenses.publicWorks.state == BudgetSubsystemState.FUNDED);
            if (healthy) {
                impact = BudgetDefaults.AUSTERITY_TRUST_IMPACT_MIN;
            }
        }
        return impact;
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
            return "Stable";
        }
        if (trust >= 40) {
            return "Tense";
        }
        if (trust >= 20) {
            return "Unrest";
        }
        return "Crisis";
    }

    private boolean withinNeutralBand(int trust) {
        return trust >= BudgetDefaults.TRUST_NEUTRAL_BAND_MIN && trust <= BudgetDefaults.TRUST_NEUTRAL_BAND_MAX;
    }

    private int smoothTrust(int current, double delta) {
        double target = clamp(current + delta, 0.0, 100.0);
        double alpha = clamp(BudgetDefaults.TRUST_EMA_ALPHA, 0.0, 1.0);
        double blended = current + alpha * (target - current);
        int smoothed = clampTrust((int) Math.round(blended));
        if (smoothed == current && Math.abs(target - current) >= 1.0 && delta != 0.0) {
            smoothed = clampTrust(current + (target > current ? 1 : -1));
        }
        return smoothed;
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

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private void refreshTrustInEconomyBreakdown(City city) {
        EconomyBreakdown breakdown = city.economyBreakdown;
        if (breakdown == null) {
            return;
        }
        double neutral = breakdown.trustNeutral > 0 ? breakdown.trustNeutral : 60.0;
        double posScale = breakdown.trustPositiveScale > 0.0 ? breakdown.trustPositiveScale : 0.20;
        double negScale = breakdown.trustNegativeScale > 0.0 ? breakdown.trustNegativeScale : 0.30;
        double maxPos = breakdown.trustMaxPts > 0.0 ? breakdown.trustMaxPts : 8.0;
        double maxNeg = breakdown.trustMinPts < 0.0 ? -breakdown.trustMinPts : 18.0;

        double delta = city.trust - neutral;
        double newTrustPoints;
        if (delta >= 0) {
            newTrustPoints = clamp(delta * posScale, 0.0, maxPos);
        } else {
            newTrustPoints = clamp(delta * negScale, -maxNeg, 0.0);
        }
        double oldTrustPoints = breakdown.trustPoints;
        breakdown.trustPoints = newTrustPoints;
        double adjustedTotal = breakdown.total - oldTrustPoints + newTrustPoints;
        breakdown.total = (int) Math.round(clamp(adjustedTotal, 0.0, 100.0));
    }

    private record PreviewSignature(double taxRate,
                                    double landTaxRate,
                                    boolean austerity,
                                    double treasury,
                                    double gdp,
                                    double landValue,
                                    int population,
                                    int employed,
                                    double maintenanceTransit,
                                    double maintenanceLighting,
                                    boolean landTaxEnabled) {
        static PreviewSignature from(City city, boolean landTaxEnabled) {
            EconomyBreakdown breakdown = city.economyBreakdown;
            double maintenanceTransit = breakdown != null ? breakdown.maintenanceTransit : 0.0;
            double maintenanceLighting = breakdown != null ? breakdown.maintenanceLighting : 0.0;
            return new PreviewSignature(
                    city.taxRate,
                    city.landTaxRate,
                    city.austerityEnabled,
                    city.treasury,
                    city.gdp,
                    city.landValue,
                    city.population,
                    city.employed,
                    maintenanceTransit,
                    maintenanceLighting,
                    landTaxEnabled
            );
        }
    }

    private record PreviewCacheEntry(PreviewSignature signature, BudgetSnapshot snapshot, long timestamp) { }

    private enum TrustAdjustmentMode {
        TICK,
        PREVIEW,
        IMMEDIATE_POLICY
    }
}
