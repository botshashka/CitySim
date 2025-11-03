package dev.citysim.economy;

/**
 * Aggregated economic signals for a city.
 */
public final class CityEconomy {
    private double gdp;
    private double gdpPerCapita;
    private double gdpEma;
    private double gdpReturnEma;
    private double lviAverage;
    private double lviEma;
    private double indexPrice = 100.0D;
    private double volatilityEma;
    private long lastUpdated;
    private int population;

    public double gdp() {
        return gdp;
    }

    public double gdpPerCapita() {
        return gdpPerCapita;
    }

    public double gdpEma() {
        return gdpEma;
    }

    public double gdpReturnEma() {
        return gdpReturnEma;
    }

    public double lviAverage() {
        return lviAverage;
    }

    public double lviEma() {
        return lviEma;
    }

    public double indexPrice() {
        return indexPrice;
    }

    public double volatilityEma() {
        return volatilityEma;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    public int population() {
        return population;
    }

    void update(double gdp,
                double gdpPerCapita,
                double lviAverage,
                int population,
                double gdpAlpha,
                double lviAlpha,
                double indexAlpha,
                double gdpReturn,
                double lviReturn,
                double populationReturn,
                double indexWeightGdp,
                double indexWeightLvi,
                double indexWeightPopulation) {
        this.gdp = gdp;
        this.gdpPerCapita = gdpPerCapita;
        this.lviAverage = lviAverage;
        this.population = population;
        this.lastUpdated = System.currentTimeMillis();

        this.gdpEma = blend(this.gdpEma, gdp, gdpAlpha);
        this.lviEma = blend(this.lviEma, lviAverage, lviAlpha);
        this.gdpReturnEma = blend(this.gdpReturnEma, gdpReturn, indexAlpha);

        double weightedReturn = indexWeightGdp * gdpReturn
                + indexWeightLvi * lviReturn
                + indexWeightPopulation * populationReturn;
        if (Double.isFinite(weightedReturn)) {
            this.indexPrice = Math.max(1.0D, this.indexPrice * (1.0D + weightedReturn));
            double volatilitySample = weightedReturn * weightedReturn;
            this.volatilityEma = blend(this.volatilityEma, volatilitySample, indexAlpha);
        }
    }

    private double blend(double current, double sample, double alpha) {
        if (!Double.isFinite(sample)) {
            return current;
        }
        if (!Double.isFinite(current) || current == 0.0D) {
            return sample;
        }
        alpha = Math.min(Math.max(alpha, 0.0D), 1.0D);
        return (1.0D - alpha) * current + alpha * sample;
    }
}
