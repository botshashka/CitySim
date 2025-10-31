package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;

public class HappinessCalculator {
    private static final double OVERCROWDING_BASELINE = 3.0;
    private static final double TRANSIT_IDEAL_SPACING_BLOCKS = 75.0;
    private static final double TRANSIT_EASING_EXPONENT = 0.5;
    private static final double NATURE_TARGET_RATIO = 0.10;
    private static final int NATURE_MIN_EFFECTIVE_SAMPLES = 36;
    private static final double HOUSING_SURPLUS_CAP = 1.5;
    private static final double HOUSING_SHORTAGE_FLOOR = 0.6;

    private double lightNeutral = 2.0;
    private double lightMaxPts = 10;
    private double employmentMaxPts = 15;
    private double overcrowdMaxPenalty = 10;
    private double natureMaxPts = 10;
    private double pollutionMaxPenalty = 15;
    private double housingMaxPts = 10;
    private double transitMaxPts = 5;
    private StationCountingMode stationCountingMode = StationCountingMode.MANUAL;

    public HappinessBreakdown calculate(City city, City.BlockScanCache metrics) {
        HappinessBreakdown hb = new HappinessBreakdown();
        int pop = city.population;
        int employed = city.employed;

        double lightScore = metrics.light;
        double lightScoreNormalized = (lightScore - lightNeutral) / lightNeutral;
        hb.lightPoints = clamp(lightScoreNormalized * lightMaxPts, -lightMaxPts, lightMaxPts);

        double employmentNeutral = 0.75;
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
        hb.employmentPoints = clamp(employmentScore * employmentMaxPts, -employmentMaxPts, employmentMaxPts);

        hb.overcrowdingPenalty = clamp(metrics.overcrowdingPenalty, 0.0, overcrowdMaxPenalty);

        double adjustedNature = adjustedNatureRatio(metrics.nature, metrics.natureSamples);
        double natureScore = (adjustedNature - NATURE_TARGET_RATIO) / NATURE_TARGET_RATIO;
        hb.naturePoints = clamp(natureScore * natureMaxPts, -natureMaxPts, natureMaxPts);

        double pollution = metrics.pollution;
        double pollutionTarget = 0.02;
        if (metrics.pollutingBlocks < 4) {
            hb.pollutionPenalty = 0.0;
        } else {
            double pollutionSeverity = Math.max(0.0, (pollution - pollutionTarget) / pollutionTarget);
            hb.pollutionPenalty = clamp(pollutionSeverity * pollutionMaxPenalty, 0.0, pollutionMaxPenalty);
        }

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
        hb.housingPoints = clamp(housingScore * housingMaxPts, -housingMaxPts, housingMaxPts);

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

    public void setLightNeutral(double lightNeutral) {
        this.lightNeutral = Math.max(0.1, lightNeutral);
    }

    public double getLightNeutral() {
        return lightNeutral;
    }

    public void setLightMaxPts(double lightMaxPts) {
        this.lightMaxPts = lightMaxPts;
    }

    public void setEmploymentMaxPts(double employmentMaxPts) {
        this.employmentMaxPts = employmentMaxPts;
    }

    public void setOvercrowdMaxPenalty(double overcrowdMaxPenalty) {
        this.overcrowdMaxPenalty = overcrowdMaxPenalty;
    }

    public void setNatureMaxPts(double natureMaxPts) {
        this.natureMaxPts = natureMaxPts;
    }

    public void setPollutionMaxPenalty(double pollutionMaxPenalty) {
        this.pollutionMaxPenalty = pollutionMaxPenalty;
    }

    public void setHousingMaxPts(double housingMaxPts) {
        this.housingMaxPts = housingMaxPts;
    }

    public void setTransitMaxPts(double transitMaxPts) {
        this.transitMaxPts = Math.max(0.0, transitMaxPts);
    }

    public void setStationCountingMode(StationCountingMode stationCountingMode) {
        this.stationCountingMode = stationCountingMode;
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

    private double adjustedNatureRatio(double rawRatio, int sampleCount) {
        double clampedRatio = clamp(rawRatio, 0.0, 1.0);
        int samples = Math.max(0, sampleCount);
        double sampleWeight = Math.min(1.0, samples / (double) NATURE_MIN_EFFECTIVE_SAMPLES);
        double adjusted = NATURE_TARGET_RATIO + sampleWeight * (clampedRatio - NATURE_TARGET_RATIO);
        return clamp(adjusted, 0.0, 1.0);
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
