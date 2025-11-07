package dev.citysim.stats.schedule;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.scan.CityScanJob;
import dev.citysim.stats.scan.CityScanJob.ScanWorkload;
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
    private final Map<String, CityScanStats> cityStats = new HashMap<>();

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
            for (CompletedJob entry : completed) {
                CityScanJob job = entry.job();
                if (job != null && job.cityId() != null && !job.isCancelled()) {
                    registerCompletedJob(job.cityId(), entry.workload(), now);
                    scheduleCity(job.cityId(), nextEligibleMillis(job.cityId(), now));
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

    private void registerCompletedJob(String cityId, ScanWorkload workload, long completedAtMillis) {
        if (cityId == null) {
            return;
        }
        CityScanStats stats = cityStats.computeIfAbsent(cityId, id -> new CityScanStats());
        stats.recordCompletion(workload, completedAtMillis);
    }

    private long nextEligibleMillis(String cityId, long nowMillis) {
        CityScanStats stats = cityStats.get(cityId);
        long base = Math.max(1L, baseSweepIntervalMillis);
        if (stats == null || stats.lastCompletionMillis <= 0L) {
            return nowMillis;
        }
        long cooldown = stats.targetCooldownMillis(base, maxEntityChunksPerTick, maxBedBlocksPerTick);
        long eligibleAt = safeAdd(stats.lastCompletionMillis, cooldown);
        return Math.max(eligibleAt, nowMillis);
    }

    private void ensureSweepEntries() {
        long now = System.currentTimeMillis();
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
            scheduleCity(city.id, nextEligibleMillis(city.id, now));
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

    public void setBaseSweepIntervalMillis(long millis) {
        if (millis <= 0L) {
            baseSweepIntervalMillis = TimeUnit.SECONDS.toMillis(5);
        } else {
            baseSweepIntervalMillis = millis;
        }
    }

    private static final class CityScanStats {
        private long lastCompletionMillis;
        private long totalDurationMillis;
        private long totalEntityChunks;
        private long totalBedBlocks;
        private int scans;
        private long lastDurationMillis;
        private long lastEntityChunks;
        private long lastBedBlocks;

        void recordCompletion(ScanWorkload workload, long completedAtMillis) {
            long duration = 1L;
            long entityChunks = 0L;
            long bedBlocks = 0L;
            if (workload != null) {
                duration = Math.max(1L, workload.durationMillis());
                entityChunks = Math.max(0L, workload.entityChunks());
                bedBlocks = Math.max(0L, workload.bedBlockChecks());
            }
            lastCompletionMillis = completedAtMillis;
            lastDurationMillis = duration;
            totalDurationMillis = safeAdd(totalDurationMillis, duration);
            totalEntityChunks = safeAdd(totalEntityChunks, entityChunks);
            totalBedBlocks = safeAdd(totalBedBlocks, bedBlocks);
            lastEntityChunks = entityChunks;
            lastBedBlocks = bedBlocks;
            scans++;
        }

        long averageDurationMillis() {
            return scans == 0 ? 0L : totalDurationMillis / scans;
        }

        long averageEntityChunks() {
            return scans == 0 ? 0L : totalEntityChunks / scans;
        }

        long averageBedBlocks() {
            return scans == 0 ? 0L : totalBedBlocks / scans;
        }

        long targetCooldownMillis(long baseIntervalMillis, int chunkBudget, int bedBudget) {
            long durationSample = Math.max(averageDurationMillis(), lastDurationMillis);
            long workloadMillis = estimatedWorkMillis(chunkBudget, bedBudget);
            long reference = Math.max(durationSample, workloadMillis);
            if (reference <= 0L) {
                reference = baseIntervalMillis;
            }
            long cooldown = Math.max(baseIntervalMillis, reference);
            return cooldown;
        }

        private long estimatedWorkMillis(int chunkBudget, int bedBudget) {
            long chunkSample = Math.max(averageEntityChunks(), lastEntityChunks);
            long bedSample = Math.max(averageBedBlocks(), lastBedBlocks);
            long ticks = 0L;
            if (chunkBudget > 0 && chunkSample > 0L) {
                ticks = safeAdd(ticks, ceilDiv(chunkSample, chunkBudget));
            }
            if (bedBudget > 0 && bedSample > 0L) {
                ticks = safeAdd(ticks, ceilDiv(bedSample, bedBudget));
            }
            if (ticks <= 0L) {
                return 0L;
            }
            return safeMultiply(ticks, 50L);
        }
    }

    private static long safeAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static long safeMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0L || divisor <= 0L) {
            return 0L;
        }
        long quotient = value / divisor;
        long remainder = value % divisor;
        if (remainder == 0L) {
            return quotient;
        }
        if (quotient >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return quotient + 1L;
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
