package dev.citysim.city;

import org.bukkit.Location;
import org.bukkit.World;

public class Cuboid {
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public boolean fullHeight;

    public Cuboid() {}
    public Cuboid(World w, Location a, Location b, boolean fullHeight) {
        this.world = w.getName();
        this.minX = Math.min(a.getBlockX(), b.getBlockX());
        this.maxX = Math.max(a.getBlockX(), b.getBlockX());
        this.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        this.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        if (fullHeight) {
            this.minY = w.getMinHeight();
            this.maxY = w.getMaxHeight()-1;
        } else {
            this.minY = Math.min(a.getBlockY(), b.getBlockY());
            this.maxY = Math.max(a.getBlockY(), b.getBlockY());
        }
        this.fullHeight = fullHeight;
    }

    public boolean isFullHeight(World world) {
        if (fullHeight) return true;
        if (world != null) {
            int worldMin = world.getMinHeight();
            int worldMax = world.getMaxHeight() - 1;
            if (minY <= worldMin && maxY >= worldMax) return true;
        }
        return (maxY - minY) >= 255;
    }

    public boolean contains(Location loc) {
        if (!loc.getWorld().getName().equals(world)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
