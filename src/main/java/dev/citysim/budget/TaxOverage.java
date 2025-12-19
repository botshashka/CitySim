package dev.citysim.budget;

/**
 * Shared helpers for tax tolerance and overage calculations across GDP and land taxes.
 */
final class TaxOverage {

    private TaxOverage() {
    }

    static double maxOverageAmount(double gdpRate, double landRate, double toleratedTax, double toleratedLandTax) {
        double overGdp = gdpRate > toleratedTax ? gdpRate - toleratedTax : 0.0;
        double overLand = landRate > toleratedLandTax ? landRate - toleratedLandTax : 0.0;
        return Math.max(overGdp, overLand);
    }

    static double maxOverageFraction(double gdpRate, double landRate, double toleratedTax, double toleratedLandTax) {
        double fracGdp = gdpRate > toleratedTax ? (gdpRate - toleratedTax) / Math.max(BudgetDefaults.MAX_TAX_RATE, 0.01) : 0.0;
        double fracLand = landRate > toleratedLandTax ? (landRate - toleratedLandTax) / Math.max(BudgetDefaults.MAX_LAND_TAX_RATE, 0.01) : 0.0;
        return clamp(Math.max(fracGdp, fracLand), 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
