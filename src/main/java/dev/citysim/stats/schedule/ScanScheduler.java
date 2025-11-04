package dev.citysim.stats.schedule;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.scan.CityScanJob;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.CityScanRunner.CompletedJob;
import dev.citysim.stats.scan.RerunRequest;
import dev.citysim.stats.scan.ScanContext;
import dev.citysim.stats.scan.ScanRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class ScanScheduler {
    private final CityManager cityManager;
    private final CityScanRunner cityScanRunner;
    private final Map<String, ScanRequest> pendingCityUpdates = new LinkedHashMap<>();
    private final Map<String, ScheduledCity> scheduledEntries = new HashMap<>();
    private final PriorityQueue<ScheduledCity> sweepQueue = new PriorityQueue<>(Comparator
            .comparingLong(ScheduledCity::nextEligibleMillis)
            .thenComparing(ScheduledCity::cityId));

    private int maxCitiesPerTick = 1;
    private int maxEntityChunksPerTick = 2;
    private int maxBedBlocksPerTick = 2048;
    private long baseSweepIntervalMillis = TimeUnit.SECONDS.toMillis(5);

    public ScanScheduler(CityManager cityManager, CityScanRunner cityScanRunner) {
        this.cityManager = cityManager;
        this.cityScanRunner = cityScanRunner;
    }

    public void setLimits(int maxCitiesPerTick, int maxEntityChunksPerTick, int maxBedBlocksPerTick) {
        this.maxCitiesPerTick = Math.max(1, maxCitiesPerTick);
        this.maxEntityChunksPerTick = Math.max(1, maxEntityChunksPerTick);
        this.maxBedBlocksPerTick = Math.max(1, maxBedBlocksPerTick);
    }

    public void clear() {
        pendingCityUpdates.clear();
        scheduledEntries.clear();
        sweepQueue.clear();
        cityScanRunner.clearActiveJobs();
    }

    public void queueCity(String cityId, boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {
        if (cityId == null || cityId.isEmpty()) {
            return;
        }
        if (cityScanRunner.hasActiveJob(cityId)) {
            cityScanRunner.startJob(cityManager.get(cityId), new ScanRequest(forceRefresh, forceChunkLoad, reason, context));
            return;
        }
        ScanRequest request = new ScanRequest(forceRefresh, forceChunkLoad, reason, context);
        pendingCityUpdates.merge(cityId, request, ScanRequest::merge);
        ScheduledCity scheduled = scheduledEntries.remove(cityId);
        if (scheduled != null) {
            sweepQueue.remove(scheduled);
        }
    }

    public List<CompletedJob> progressActiveJobs() {
        List<CompletedJob> completed = cityScanRunner.progressJobs(maxCitiesPerTick, maxEntityChunksPerTick, maxBedBlocksPerTick);
        if (!completed.isEmpty()) {
            long now = System.currentTimeMillis();
            long delay = computeSweepDelayMillis();
            for (CompletedJob entry : completed) {
                CityScanJob job = entry.job();
                if (job != null && job.cityId() != null && !job.isCancelled()) {
                    scheduleCity(job.cityId(), now + delay);
                }
                RerunRequest rerun = entry.rerunRequest();
                if (rerun.requested() && job != null && job.cityId() != null) {
                    queueCity(job.cityId(), rerun.forceRefresh(), rerun.forceChunkLoad(), rerun.reason(), rerun.context());
                }
            }
        }
        return completed;
    }

    public int startJobs(boolean includeScheduled) {
        ensureSweepEntries();
        int started = 0;
        long now = System.currentTimeMillis();
        int target = Math.max(1, maxCitiesPerTick);
        while (started < target) {
            if (processNextPendingCity()) {
                started++;
                continue;
            }
            if (!includeScheduled) {
                break;
            }
            boolean allowEarly = cityScanRunner.activeJobsView().isEmpty() && started == 0;
            if (!processNextScheduledCity(now, allowEarly)) {
                break;
            }
            started++;
            now = System.currentTimeMillis();
        }
        return started;
    }

    public List<CompletedJob> tick() {
        List<CompletedJob> completed = progressActiveJobs();
        startJobs(true);
        return completed;
    }

    public void cancel(String cityId) {
        if (cityId == null) {
            return;
        }
        pendingCityUpdates.remove(cityId);
        ScheduledCity scheduled = scheduledEntries.remove(cityId);
        if (scheduled != null) {
            sweepQueue.remove(scheduled);
        }
        cityScanRunner.cancelJob(cityId);
    }

    public int pendingCount() {
        return pendingCityUpdates.size();
    }

    public int scheduledCount() {
        return sweepQueue.size();
    }

    public int activeCount() {
        return cityScanRunner.activeJobsView().size();
    }

    private boolean processNextPendingCity() {
        if (pendingCityUpdates.isEmpty()) {
            return false;
        }
        var iterator = pendingCityUpdates.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            City city = cityManager.get(entry.getKey());
            if (city == null) {
                continue;
            }
            ScanRequest request = entry.getValue();
            if (startCityScanJob(city, request)) {
                return true;
            }
        }
        return false;
    }

    private boolean processNextScheduledCity(long nowMillis, boolean allowEarly) {
        while (!sweepQueue.isEmpty()) {
            ScheduledCity entry = sweepQueue.peek();
            if (entry == null) {
                break;
            }
            if (pendingCityUpdates.containsKey(entry.cityId())
                    || cityScanRunner.hasActiveJob(entry.cityId())
                    || cityManager.get(entry.cityId()) == null) {
                sweepQueue.poll();
                scheduledEntries.remove(entry.cityId());
                continue;
            }
            if (!allowEarly && entry.nextEligibleMillis() > nowMillis) {
                return false;
            }
            sweepQueue.poll();
            scheduledEntries.remove(entry.cityId());
            City city = cityManager.get(entry.cityId());
            if (city == null) {
                continue;
            }
            if (startCityScanJob(city, new ScanRequest(false, "scheduled sweep", null))) {
                return true;
            }
        }
        return false;
    }

    private boolean startCityScanJob(City city, ScanRequest request) {
        boolean alreadyActive = city != null && city.id != null && cityScanRunner.hasActiveJob(city.id);
        CityScanJob job = cityScanRunner.startJob(city, request);
        return job != null && !alreadyActive;
    }

    private void ensureSweepEntries() {
        long now = System.currentTimeMillis();
        long delay = computeSweepDelayMillis();
        for (City city : cityManager.all()) {
            if (city == null || city.id == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(city.id) || cityScanRunner.hasActiveJob(city.id)) {
                continue;
            }
            if (scheduledEntries.containsKey(city.id)) {
                continue;
            }
            long lastStats = Math.max(0L, city.statsTimestamp);
            long nextEligible = Math.min(lastStats, now);
            if (nextEligible <= 0L || nextEligible < now - delay) {
                nextEligible = now - delay;
            }
            scheduleCity(city.id, nextEligible);
        }
    }

    private void scheduleCity(String cityId, long nextEligibleMillis) {
        if (cityId == null) {
            return;
        }
        ScheduledCity existing = scheduledEntries.get(cityId);
        if (existing != null) {
            if (existing.nextEligibleMillis() <= nextEligibleMillis) {
                return;
            }
            sweepQueue.remove(existing);
        }
        ScheduledCity entry = new ScheduledCity(cityId, nextEligibleMillis);
        scheduledEntries.put(cityId, entry);
        sweepQueue.add(entry);
    }

    private long computeSweepDelayMillis() {
        long base = Math.max(TimeUnit.SECONDS.toMillis(1), baseSweepIntervalMillis);
        int cityCount = Math.max(1, cityManager.all().size());
        int concurrency = Math.max(1, maxCitiesPerTick);
        double cycles = Math.ceil(cityCount / (double) concurrency);
        long adjusted = (long) Math.max(base, base * cycles);
        return Math.max(base, adjusted);
    }

    public void setBaseSweepIntervalMillis(long millis) {
        if (millis <= 0L) {
            baseSweepIntervalMillis = TimeUnit.SECONDS.toMillis(5);
        } else {
            baseSweepIntervalMillis = millis;
        }
    }

    private static final class ScheduledCity {
        private final String cityId;
        private final long nextEligibleMillis;

        ScheduledCity(String cityId, long nextEligibleMillis) {
            this.cityId = cityId;
            this.nextEligibleMillis = nextEligibleMillis;
        }

        String cityId() {
            return cityId;
        }

        long nextEligibleMillis() {
            return nextEligibleMillis;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScheduledCity that)) return false;
            return Objects.equals(cityId, that.cityId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(cityId);
        }
    }
}
