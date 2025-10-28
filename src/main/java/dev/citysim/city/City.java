package dev.citysim.city;

import dev.citysim.stats.HappinessBreakdown;

import java.util.ArrayList;
import java.util.List;

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
    public int golems = 0;
    public int happiness = 50;
    public int stations = 0;

    public transient HappinessBreakdown happinessBreakdown = null;

    public transient BlockScanCache blockScanCache = null;

    public boolean highrise = false;

    public boolean contains(org.bukkit.Location loc) {
        for (Cuboid c : cuboids) if (c.contains(loc)) return true;
        return false;
    }

    public void invalidateBlockScanCache() {
        blockScanCache = null;
        happinessBreakdown = null;
    }

    public static class BlockScanCache {
        public double light;
        public double nature;
        public double pollution;
        public int pollutingBlocks;
        public double overcrowdingPenalty;
        public long timestamp;
    }
}
