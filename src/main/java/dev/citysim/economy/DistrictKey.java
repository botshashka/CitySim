package dev.citysim.economy;

import java.util.Objects;

/**
 * Identifies a chunk-aligned district tile. Chunk coordinates refer to the
 * north-west corner chunk of the tile. Tiles are square and span a
 * configurable number of chunks.
 */
public record DistrictKey(String world, int chunkX, int chunkZ) {

    public DistrictKey {
        Objects.requireNonNull(world, "world");
    }

    public String asString() {
        return world + ":" + chunkX + ":" + chunkZ;
    }
}
