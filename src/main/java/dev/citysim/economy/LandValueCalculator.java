package dev.citysim.economy;

import dev.citysim.city.City;
import dev.citysim.stats.HappinessCalculator;

/**
 * Computes land value indexes based on sampled district metrics.
 */
public final class LandValueCalculator {
    private final double weightLight;
    private final double weightNature;
    private final double weightAccess;
    private final double weightPollution;
    private final double weightCrowding;
    private final HappinessCalculator happinessCalculator;

    public LandValueCalculator(HappinessCalculator happinessCalculator,
                               double weightLight,
                               double weightNature,
                               double weightAccess,
                               double weightPollution,
                               double weightCrowding) {
        this.happinessCalculator = happinessCalculator;
        this.weightLight = weightLight;
        this.weightNature = weightNature;
        this.weightAccess = weightAccess;
        this.weightPollution = weightPollution;
        this.weightCrowding = weightCrowding;
    }

    public double computeLvi(City city, DistrictStats stats, double fallbackAccess) {
        if (stats == null) {
            return 0.0;
        }
        double lightNeutral = happinessCalculator != null ? happinessCalculator.getLightNeutral() : 2.0;
        double lightNorm = normalizeLight(stats.lightAverage(), lightNeutral);
        double natureNorm = clamp01(stats.natureRatio());
        double accessNorm = clamp01(stats.accessScore() > 0.0 ? stats.accessScore() : fallbackAccess);
        double pollutionNorm = clamp01(stats.pollutionRatio());
        double crowdNorm = clamp01(stats.crowdingPenalty() / 10.0);

        double base = 0.5
                + weightLight * lightNorm
                + weightNature * natureNorm
                + weightAccess * accessNorm
                - weightPollution * pollutionNorm
                - weightCrowding * crowdNorm;
        base = clamp01(base);
        return base * 100.0;
    }

    private static double normalizeLight(double lightAverage, double neutral) {
        if (!Double.isFinite(lightAverage) || !Double.isFinite(neutral) || neutral <= 0.0) {
            return 0.5;
        }
        double diff = lightAverage - neutral;
        double scale = neutral * 2.0;
        if (scale <= 0.0) {
            scale = 1.0;
        }
        double normalized = 0.5 + diff / scale;
        return clamp01(normalized);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
