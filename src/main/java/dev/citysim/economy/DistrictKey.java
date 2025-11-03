package dev.citysim.economy;

import java.util.Objects;

/**
 * Immutable identifier for a district tile. Districts are chunk aligned tiles of
 * {@link EconomySettings#districtTileBlocks()} blocks.
 */
public final class DistrictKey {
    private final String world;
    private final int chunkX;
    private final int chunkZ;

    public DistrictKey(String world, int chunkX, int chunkZ) {
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public String world() {
        return world;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DistrictKey that)) return false;
        return chunkX == that.chunkX && chunkZ == that.chunkZ && Objects.equals(world, that.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, chunkX, chunkZ);
    }

    @Override
    public String toString() {
        return "DistrictKey{" +
                "world='" + world + '\'' +
                ", chunkX=" + chunkX +
                ", chunkZ=" + chunkZ +
                '}';
    }
}
