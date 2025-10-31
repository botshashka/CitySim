package dev.citysim.city;

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
    public int employed = 0;
    public int unemployed = 0;
    public int beds = 0;
    public int happiness = 50;
    public int stations = 0;

    public transient HappinessBreakdown happinessBreakdown = null;

    public transient BlockScanCache blockScanCache = null;
    private transient Set<ChunkPosition> residentialChunks = new LinkedHashSet<>();

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
        residentialChunks.clear();
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

    public static class BlockScanCache {
        public double light;
        public double nature;
        public int natureSamples;
        public double pollution;
        public int pollutingBlocks;
        public double overcrowdingPenalty;
        public long timestamp;
    }

    public record ChunkPosition(String world, int x, int z) {
    }
}
