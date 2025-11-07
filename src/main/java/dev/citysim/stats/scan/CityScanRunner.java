package dev.citysim.stats.scan;

import dev.citysim.city.City;
import dev.citysim.stats.HappinessBreakdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CityScanRunner {
    private final Map<String, CityScanJob> activeCityJobs = new LinkedHashMap<>();
    private final CityScanCallbacks callbacks;
    private final ScanDebugManager debugManager;
    private long lastDebugSummaryMillis = 0L;

    public CityScanRunner(CityScanCallbacks callbacks, ScanDebugManager debugManager) {
        this.callbacks = callbacks;
        this.debugManager = debugManager;
    }

    public boolean hasActiveJob(String cityId) {
        return cityId != null && activeCityJobs.containsKey(cityId);
    }

    public CityScanJob startJob(City city, ScanRequest request) {
        if (city == null || city.id == null || city.id.isEmpty()) {
            return null;
        }
        ScanRequest effective = request != null ? request : new ScanRequest(false, false, null, null);
        CityScanJob existing = activeCityJobs.get(city.id);
        if (existing != null) {
            existing.requestRequeue(effective.forceRefresh(), effective.forceChunkLoad(), effective.reason(), effective.context());
            return existing;
        }
        CityScanJob job = new CityScanJob(city, effective, callbacks, debugManager, false);
        activeCityJobs.put(city.id, job);
        return job;
    }

    public void cancelJob(String cityId) {
        if (cityId == null) {
            return;
        }
        CityScanJob running = activeCityJobs.remove(cityId);
        if (running != null) {
            running.cancel();
        }
    }

    public void clearActiveJobs() {
        if (activeCityJobs.isEmpty()) {
            return;
        }
        for (CityScanJob job : activeCityJobs.values()) {
            job.cancel();
        }
        activeCityJobs.clear();
    }

    public List<CompletedJob> progressJobs(int jobsToProcess, int maxEntityChunks, int maxBedBlocks) {
        if (activeCityJobs.isEmpty()) {
            return List.of();
        }
        int baseSlices = Math.max(1, jobsToProcess);
        int chunkSliceLimit = maxEntityChunks <= 0 ? Integer.MAX_VALUE : Math.max(1, maxEntityChunks);
        int bedSliceLimit = maxBedBlocks <= 0 ? Integer.MAX_VALUE : Math.max(1, maxBedBlocks);
        int chunkBudget = chunkSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : multiplyBudget(chunkSliceLimit, baseSlices);
        int bedBudget = bedSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : multiplyBudget(bedSliceLimit, baseSlices);

        Map<String, CityScanJob> jobPool = new LinkedHashMap<>(activeCityJobs);
        List<String> order = new ArrayList<>(jobPool.keySet());
        activeCityJobs.clear();

        List<CompletedJob> completed = new ArrayList<>();
        List<String> remaining = new ArrayList<>();

        for (String jobId : order) {
            CityScanJob job = jobPool.get(jobId);
            if (job == null || job.isCancelled()) {
                continue;
            }
            if (chunkBudget <= 0 && bedBudget <= 0) {
                remaining.add(jobId);
                continue;
            }
            int chunkLimit = chunkSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(chunkSliceLimit, chunkBudget);
            int bedLimit = bedSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(bedSliceLimit, bedBudget);
            if (chunkLimit <= 0 && bedLimit <= 0) {
                remaining.add(jobId);
                continue;
            }
            boolean done = job.process(chunkLimit, bedLimit);
            if (chunkSliceLimit != Integer.MAX_VALUE) {
                chunkBudget = Math.max(0, chunkBudget - chunkLimit);
            }
            if (bedSliceLimit != Integer.MAX_VALUE) {
                bedBudget = Math.max(0, bedBudget - bedLimit);
            }
            if (done) {
                completed.add(new CompletedJob(job, job.consumeRerunRequest(), job.workload()));
                jobPool.remove(jobId);
            } else {
                remaining.add(jobId);
            }
        }

        if (!remaining.isEmpty() && (chunkBudget > 0 || chunkSliceLimit == Integer.MAX_VALUE || bedBudget > 0 || bedSliceLimit == Integer.MAX_VALUE)) {
            List<String> nextRound = new ArrayList<>();
            for (String jobId : remaining) {
                CityScanJob job = jobPool.get(jobId);
                if (job == null || job.isCancelled()) {
                    continue;
                }
                int chunkLimit = chunkSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(chunkSliceLimit, chunkBudget);
                int bedLimit = bedSliceLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.min(bedSliceLimit, bedBudget);
                if (chunkLimit <= 0 && bedLimit <= 0) {
                    nextRound.add(jobId);
                    continue;
                }
                boolean done = job.process(chunkLimit, bedLimit);
                if (chunkSliceLimit != Integer.MAX_VALUE) {
                    chunkBudget = Math.max(0, chunkBudget - chunkLimit);
                }
                if (bedSliceLimit != Integer.MAX_VALUE) {
                    bedBudget = Math.max(0, bedBudget - bedLimit);
                }
                if (done) {
                    completed.add(new CompletedJob(job, job.consumeRerunRequest(), job.workload()));
                    jobPool.remove(jobId);
                } else {
                    nextRound.add(jobId);
                }
                if ((chunkBudget <= 0 && chunkSliceLimit != Integer.MAX_VALUE) && (bedBudget <= 0 && bedSliceLimit != Integer.MAX_VALUE)) {
                    int currentIndex = order.indexOf(jobId);
                    if (currentIndex >= 0) {
                        for (int i = currentIndex + 1; i < order.size(); i++) {
                            String pendingId = order.get(i);
                            CityScanJob pendingJob = jobPool.get(pendingId);
                            if (pendingJob != null && !pendingJob.isCancelled() && !nextRound.contains(pendingId)) {
                                nextRound.add(pendingId);
                            }
                        }
                    }
                    break;
                }
            }
            remaining = nextRound;
        }
        for (String jobId : remaining) {
            CityScanJob job = jobPool.get(jobId);
            if (job != null && !job.isCancelled()) {
                activeCityJobs.put(jobId, job);
            }
        }

        for (Map.Entry<String, CityScanJob> entry : jobPool.entrySet()) {
            if (activeCityJobs.containsKey(entry.getKey())) {
                continue;
            }
            if (entry.getValue() != null && !entry.getValue().isCancelled()) {
                activeCityJobs.put(entry.getKey(), entry.getValue());
            }
        }
        if (debugManager.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastDebugSummaryMillis >= 1000L) {
                java.util.List<ScanDebugManager.JobDebugSummary> summaries = new java.util.ArrayList<>();
                for (CityScanJob job : activeCityJobs.values()) {
                    if (job == null || job.isCancelled()) {
                        continue;
                    }
                    CityScanJob.ScanProgress progress = job.progressSnapshot();
                    summaries.add(new ScanDebugManager.JobDebugSummary(
                            job.city(),
                            progress,
                            job.totalBedWorkUnits(),
                            job.completedBedWorkUnits(),
                            job.cachedBedChunks(),
                            job.deferredBedChunks()
                    ));
                }
                debugManager.logTickSummary(order.size(), completed.size(), summaries);
                lastDebugSummaryMillis = now;
            }
        }
        return completed;
    }

    private static int multiplyBudget(int value, int multiplier) {
        long result = (long) value * (long) multiplier;
        if (result >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(1L, result);
    }

    public HappinessBreakdown runSynchronously(City city, ScanRequest request) {
        CityScanJob job = new CityScanJob(city, request, callbacks, debugManager, true);
        while (!job.process(Integer.MAX_VALUE, Integer.MAX_VALUE)) {
            // Keep processing until the scan completes synchronously
        }
        HappinessBreakdown result = job.getResult();
        return result != null ? result : city.happinessBreakdown;
    }

    public Map<String, CityScanJob> activeJobsView() {
        return Map.copyOf(activeCityJobs);
    }

    public record CompletedJob(CityScanJob job, RerunRequest rerunRequest, CityScanJob.ScanWorkload workload) {
    }
}
