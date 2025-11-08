package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;

public class ProsperityCalculator {
    private static final double OVERCROWDING_BASELINE = 3.0;
    private static final double TRANSIT_IDEAL_SPACING_BLOCKS = 75.0;
    private static final double TRANSIT_EASING_EXPONENT = 0.5;
    private static final double DEFAULT_NATURE_TARGET_RATIO = 0.10;
    private static final int NATURE_MIN_EFFECTIVE_SAMPLES = 36;
    private static final int POLLUTION_MIN_EFFECTIVE_SAMPLES = 36;
    private static final double POLLUTION_BLOCK_FULL_WEIGHT = 12.0;
    private static final double HOUSING_SURPLUS_CAP = 1.2;
    private static final double HOUSING_SHORTAGE_FLOOR = 0.6;

    private double baseScore = 50.0;
    private double lightNeutral = 2.0;
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double employmentNeutral = 0.75;
    private double overcrowdMaxPenalty = 10;
    private double natureMaxPts = 10;
    private double natureTargetRatio = DEFAULT_NATURE_TARGET_RATIO;
    private double pollutionMaxPenalty = 15;
    private double pollutionTargetRatio = 0.02;
    private double housingMaxPts = 10;
    private double transitMaxPts = 5;
    private StationCountingMode stationCountingMode = StationCountingMode.MANUAL;

    public ProsperityBreakdown calculate(City city, City.BlockScanCache metrics) {
        ProsperityBreakdown hb = new ProsperityBreakdown();
        int pop = city.population;
        int employed = city.employed;
        boolean ghostByPopulation = pop <= 0;
        boolean flaggedGhostTown = city.isGhostTown();
        hb.setGhostTown(flaggedGhostTown || ghostByPopulation);
        hb.base = (int) Math.round(baseScore);

        if (hb.isGhostTown()) {
            hb.lightPoints = 0.0;
            hb.employmentPoints = 0.0;
            hb.overcrowdingPenalty = 0.0;
            hb.naturePoints = 0.0;
            hb.pollutionPenalty = 0.0;
            hb.housingPoints = 0.0;
            hb.transitPoints = 0.0;
            hb.total = 0;
            return hb;
        }

        double lightScore = metrics.light;
        double lightScoreNormalized = lightNeutral <= 0.0
                ? 0.0
                : (lightScore - lightNeutral) / lightNeutral;
        hb.lightPoints = applySymmetricScaling(lightScoreNormalized, lightMaxPts);

        double employmentScore;
        if (pop <= 0) {
            employmentScore = 0.0;
        } else {
            double employmentRate = (double) employed / (double) pop;
            if (employmentRate >= employmentNeutral) {
                employmentScore = (employmentRate - employmentNeutral) / (1.0 - employmentNeutral);
            } else {
                employmentScore = (employmentRate - employmentNeutral) / employmentNeutral;
            }
        }
        hb.employmentPoints = applySymmetricScaling(employmentScore, employmentMaxPts);

        hb.overcrowdingPenalty = clamp(metrics.overcrowdingPenalty, 0.0, overcrowdMaxPenalty);

        double adjustedNature = adjustNatureRatio(metrics.nature, metrics.natureSamples);
        double natureScore = natureTargetRatio <= 0.0
                ? 0.0
                : (adjustedNature - natureTargetRatio) / natureTargetRatio;
        hb.naturePoints = applySymmetricScaling(natureScore, natureMaxPts);

        hb.pollutionPenalty = calculatePollutionPenalty(
                metrics.pollution,
                Math.max(0, metrics.pollutingBlocks),
                Math.max(0, metrics.pollutionSamples));

        int beds = Math.max(0, city.beds);
        double housingRatio = pop <= 0 ? 1.0 : (double) beds / Math.max(1.0, (double) pop);
        double housingScore;
        if (housingRatio >= 1.0) {
            double surplus = housingRatio - 1.0;
            double normalized = surplus / Math.max(0.0001, HOUSING_SURPLUS_CAP - 1.0);
            housingScore = Math.min(1.0, normalized);
        } else {
            double shortage = 1.0 - housingRatio;
            double normalized = shortage / Math.max(0.0001, 1.0 - HOUSING_SHORTAGE_FLOOR);
            housingScore = -Math.min(1.0, normalized);
        }
        hb.housingPoints = applySymmetricScaling(housingScore, housingMaxPts);

        hb.transitPoints = computeTransitPoints(city);

        double total = hb.base
                + hb.lightPoints
                + hb.employmentPoints
                - hb.overcrowdingPenalty
                + hb.naturePoints
                - hb.pollutionPenalty
                + hb.housingPoints
                + hb.transitPoints;

        if (total < 0) total = 0;
        if (total > 100) total = 100;
        hb.total = (int) Math.round(total);
        return hb;
    }

    public void setBaseScore(double baseScore) {
        this.baseScore = Math.max(0.0, baseScore);
    }

    public double getBaseScore() {
        return baseScore;
    }

    public void setLightNeutral(double lightNeutral) {
        this.lightNeutral = Math.max(0.1, lightNeutral);
    }

    public double getLightNeutral() {
        return lightNeutral;
    }

    public void setLightMaxPts(double lightMaxPts) {
        this.lightMaxPts = lightMaxPts;
    }

    public double getLightMaxPts() {
        return lightMaxPts;
    }

    public void setEmploymentMaxPts(double employmentMaxPts) {
        this.employmentMaxPts = employmentMaxPts;
    }

    public double getEmploymentMaxPts() {
        return employmentMaxPts;
    }

    public void setEmploymentNeutral(double employmentNeutral) {
        this.employmentNeutral = clamp(employmentNeutral, 0.0001, 0.9999);
    }

    public double getEmploymentNeutral() {
        return employmentNeutral;
    }

    public void setOvercrowdMaxPenalty(double overcrowdMaxPenalty) {
        this.overcrowdMaxPenalty = overcrowdMaxPenalty;
    }

    public double getOvercrowdMaxPenalty() {
        return overcrowdMaxPenalty;
    }

    public void setNatureMaxPts(double natureMaxPts) {
        this.natureMaxPts = natureMaxPts;
    }

    public double getNatureMaxPts() {
        return natureMaxPts;
    }

    public void setNatureTargetRatio(double natureTargetRatio) {
        this.natureTargetRatio = clamp(natureTargetRatio, 0.0001, 1.0);
    }

    public void setPollutionMaxPenalty(double pollutionMaxPenalty) {
        this.pollutionMaxPenalty = pollutionMaxPenalty;
    }

    public double getPollutionMaxPenalty() {
        return pollutionMaxPenalty;
    }

    public void setPollutionTargetRatio(double pollutionTargetRatio) {
        this.pollutionTargetRatio = clamp(pollutionTargetRatio, 0.0001, 1.0);
    }

    public double getPollutionTargetRatio() {
        return pollutionTargetRatio;
    }

    public void setHousingMaxPts(double housingMaxPts) {
        this.housingMaxPts = housingMaxPts;
    }

    public double getHousingMaxPts() {
        return housingMaxPts;
    }

    public double getHousingSurplusCap() {
        return HOUSING_SURPLUS_CAP;
    }

    public double getHousingShortageFloor() {
        return HOUSING_SHORTAGE_FLOOR;
    }

    public void setTransitMaxPts(double transitMaxPts) {
        this.transitMaxPts = Math.max(0.0, transitMaxPts);
    }

    public double getTransitMaxPts() {
        return transitMaxPts;
    }

    public void setStationCountingMode(StationCountingMode stationCountingMode) {
        this.stationCountingMode = stationCountingMode;
    }

    public double computePollutionPenalty(City.BlockScanCache metrics) {
        if (metrics == null) {
            return 0.0;
        }
        return calculatePollutionPenalty(metrics.pollution,
                Math.max(0, metrics.pollutingBlocks),
                Math.max(0, metrics.pollutionSamples));
    }

    public double computeOvercrowdingPenalty(City city) {
        double effectiveArea = totalEffectiveArea(city);
        if (effectiveArea <= 0) {
            return 0.0;
        }
        int pop = Math.max(0, city.population);
        if (pop <= 0) {
            return 0.0;
        }
        double density = pop / (effectiveArea / 1000.0);
        double penalty = density * 0.5 - OVERCROWDING_BASELINE;
        if (penalty < 0.0) {
            penalty = 0.0;
        }
        if (penalty > overcrowdMaxPenalty) {
            penalty = overcrowdMaxPenalty;
        }
        return penalty;
    }

    public double computeTransitPoints(City city) {
        if (stationCountingMode == StationCountingMode.DISABLED) {
            return 0.0;
        }
        double area = totalFootprintArea(city);
        if (area <= 0.0 || transitMaxPts <= 0.0) {
            return 0.0;
        }

        double actualStations = Math.max(0, city.stations);
        if (actualStations <= 0.0) {
            return -transitMaxPts;
        }

        double idealStations = Math.max(1.0, area / (TRANSIT_IDEAL_SPACING_BLOCKS * TRANSIT_IDEAL_SPACING_BLOCKS));
        double coverageRatio = actualStations / idealStations;
        double easedCoverage = Math.pow(coverageRatio, TRANSIT_EASING_EXPONENT);
        double score = transitMaxPts * clamp(easedCoverage, 0.0, 1.0);
        return clamp(score, -transitMaxPts, transitMaxPts);
    }

    public double computeTransitCoverage(City city) {
        if (stationCountingMode == StationCountingMode.DISABLED) {
            return 0.0;
        }
        double area = totalFootprintArea(city);
        if (area <= 0.0) {
            return 0.0;
        }
        double idealStations = Math.max(1.0, area / (TRANSIT_IDEAL_SPACING_BLOCKS * TRANSIT_IDEAL_SPACING_BLOCKS));
        double actualStations = Math.max(0.0, city.stations);
        if (actualStations <= 0.0) {
            return 0.0;
        }
        double coverageRatio = actualStations / idealStations;
        return clamp(coverageRatio, 0.0, 1.0);
    }

    public double normalizeTransitCoverage(double coverageRatio) {
        double ratio = clamp(coverageRatio, 0.0, 1.0);
        double eased = Math.pow(ratio, TRANSIT_EASING_EXPONENT);
        return clamp(eased, 0.0, 1.0);
    }

    double adjustNatureRatio(double rawRatio, int sampleCount) {
        double clampedRatio = clamp(rawRatio, 0.0, 1.0);
        int samples = Math.max(0, sampleCount);
        double sampleWeight = Math.min(1.0, samples / (double) NATURE_MIN_EFFECTIVE_SAMPLES);
        double adjusted = natureTargetRatio + sampleWeight * (clampedRatio - natureTargetRatio);
        return clamp(adjusted, 0.0, 1.0);
    }

    public double getNatureTargetRatio() {
        return natureTargetRatio;
    }

    private double applySymmetricScaling(double normalizedScore, double maxPoints) {
        double span = Math.abs(maxPoints);
        if (span == 0.0) {
            return 0.0;
        }
        return clamp(normalizedScore * span, -span, span);
    }

    private double calculatePollutionPenalty(double pollutionRatio, int pollutingBlocks, int pollutionSamples) {
        if (pollutionMaxPenalty <= 0.0 || pollutionTargetRatio <= 0.0) {
            return 0.0;
        }
        if (pollutingBlocks <= 0 || pollutionSamples <= 0) {
            return 0.0;
        }

        double sampleGrace = 1.0 / pollutionSamples;
        double adjustedRatio = pollutionRatio - pollutionTargetRatio - sampleGrace;
        if (adjustedRatio <= 0.0) {
            return 0.0;
        }

        double severity = adjustedRatio / pollutionTargetRatio;
        double sampleWeight = Math.min(1.0, pollutionSamples / (double) POLLUTION_MIN_EFFECTIVE_SAMPLES);
        double blockWeight = Math.min(1.0, pollutingBlocks / POLLUTION_BLOCK_FULL_WEIGHT);
        double weightedSeverity = severity * sampleWeight * blockWeight;
        return clamp(weightedSeverity * pollutionMaxPenalty, 0.0, pollutionMaxPenalty);
    }

    private double totalEffectiveArea(City city) {
        long sum = 0;
        if (city.cuboids == null) {
            return 0;
        }
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            long area = width * length;
            if (city.highrise) {
                long height = (long) (c.maxY - c.minY + 1);
                if (height < 1) height = 1;
                area *= height;
            }
            sum += area;
        }
        return (double) sum;
    }

    public double estimateFootprintArea(City city) {
        return totalFootprintArea(city);
    }

    private double totalFootprintArea(City city) {
        long sum = 0;
        if (city.cuboids == null) {
            return 0;
        }
        for (Cuboid c : city.cuboids) {
            if (c == null || c.world == null) {
                continue;
            }
            long width = (long) (c.maxX - c.minX + 1);
            long length = (long) (c.maxZ - c.minZ + 1);
            if (width < 0) width = 0;
            if (length < 0) length = 0;
            sum += width * length;
        }
        return (double) sum;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
