package dev.citysim.budget;

public final class BudgetDefaults {
    public static final double DEFAULT_TAX_RATE = 0.06;
    public static final double DEFAULT_LAND_TAX_RATE = 0.05;
    public static final double MAX_TAX_RATE = 0.20;
    public static final double MAX_LAND_TAX_RATE = 0.20;

    public static final long DEFAULT_BUDGET_INTERVAL_TICKS = 6000L;
    public static final long MIN_BUDGET_INTERVAL_TICKS = 20L;
    public static final long MAX_BUDGET_INTERVAL_TICKS = 12000L;

    public static final double ADMIN_PER_CAPITA = 1.0;
    public static final double TRANSIT_COST = 14.0;
    public static final double LIGHTING_COST = 10.0;

    public static final double TRUST_BASE_TOLERANCE = 0.06;
    public static final double TRUST_TOLERANCE_BONUS = 0.10;
    public static final double TRUST_TOLERANCE_MIN = 0.02;
    public static final double LAND_TRUST_BASE_TOLERANCE = 0.05;
    public static final double LAND_TRUST_TOLERANCE_BONUS = 0.08;
    public static final double LAND_TRUST_TOLERANCE_MIN = 0.01;
    public static final double TRUST_COLLECTION_FLOOR = 0.75;
    public static final double OVER_TAX_SCALE = 40.0;
    public static final double OVER_TAX_COLLECTION_FLOOR = 0.50;
    public static final double OVER_TAX_SCALE_BONUS = 140.0;
    public static final double POLICY_TRUST_IMPACT_MIN = 15.0;
    public static final double POLICY_TRUST_IMPACT_SCALE = 120.0;
    public static final double AUSTERITY_TRUST_IMPACT_MIN = 8.0;
    public static final long AUSTERITY_MIN_ON_INTERVALS = 2L;
    public static final long AUSTERITY_COOLDOWN_INTERVALS = 2L;
    public static final long AUSTERITY_CONFIRM_WINDOW_INTERVALS = 1L;
    public static final double TRUST_NEUTRAL_BAND_MIN = 50.0;
    public static final double TRUST_NEUTRAL_BAND_MAX = 70.0;
    public static final int TRUST_DELTA_CAP_POS = 1;
    public static final int TRUST_DELTA_CAP_NEG = -1;
    public static final double TRUST_EMA_ALPHA = 0.35;

    public static final double AUSTERITY_UPKEEP_MULT = 0.70;
    public static final double AUSTERITY_CAP = 0.80;

    private BudgetDefaults() {
    }
}
