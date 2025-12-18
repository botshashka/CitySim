package dev.citysim.budget;

public final class BudgetDefaults {
    public static final double DEFAULT_TAX_RATE = 0.06;
    public static final double DEFAULT_LAND_TAX_RATE = 0.05;

    public static final long DEFAULT_BUDGET_INTERVAL_TICKS = 6000L;
    public static final long MIN_BUDGET_INTERVAL_TICKS = 20L;
    public static final long MAX_BUDGET_INTERVAL_TICKS = 12000L;

    public static final double ADMIN_PER_CAPITA = 1.0;
    public static final double TRANSIT_COST = 14.0;
    public static final double LIGHTING_COST = 10.0;
    public static final double AREA_COST = 12.0;

    private BudgetDefaults() {
    }
}
