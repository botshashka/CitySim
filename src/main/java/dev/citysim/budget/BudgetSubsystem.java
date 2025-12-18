package dev.citysim.budget;

public enum BudgetSubsystem {
    ADMINISTRATION("Administration"),
    LOGISTICS("Logistics"),
    PUBLIC_WORKS("Public Works"),
    LAND_MANAGEMENT("Land Management");

    private final String displayName;

    BudgetSubsystem(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
