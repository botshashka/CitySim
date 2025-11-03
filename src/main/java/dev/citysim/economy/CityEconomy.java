package dev.citysim.economy;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores the aggregate economic metrics for a city.
 */
public final class CityEconomy {
    public enum Trend { UP, FLAT, DOWN }

    private double gdp;
    private double gdpPerCapita;
    private double gdpEma;
    private double gdpReturnEma;
    private double lviAverage;
    private double lviEma;
    private double indexPrice = 100.0;
    private double volatilityEma;
    private double lastGdp;
    private double lastLvi;
    private double lastPopulation;
    private Trend lviTrend = Trend.FLAT;
    private Trend gdpTrend = Trend.FLAT;

    private final AtomicLong lastUpdated = new AtomicLong();

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

    public Trend lviTrend() {
        return lviTrend;
    }

    public Trend gdpTrend() {
        return gdpTrend;
    }

    public long lastUpdated() {
        return lastUpdated.get();
    }

    public double lastGdp() {
        return lastGdp;
    }

    public double lastLvi() {
        return lastLvi;
    }

    public double lastPopulation() {
        return lastPopulation;
    }

    public void update(double gdp,
                       double gdpPerCapita,
                       double lviAverage,
                       double gdpReturn,
                       double lviReturn,
                       double populationReturn,
                       int population,
                       double alpha,
                       double indexWeightGdp,
                       double indexWeightLvi,
                       double indexWeightPopulation) {
        this.gdp = gdp;
        this.gdpPerCapita = gdpPerCapita;
        this.lviAverage = lviAverage;

        this.gdpEma = ema(gdp, gdpEma, alpha);
        this.lviEma = ema(lviAverage, lviEma, alpha);
        this.gdpReturnEma = ema(gdpReturn, gdpReturnEma, alpha);

        double weightedReturn = indexWeightGdp * gdpReturn
                + indexWeightLvi * lviReturn
                + indexWeightPopulation * populationReturn;
        if (!Double.isFinite(weightedReturn)) {
            weightedReturn = 0.0;
        }

        double previousPrice = indexPrice > 0.0 ? indexPrice : 100.0;
        double updatedPrice = previousPrice * (1.0 + weightedReturn);
        if (!Double.isFinite(updatedPrice) || updatedPrice <= 1.0) {
            updatedPrice = Math.max(1.0, previousPrice);
        }
        this.indexPrice = updatedPrice;

        double volatilitySample = Math.abs(weightedReturn);
        this.volatilityEma = ema(volatilitySample, volatilityEma, alpha);

        this.lviTrend = computeTrend(lviAverage, lviEma, 0.5);
        this.gdpTrend = computeTrend(gdpReturnEma, 0.0025);

        this.lastGdp = gdp;
        this.lastLvi = lviAverage;
        this.lastPopulation = population;
        this.lastUpdated.set(System.currentTimeMillis());
    }

    private static double ema(double value, double current, double alpha) {
        if (!Double.isFinite(value)) {
            value = 0.0;
        }
        if (alpha <= 0.0) {
            return value;
        }
        if (current == 0.0) {
            return value;
        }
        return alpha * value + (1.0 - alpha) * current;
    }

    private static Trend computeTrend(double sample, double baseline, double tolerance) {
        if (Double.isNaN(sample) || Double.isNaN(baseline)) {
            return Trend.FLAT;
        }
        if (Math.abs(sample - baseline) <= tolerance) {
            return Trend.FLAT;
        }
        return sample > baseline ? Trend.UP : Trend.DOWN;
    }

    private static Trend computeTrend(double emaValue, double tolerance) {
        if (Math.abs(emaValue) <= tolerance) {
            return Trend.FLAT;
        }
        return emaValue > 0.0 ? Trend.UP : Trend.DOWN;
    }
}
