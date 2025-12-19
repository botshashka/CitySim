package dev.citysim.budget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxOverageTest {

    @Test
    void maxOverageAmountUsesWorstOfBoth() {
        double over = TaxOverage.maxOverageAmount(0.10, 0.07, 0.06, 0.05);
        assertEquals(0.04, over, 1e-9); // GDP overage dominates

        over = TaxOverage.maxOverageAmount(0.06, 0.12, 0.10, 0.05);
        assertEquals(0.07, over, 1e-9); // land overage dominates
    }

    @Test
    void maxOverageFractionClampedToOne() {
        double frac = TaxOverage.maxOverageFraction(0.30, 0.30, 0.0, 0.0);
        assertEquals(1.0, frac, 1e-9);
    }
}
