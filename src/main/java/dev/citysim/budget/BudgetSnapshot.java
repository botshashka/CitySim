package dev.citysim.budget;

public class BudgetSnapshot {
    public long timestamp;
    public double treasuryBefore;
    public double treasuryAfter;
    public double net;

    public BudgetIncome income;
    public BudgetExpenses expenses;

    public double adminMultiplier;
    public double logisticsMultiplier;
    public double publicWorksMultiplier;

    public double adminEffectiveMultiplier;
    public double logisticsEffectiveMultiplier;
    public double publicWorksEffectiveMultiplier;

    public double toleratedTax;
    public double toleratedLandTax;
    public double collectionEfficiency;
    public int trust;
    public double trustDelta;
    public String trustState;
    public boolean austerityEnabled;

    public boolean preview;
}
