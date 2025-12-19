package dev.citysim.stats;

import dev.citysim.city.City;

public class EconomyCalculator {

    private static final double EMPLOYMENT_NEUTRAL = 0.75;
    private static final double BASE_PRODUCTIVITY = 20.0;
    private static final double MULTIPLIER_BASE = 0.5;
    private static final double MULTIPLIER_SPAN = 1.0;
    private static final double LIGHTING_DRAG_MAX = 2.0;
    private static final double TRANSIT_DRAG_PER_STATION = 0.3;
    private static final double TRANSIT_DRAG_MAX = 3.0;
    private static final double TRANSIT_NEUTRAL_NORMALIZED = 0.5;

    private final ProsperityCalculator prosperityCalculator;

    public EconomyCalculator(ProsperityCalculator prosperityCalculator) {
        this.prosperityCalculator = prosperityCalculator;
    }

    public EconomyComputation compute(City city, ProsperityBreakdown prosperity, City.BlockScanCache metrics) {
        if (city == null) {
            return null;
        }

        EconomyBreakdown breakdown = new EconomyBreakdown();
        populateCapsAndTargets(breakdown);
        boolean ghostTown = city.isGhostTown() || city.population <= 0;
        if (prosperity != null && prosperity.isGhostTown()) {
            ghostTown = true;
        }
        breakdown.setGhostTown(ghostTown);

        if (ghostTown) {
            breakdown.base = prosperity != null ? prosperity.base : 0;
            breakdown.total = 0;
            return new EconomyComputation(breakdown, 0.0, 0.0, city.sectorAgri, city.sectorInd, city.sectorServ,
                    0.0, 0.0, 0.0, 0.0);
        }

        breakdown.base = prosperity != null ? prosperity.base : breakdown.base;

        double employmentPoints = prosperity != null ? prosperity.employmentPoints : computeEmploymentPoints(city.employmentRate);
        breakdown.employmentUtilization = employmentPoints;

        double housingPoints = prosperity != null ? prosperity.housingPoints : computeHousingPoints(city);
        breakdown.housingBalance = housingPoints;

        double transitPoints = prosperity != null ? prosperity.transitPoints : prosperityCalculator.computeTransitPoints(city);
        if (prosperity != null) {
            transitPoints *= logisticsMultiplier(city);
        }
        breakdown.transitCoverage = transitPoints;

        double lightingPoints = prosperity != null ? prosperity.lightPoints * publicWorksMultiplier(city) : computeLightingPoints(city, metrics);
        breakdown.lighting = lightingPoints;

        double naturePoints = prosperity != null ? prosperity.naturePoints : computeNaturePoints(metrics);
        breakdown.nature = naturePoints;

        double pollutionPenalty = prosperity != null ? prosperity.pollutionPenalty : prosperityCalculator.computePollutionPenalty(metrics);
        breakdown.pollutionPenalty = pollutionPenalty;

        double overcrowdingPenalty = prosperity != null ? prosperity.overcrowdingPenalty : prosperityCalculator.computeOvercrowdingPenalty(city);
        breakdown.overcrowdingPenalty = overcrowdingPenalty;

        double lightingDrag = computeLightingDrag(metrics);
        double transitDrag = computeTransitDrag(city);
        breakdown.maintenanceLighting = lightingDrag;
        breakdown.maintenanceTransit = transitDrag;

        double total = breakdown.base
                + employmentPoints
                + housingPoints
                + transitPoints
                + lightingPoints
                + naturePoints
                - pollutionPenalty
                - overcrowdingPenalty;

        total = clamp(total, 0.0, 100.0);
        breakdown.total = (int) Math.round(total);

        double prosperityMultiplier = MULTIPLIER_BASE + (total / 100.0) * MULTIPLIER_SPAN;
        double workingPopulation = Math.max(0, city.employed);
        double gdp = workingPopulation * BASE_PRODUCTIVITY * prosperityMultiplier;
        double gdpPerCapita = city.population <= 0 ? 0.0 : gdp / city.population;

        double jobsPressure = city.employmentRate - EMPLOYMENT_NEUTRAL;
        double housingPressure = city.housingRatio - 1.0;
        double normalizedTransitCoverage = prosperityCalculator.normalizeTransitCoverage(city.transitCoverage);
        // Pressures are stored as signed deltas so that surpluses (+) and deficits (-) are visible to the UI.
        // Transit uses the same easing normalization as prosperity scoring, with 0.5 treated as the neutral baseline.
        double transitPressure = normalizedTransitCoverage - TRANSIT_NEUTRAL_NORMALIZED;

        double landValue = computeLandValue(city, metrics);

        return new EconomyComputation(breakdown, gdp, gdpPerCapita, city.sectorAgri, city.sectorInd, city.sectorServ,
                jobsPressure, housingPressure, transitPressure, landValue);
    }

    private double computeEmploymentPoints(double employmentRate) {
        double employmentMaxPts = prosperityCalculator.getEmploymentMaxPts();
        if (employmentRate >= EMPLOYMENT_NEUTRAL) {
            double surplus = employmentRate - EMPLOYMENT_NEUTRAL;
            double normalized = (1.0 - EMPLOYMENT_NEUTRAL) <= 0.0 ? 0.0 : surplus / (1.0 - EMPLOYMENT_NEUTRAL);
            return clamp(normalized * employmentMaxPts, -employmentMaxPts, employmentMaxPts);
        }
        double deficit = EMPLOYMENT_NEUTRAL - employmentRate;
        double normalized = EMPLOYMENT_NEUTRAL <= 0.0 ? 0.0 : deficit / EMPLOYMENT_NEUTRAL;
        return clamp(-normalized * employmentMaxPts, -employmentMaxPts, employmentMaxPts);
    }

    private double computeHousingPoints(City city) {
        double housingRatio = city.housingRatio;
        double housingMaxPts = prosperityCalculator.getHousingMaxPts();
        double surplusCap = prosperityCalculator.getHousingSurplusCap();
        double shortageFloor = prosperityCalculator.getHousingShortageFloor();
        double score;
        if (housingRatio >= 1.0) {
            double surplus = housingRatio - 1.0;
            double normalized = (surplusCap - 1.0) <= 0.0 ? 0.0 : surplus / (surplusCap - 1.0);
            score = Math.min(1.0, Math.max(0.0, normalized));
        } else {
            double shortage = 1.0 - housingRatio;
            double normalized = (1.0 - shortageFloor) <= 0.0 ? 0.0 : shortage / (1.0 - shortageFloor);
            score = -Math.min(1.0, Math.max(0.0, normalized));
        }
        return clamp(score * housingMaxPts, -housingMaxPts, housingMaxPts);
    }

    private double computeLightingPoints(City city, City.BlockScanCache metrics) {
        if (metrics == null) {
            return 0.0;
        }
        double lightNeutral = prosperityCalculator.getLightNeutral();
        double lightMaxPts = prosperityCalculator.getLightMaxPts();
        double normalized = (metrics.light - lightNeutral) / lightNeutral;
        double raw = clamp(normalized * lightMaxPts, -lightMaxPts, lightMaxPts);
        return raw * publicWorksMultiplier(city);
    }

    private double computeNaturePoints(City.BlockScanCache metrics) {
        if (metrics == null) {
            return 0.0;
        }
        double adjustedNature = prosperityCalculator.adjustNatureRatio(metrics.nature, metrics.natureSamples);
        double natureMaxPts = prosperityCalculator.getNatureMaxPts();
        double target = prosperityCalculator.getNatureTargetRatio();
        double score = (adjustedNature - target) / target;
        return clamp(score * natureMaxPts, -natureMaxPts, natureMaxPts);
    }

    private double computeLightingDrag(City.BlockScanCache metrics) {
        if (metrics == null) {
            return 0.0;
        }
        double lightNeutral = prosperityCalculator.getLightNeutral();
        double excess = Math.max(0.0, metrics.light - lightNeutral);
        double normalized = lightNeutral <= 0.0 ? 0.0 : excess / (lightNeutral * 2.0);
        return clamp(normalized * LIGHTING_DRAG_MAX, 0.0, LIGHTING_DRAG_MAX);
    }

    private double computeTransitDrag(City city) {
        double stations = Math.max(0.0, city.stations);
        double drag = stations * TRANSIT_DRAG_PER_STATION;
        return clamp(drag, 0.0, TRANSIT_DRAG_MAX);
    }

    private double computeLandValue(City city, City.BlockScanCache metrics) {
        double lightNeutral = prosperityCalculator.getLightNeutral();
        double lightFactor = metrics == null ? 0.5 : clamp(metrics.light / (lightNeutral * 2.0), 0.0, 1.0);
        double natureFactor = metrics == null ? 0.0 : clamp(metrics.nature, 0.0, 1.0);
        double transitFactor = clamp(city.transitCoverage, 0.0, 1.0);
        double pollutionFactor = metrics == null ? 0.0 : clamp(metrics.pollution / 0.05, 0.0, 1.0);

        double positive = (lightFactor + natureFactor + transitFactor) / 3.0;
        double blended = positive - (pollutionFactor * 0.5);
        return clamp(blended * 100.0, 0.0, 100.0);
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

    private void populateCapsAndTargets(EconomyBreakdown breakdown) {
        breakdown.employmentMaxPts = prosperityCalculator.getEmploymentMaxPts();
        breakdown.housingMaxPts = prosperityCalculator.getHousingMaxPts();
        breakdown.transitMaxPts = prosperityCalculator.getTransitMaxPts();
        breakdown.lightingMaxPts = prosperityCalculator.getLightMaxPts();
        breakdown.natureMaxPts = prosperityCalculator.getNatureMaxPts();

        breakdown.pollutionMaxPenalty = prosperityCalculator.getPollutionMaxPenalty();
        breakdown.overcrowdingMaxPenalty = prosperityCalculator.getOvercrowdMaxPenalty();

        breakdown.employmentNeutral = prosperityCalculator.getEmploymentNeutral();
        breakdown.transitNeutral = TRANSIT_NEUTRAL_NORMALIZED;
        breakdown.lightNeutral = prosperityCalculator.getLightNeutral();
        breakdown.natureTargetRatio = prosperityCalculator.getNatureTargetRatio();
        breakdown.pollutionTargetRatio = prosperityCalculator.getPollutionTargetRatio();
    }

    public record EconomyComputation(EconomyBreakdown breakdown,
                                     double gdp,
                                     double gdpPerCapita,
                                     double sectorAgri,
                                     double sectorInd,
                                     double sectorServ,
                                     double jobsPressure,
                                     double housingPressure,
                                     double transitPressure,
                                     double landValue) {
    }

    private double publicWorksMultiplier(City city) {
        if (city == null) {
            return 1.0;
        }
        double multiplier = city.publicWorksFundingMultiplier;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = city.lastBudgetSnapshot != null ? city.lastBudgetSnapshot.publicWorksMultiplier : 1.0;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, multiplier);
    }

    private double logisticsMultiplier(City city) {
        if (city == null) {
            return 1.0;
        }
        double multiplier = city.logisticsFundingMultiplier;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = city.lastBudgetSnapshot != null ? city.lastBudgetSnapshot.logisticsMultiplier : 1.0;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, multiplier);
    }
}
