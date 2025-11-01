package dev.citysim.visual;

public record SelectionBounds(String worldName,
                              int minX,
                              int minY,
                              int minZ,
                              int maxX,
                              int maxY,
                              int maxZ,
                              YMode mode,
                              long hash) {
}
