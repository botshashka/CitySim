package dev.citysim.budget;

public class SubsystemBudget {
    public BudgetSubsystem subsystem;
    public double required;
    public double paid;
    public double ratio;
    public BudgetSubsystemState state;
    public double multiplier;

    public static SubsystemBudget of(BudgetSubsystem subsystem, double required, double paid) {
        SubsystemBudget snapshot = new SubsystemBudget();
        snapshot.subsystem = subsystem;
        snapshot.required = Math.max(0.0, required);
        snapshot.paid = Math.max(0.0, paid);
        if (snapshot.required <= 0.0) {
            snapshot.ratio = 1.0;
            snapshot.state = BudgetSubsystemState.FUNDED;
            snapshot.multiplier = 1.0;
            return snapshot;
        }
        snapshot.ratio = snapshot.paid / snapshot.required;
        snapshot.ratio = Math.max(0.0, Math.min(1.0, snapshot.ratio));
        if (snapshot.ratio >= 1.0) {
            snapshot.state = BudgetSubsystemState.FUNDED;
        } else if (snapshot.ratio > 0.0) {
            snapshot.state = BudgetSubsystemState.UNDERFUNDED;
        } else {
            snapshot.state = BudgetSubsystemState.OFFLINE;
        }
        snapshot.multiplier = snapshot.ratio;
        return snapshot;
    }
}
