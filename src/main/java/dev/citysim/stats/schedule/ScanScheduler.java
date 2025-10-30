package dev.citysim.stats.schedule;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.scan.CityScanJob;
import dev.citysim.stats.scan.CityScanRunner;
import dev.citysim.stats.scan.CityScanRunner.CompletedJob;
import dev.citysim.stats.scan.RerunRequest;
import dev.citysim.stats.scan.ScanContext;
import dev.citysim.stats.scan.ScanRequest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScanScheduler {
    private final CityManager cityManager;
    private final CityScanRunner cityScanRunner;
    private final Map<String, ScanRequest> pendingCityUpdates = new LinkedHashMap<>();
    private final Deque<String> scheduledCityQueue = new ArrayDeque<>();

    private int maxCitiesPerTick = 1;
    private int maxEntityChunksPerTick = 2;
    private int maxBedBlocksPerTick = 2048;

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
        scheduledCityQueue.clear();
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
        scheduledCityQueue.remove(cityId);
    }

    public void tick() {
        List<CompletedJob> completed = cityScanRunner.progressJobs(maxCitiesPerTick, maxEntityChunksPerTick, maxBedBlocksPerTick);
        for (CompletedJob entry : completed) {
            RerunRequest rerun = entry.rerunRequest();
            if (rerun.requested()) {
                queueCity(entry.job().cityId(), rerun.forceRefresh(), rerun.forceChunkLoad(), rerun.reason(), rerun.context());
            }
        }

        int processed = 0;
        int target = Math.max(1, maxCitiesPerTick);
        while (processed < target) {
            boolean started = processNextPendingCity();
            if (!started) {
                started = processNextScheduledCity();
            }
            if (!started) {
                break;
            }
            processed++;
        }
    }

    public void cancel(String cityId) {
        if (cityId == null) {
            return;
        }
        pendingCityUpdates.remove(cityId);
        scheduledCityQueue.remove(cityId);
        cityScanRunner.cancelJob(cityId);
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

    private boolean processNextScheduledCity() {
        int attemptsRemaining = 0;
        while (true) {
            if (scheduledCityQueue.isEmpty()) {
                refillScheduledQueue();
                if (scheduledCityQueue.isEmpty()) {
                    return false;
                }
                attemptsRemaining = scheduledCityQueue.size();
            }
            if (attemptsRemaining <= 0) {
                attemptsRemaining = scheduledCityQueue.size();
                if (attemptsRemaining <= 0) {
                    return false;
                }
            }
            attemptsRemaining--;
            String cityId = scheduledCityQueue.pollFirst();
            if (cityId == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(cityId) || cityScanRunner.hasActiveJob(cityId)) {
                continue;
            }
            City city = cityManager.get(cityId);
            if (city == null) {
                continue;
            }
            if (startCityScanJob(city, new ScanRequest(false, "scheduled sweep", null))) {
                return true;
            }
        }
    }

    private boolean startCityScanJob(City city, ScanRequest request) {
        CityScanJob job = cityScanRunner.startJob(city, request);
        return job != null;
    }

    private void refillScheduledQueue() {
        scheduledCityQueue.clear();
        List<City> cities = new ArrayList<>(cityManager.all());
        for (City city : cities) {
            if (city == null || city.id == null) {
                continue;
            }
            if (pendingCityUpdates.containsKey(city.id) || cityScanRunner.hasActiveJob(city.id)) {
                continue;
            }
            scheduledCityQueue.addLast(city.id);
        }
    }
}
