package dev.citysim.economy;

import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the rolling metrics for a single district tile.
 */
public final class DistrictStats {
    private final DistrictKey key;

    private volatile double landValue0to100;
    private volatile double natureRatio;
    private volatile double pollutionRatio;
    private volatile double lightAverage;
    private volatile double accessScore;
    private volatile double housingCapacity;
    private volatile double jobsCapacity;
    private volatile double crowdingPenalty;
    private volatile double stationsNearby;

    private final AtomicLong updatedAt = new AtomicLong();

    public DistrictStats(DistrictKey key) {
        this.key = key;
    }

    public DistrictKey key() {
        return key;
    }

    public double landValue0to100() {
        return landValue0to100;
    }

    public void setLandValue0to100(double landValue0to100) {
        this.landValue0to100 = landValue0to100;
    }

    public double natureRatio() {
        return natureRatio;
    }

    public void setNatureRatio(double natureRatio) {
        this.natureRatio = natureRatio;
    }

    public double pollutionRatio() {
        return pollutionRatio;
    }

    public void setPollutionRatio(double pollutionRatio) {
        this.pollutionRatio = pollutionRatio;
    }

    public double lightAverage() {
        return lightAverage;
    }

    public void setLightAverage(double lightAverage) {
        this.lightAverage = lightAverage;
    }

    public double accessScore() {
        return accessScore;
    }

    public void setAccessScore(double accessScore) {
        this.accessScore = accessScore;
    }

    public double housingCapacity() {
        return housingCapacity;
    }

    public void setHousingCapacity(double housingCapacity) {
        this.housingCapacity = housingCapacity;
    }

    public double jobsCapacity() {
        return jobsCapacity;
    }

    public void setJobsCapacity(double jobsCapacity) {
        this.jobsCapacity = jobsCapacity;
    }

    public double crowdingPenalty() {
        return crowdingPenalty;
    }

    public void setCrowdingPenalty(double crowdingPenalty) {
        this.crowdingPenalty = crowdingPenalty;
    }

    public double stationsNearby() {
        return stationsNearby;
    }

    public void setStationsNearby(double stationsNearby) {
        this.stationsNearby = stationsNearby;
    }

    public long updatedAt() {
        return updatedAt.get();
    }

    public void markUpdated(long timestamp) {
        updatedAt.set(timestamp);
    }

    public boolean needsRefresh(long now, long refreshIntervalMillis) {
        long last = updatedAt.get();
        if (last == 0L) {
            return true;
        }
        return now - last >= refreshIntervalMillis;
    }

    public boolean isWorldLoaded() {
        return Bukkit.getWorld(key.world()) != null;
    }
}
