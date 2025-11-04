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
        CityScanJob job = new CityScanJob(city, effective, callbacks, debugManager);
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
        int limit = Math.max(1, jobsToProcess);
        var iterator = activeCityJobs.entrySet().iterator();
        List<CityScanJob> toRequeue = new ArrayList<>();
        List<CompletedJob> completed = new ArrayList<>();
        while (iterator.hasNext() && limit > 0) {
            Map.Entry<String, CityScanJob> entry = iterator.next();
            iterator.remove();
            CityScanJob job = entry.getValue();
            if (job.isCancelled()) {
                limit--;
                continue;
            }
            boolean done = job.process(maxEntityChunks, maxBedBlocks);
            if (done) {
                completed.add(new CompletedJob(job, job.consumeRerunRequest(), job.workload()));
            } else {
                toRequeue.add(job);
            }
            limit--;
        }
        for (CityScanJob job : toRequeue) {
            activeCityJobs.put(job.cityId(), job);
        }
        return completed;
    }

    public HappinessBreakdown runSynchronously(City city, ScanRequest request) {
        CityScanJob job = new CityScanJob(city, request, callbacks, debugManager);
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
