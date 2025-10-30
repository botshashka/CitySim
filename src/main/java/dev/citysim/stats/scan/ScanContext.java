package dev.citysim.stats.scan;

public record ScanContext(String world, Integer x, Integer y, Integer z) {
    public String describe() {
        if (world == null) {
            return "unknown";
        }
        if (x != null && y != null && z != null) {
            return world + " (" + x + ", " + y + ", " + z + ")";
        }
        return world;
    }
}
