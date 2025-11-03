package dev.citysim.economy;

import dev.citysim.city.City;
import dev.citysim.stats.HappinessCalculator;

/**
 * Computes land value index (LVI) scores for districts using configuration weights.
 */
public final class LandValueCalculator {
    private final EconomySettings settings;
    private final HappinessCalculator happinessCalculator;

    public LandValueCalculator(EconomySettings settings, HappinessCalculator happinessCalculator) {
        this.settings = settings;
        this.happinessCalculator = happinessCalculator;
    }

    public double computeLvi(City city,
                             double light,
                             double nature,
                             double access,
                             double pollution,
                             double crowdingPenalty) {
        double lightNorm = normalizeLight(light);
        double crowdNorm = normalizeCrowding(crowdingPenalty);
        double accessNorm = clamp01(access);
        double natureNorm = clamp01(nature);
        double pollutionNorm = clamp01(pollution);

        double value = 0.5
                + settings.weightLight() * lightNorm
                + settings.weightNature() * natureNorm
                + settings.weightAccess() * accessNorm
                - settings.weightPollution() * pollutionNorm
                - settings.weightCrowding() * crowdNorm;

        value = Math.max(0.0D, Math.min(1.0D, value));
        return value * 100.0D;
    }

    private double normalizeLight(double light) {
        double neutral = happinessCalculator.getLightNeutral();
        double max = 15.0D;
        if (light >= neutral) {
            double range = Math.max(1.0D, max - neutral);
            return Math.min(1.0D, (light - neutral) / range);
        }
        double range = Math.max(1.0D, neutral);
        return Math.max(0.0D, 1.0D - ((neutral - light) / range));
    }

    private double normalizeCrowding(double penalty) {
        double maxPenalty = happinessCalculator.getOvercrowdingMaxPenalty();
        if (maxPenalty <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, penalty / maxPenalty));
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
