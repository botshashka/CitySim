package dev.citysim.economy;

import java.time.Instant;

/**
 * Mutable statistics representing a single district tile. Values are smoothed using
 * simple exponential moving averages to avoid flicker when players inspect the overlay.
 */
public final class DistrictStats {
    private double landValue;
    private double nature;
    private double pollution;
    private double light;
    private double access;
    private double housingCapacity;
    private double jobsCapacity;
    private double crowdingPenalty;
    private int stationsNearby;
    private long updatedAt;

    public DistrictStats() {
        this.updatedAt = Instant.now().toEpochMilli();
    }

    public void applySample(TileSample sample, double alpha) {
        if (sample == null) {
            return;
        }
        this.landValue = blend(this.landValue, sample.landValue(), alpha);
        this.nature = blend(this.nature, sample.nature(), alpha);
        this.pollution = blend(this.pollution, sample.pollution(), alpha);
        this.light = blend(this.light, sample.light(), alpha);
        this.access = blend(this.access, sample.access(), alpha);
        this.housingCapacity = blend(this.housingCapacity, sample.housingCapacity(), alpha);
        this.jobsCapacity = blend(this.jobsCapacity, sample.jobsCapacity(), alpha);
        this.crowdingPenalty = blend(this.crowdingPenalty, sample.crowdingPenalty(), alpha);
        this.stationsNearby = sample.stationsNearby();
        this.updatedAt = System.currentTimeMillis();
    }

    private double blend(double current, double sample, double alpha) {
        if (Double.isNaN(current) || current == 0.0D) {
            return sample;
        }
        return (1.0D - alpha) * current + alpha * sample;
    }

    public int landValue0To100() {
        return (int) Math.round(Math.max(0.0D, Math.min(100.0D, landValue)));
    }

    public double landValueRaw() {
        return landValue;
    }

    public double nature() {
        return clamp01(nature);
    }

    public double pollution() {
        return clamp01(pollution);
    }

    public double light() {
        return light;
    }

    public double access() {
        return clamp01(access);
    }

    public double housingCapacity() {
        return Math.max(0.0D, housingCapacity);
    }

    public double jobsCapacity() {
        return Math.max(0.0D, jobsCapacity);
    }

    public double crowdingPenalty() {
        return Math.max(0.0D, crowdingPenalty);
    }

    public int stationsNearby() {
        return stationsNearby;
    }

    public long updatedAt() {
        return updatedAt;
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /**
     * Aggregated snapshot used for smoothing updates.
     */
    public record TileSample(double landValue,
                             double nature,
                             double pollution,
                             double light,
                             double access,
                             double housingCapacity,
                             double jobsCapacity,
                             double crowdingPenalty,
                             int stationsNearby) {
    }
}
