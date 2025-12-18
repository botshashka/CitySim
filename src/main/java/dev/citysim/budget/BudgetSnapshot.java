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
    public double landManagementMultiplier;

    public boolean preview;
}
