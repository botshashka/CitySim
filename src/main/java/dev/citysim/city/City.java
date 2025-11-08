package dev.citysim.city;

import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.stats.HappinessBreakdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class City {
    public String id;
    public String name;
    public String world;
    public int priority = 0;
    public List<Cuboid> cuboids = new ArrayList<>();

    public int population = 0;
    public int adultPopulation = 0;
    public int employed = 0;
    public int unemployed = 0;
    public int adultNone = 0;
    public int adultNitwit = 0;
    public int beds = 0;
    public int happiness = 50;
    public int stations = 0;

    public int level = 0;
    public double levelProgress = 0.0;
    public java.util.List<String> mayors = new java.util.ArrayList<>();

    public double employmentRate = 0.0;
    public double housingRatio = 1.0;
    public double transitCoverage = 0.0;
    public long statsTimestamp = 0L;

    public double gdp = 0.0;
    public double gdpPerCapita = 0.0;
    public double sectorAgri = 0.0;
    public double sectorInd = 0.0;
    public double sectorServ = 0.0;
    public double jobsPressure = 0.0;
    public double housingPressure = 0.0;
    public double transitPressure = 0.0;
    public double landValue = 0.0;

    public int migrationZeroPopArrivals = 0;

    public transient HappinessBreakdown happinessBreakdown = null;
    public transient EconomyBreakdown economyBreakdown = null;

    public transient BlockScanCache blockScanCache = null;
    public transient EntityScanCache entityScanCache = null;
    private transient Set<ChunkPosition> residentialChunks = new LinkedHashSet<>();
    private transient java.util.Map<ChunkPosition, BedSnapshot> bedSnapshots = new java.util.HashMap<>();

    public boolean highrise = false;

    public boolean contains(org.bukkit.Location loc) {
        if (loc == null) {
            return false;
        }

        org.bukkit.World locationWorld = loc.getWorld();
        if (locationWorld == null) {
            return false;
        }

        String worldName = locationWorld.getName();
        for (Cuboid c : cuboids) {
            if (c == null || c.world == null || !worldName.equals(c.world)) {
                continue;
            }
            if (c.contains(loc)) {
                return true;
            }
        }
        return false;
    }

    public void invalidateBlockScanCache() {
        blockScanCache = null;
        happinessBreakdown = null;
        economyBreakdown = null;
        residentialChunks.clear();
        invalidateBedSnapshots();
    }

    public void setResidentialChunks(Collection<ChunkPosition> chunkPositions) {
        LinkedHashSet<ChunkPosition> updated = new LinkedHashSet<>();
        if (chunkPositions != null) {
            updated.addAll(chunkPositions);
        }
        residentialChunks = updated;
    }

    public Set<ChunkPosition> getResidentialChunks() {
        return Collections.unmodifiableSet(residentialChunks);
    }

    public java.util.Map<ChunkPosition, BedSnapshot> bedSnapshotMap() {
        if (bedSnapshots == null) {
            bedSnapshots = new java.util.HashMap<>();
        }
        return bedSnapshots;
    }

    public BedSnapshot getBedSnapshot(ChunkPosition chunk) {
        if (chunk == null) {
            return null;
        }
        return bedSnapshotMap().get(chunk);
    }

    public void putBedSnapshot(ChunkPosition chunk, int bedHalves, long timestamp) {
        if (chunk == null) {
            return;
        }
        BedSnapshot snapshot = new BedSnapshot();
        snapshot.bedHalves = Math.max(0, bedHalves);
        snapshot.timestamp = timestamp;
        snapshot.dirty = false;
        bedSnapshotMap().put(chunk, snapshot);
    }

    public void invalidateBedSnapshots() {
        if (bedSnapshots != null) {
            bedSnapshots.clear();
        }
    }

    public static class BlockScanCache {
        public double light;
        public double nature;
        public int natureSamples;
        public double pollution;
        public int pollutingBlocks;
        public int pollutionSamples;
        public double overcrowdingPenalty;
        public long timestamp;
    }

    public static class EntityScanCache {
        public long timestamp;
    }

    public record ChunkPosition(String world, int x, int z) {
    }

    public static class BedSnapshot {
        public int bedHalves;
        public long timestamp;
        public boolean dirty;
    }

    public boolean isGhostTown() {
        return population <= 0;
    }
}
