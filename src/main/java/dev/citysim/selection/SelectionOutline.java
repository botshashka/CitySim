package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SelectionOutline {
    public static final String MAX_OUTLINE_PARTICLES_PATH = "selection.max_outline_particles";
    public static final String SIMPLE_OUTLINE_MIDPOINTS_PATH = "selection.simple_outline_midpoints";
    public static final int DEFAULT_MAX_OUTLINE_PARTICLES = 1500;
    public static final boolean DEFAULT_SIMPLE_OUTLINE_MIDPOINTS = true;

    private SelectionOutline() {
    }

    public static int resolveMaxOutlineParticles(Plugin plugin) {
        if (plugin == null) {
            return DEFAULT_MAX_OUTLINE_PARTICLES;
        }
        FileConfiguration config = plugin.getConfig();
        int configured = config.getInt(MAX_OUTLINE_PARTICLES_PATH, DEFAULT_MAX_OUTLINE_PARTICLES);
        if (configured < 0) {
            return 0;
        }
        return configured;
    }

    public static boolean resolveSimpleMidpoints(Plugin plugin) {
        if (plugin == null) {
            return DEFAULT_SIMPLE_OUTLINE_MIDPOINTS;
        }
        return plugin.getConfig().getBoolean(SIMPLE_OUTLINE_MIDPOINTS_PATH, DEFAULT_SIMPLE_OUTLINE_MIDPOINTS);
    }

    public static List<Location> planOutline(World world,
                                             int minX,
                                             int minY,
                                             int minZ,
                                             int maxX,
                                             int maxY,
                                             int maxZ,
                                             int maxParticles,
                                             boolean includeMidpoints,
                                             boolean fullHeight,
                                             int viewerY) {
        if (world == null) {
            return Collections.emptyList();
        }
        if (fullHeight) {
            int minWorldY = world.getMinHeight();
            int maxWorldY = world.getMaxHeight() - 1;
            int targetY = clamp(viewerY, minWorldY, maxWorldY);
            return generateHorizontalSlice(world, minX, minZ, maxX, maxZ, targetY, maxParticles);
        }
        if (maxParticles <= 0) {
            return generateSimplifiedOutline(world, minX, minY, minZ, maxX, maxY, maxZ, includeMidpoints);
        }
        long estimate = estimateFullOutlineParticles(minX, minY, minZ, maxX, maxY, maxZ);
        if (estimate > maxParticles) {
            return generateSimplifiedOutline(world, minX, minY, minZ, maxX, maxY, maxZ, includeMidpoints);
        }
        return generateFullOutline(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static List<Location> generateHorizontalSlice(World world,
                                                          int minX,
                                                          int minZ,
                                                          int maxX,
                                                          int maxZ,
                                                          int y,
                                                          int maxParticles) {
        List<Location> points = new ArrayList<>();
        if (minX > maxX || minZ > maxZ) {
            return points;
        }

        for (int x = minX; x <= maxX; x++) {
            addPoint(points, center(world, x, y, minZ));
            if (minZ != maxZ) {
                addPoint(points, center(world, x, y, maxZ));
            }
        }

        if (maxZ > minZ + 1 || minX == maxX) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                addPoint(points, center(world, minX, y, z));
                if (minX != maxX) {
                    addPoint(points, center(world, maxX, y, z));
                }
            }
        }

        if (maxParticles > 0 && points.size() > maxParticles) {
            int stride = Math.max(1, (int) Math.ceil(points.size() / (double) maxParticles));
            List<Location> limited = new ArrayList<>(Math.min(points.size(), maxParticles));
            for (int i = 0; i < points.size(); i += stride) {
                limited.add(points.get(i));
            }
            Location last = points.get(points.size() - 1);
            if (!limited.contains(last)) {
                limited.add(last);
            }
            ensureCorner(limited, center(world, minX, y, minZ));
            ensureCorner(limited, center(world, minX, y, maxZ));
            ensureCorner(limited, center(world, maxX, y, minZ));
            ensureCorner(limited, center(world, maxX, y, maxZ));
            return limited;
        }

        return points;
    }

    private static void ensureCorner(List<Location> locations, Location corner) {
        if (corner == null || locations.contains(corner)) {
            return;
        }
        locations.add(corner);
    }

    public static long estimateFullOutlineParticles(int minX,
                                                     int minY,
                                                     int minZ,
                                                     int maxX,
                                                     int maxY,
                                                     int maxZ) {
        int dx = Math.max(0, maxX - minX);
        int dy = Math.max(0, maxY - minY);
        int dz = Math.max(0, maxZ - minZ);

        int xCorners = dx > 0 ? 2 : 1;
        int yCorners = dy > 0 ? 2 : 1;
        int zCorners = dz > 0 ? 2 : 1;

        long total = (long) xCorners * yCorners * zCorners;
        if (dx > 1) {
            total += (long) (dx - 1) * yCorners * zCorners;
        }
        if (dz > 1) {
            total += (long) (dz - 1) * xCorners * yCorners;
        }
        if (dy > 1) {
            total += (long) (dy - 1) * xCorners * zCorners;
        }
        return total;
    }

    private static List<Location> generateFullOutline(World world,
                                                      int minX,
                                                      int minY,
                                                      int minZ,
                                                      int maxX,
                                                      int maxY,
                                                      int maxZ) {
        List<Location> points = new ArrayList<>();
        int[] xCorners = axisValues(minX, maxX);
        int[] yCorners = axisValues(minY, maxY);
        int[] zCorners = axisValues(minZ, maxZ);

        for (int x : xCorners) {
            for (int y : yCorners) {
                for (int z : zCorners) {
                    points.add(center(world, x, y, z));
                }
            }
        }

        if (maxX > minX + 1) {
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int y : yCorners) {
                    for (int z : zCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        if (maxZ > minZ + 1) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                for (int x : xCorners) {
                    for (int y : yCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        if (maxY > minY + 1) {
            for (int y = minY + 1; y <= maxY - 1; y++) {
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        return points;
    }

    private static List<Location> generateSimplifiedOutline(World world,
                                                            int minX,
                                                            int minY,
                                                            int minZ,
                                                            int maxX,
                                                            int maxY,
                                                            int maxZ,
                                                            boolean includeMidpoints) {
        List<Location> points = new ArrayList<>();
        int[] xCorners = axisValues(minX, maxX);
        int[] zCorners = axisValues(minZ, maxZ);
        int height = Math.max(1, maxY - minY + 1);
        int columnHeight = Math.min(6, height);
        int maxColumnStart = Math.max(minY, maxY - columnHeight + 1);
        int centerColumnStart = clamp(minY + (height / 2) - (columnHeight / 2), minY, maxColumnStart);

        List<Integer> columnStarts = new ArrayList<>();
        addIfAbsent(columnStarts, centerColumnStart);
        addIfAbsent(columnStarts, minY);
        addIfAbsent(columnStarts, maxColumnStart);

        for (int startY : columnStarts) {
            int topY = Math.min(maxY, startY + columnHeight - 1);
            for (int x : xCorners) {
                for (int z : zCorners) {
                    for (int y = startY; y <= topY; y++) {
                        addPoint(points, center(world, x, y, z));
                    }
                }
            }
        }

        if (includeMidpoints) {
            int midX = minX + (maxX - minX) / 2;
            int midZ = minZ + (maxZ - minZ) / 2;
            int midY = minY + (maxY - minY) / 2;

            List<Integer> sampleYs = new ArrayList<>();
            addIfAbsent(sampleYs, clamp(midY, minY, maxY));
            addIfAbsent(sampleYs, clamp(centerColumnStart, minY, maxY));
            addIfAbsent(sampleYs, clamp(Math.min(maxY, centerColumnStart + columnHeight - 1), minY, maxY));
            addIfAbsent(sampleYs, minY);
            addIfAbsent(sampleYs, maxY);

            if (maxX > minX) {
                int[] xSamples = axisInteriorSamples(minX, maxX);
                for (int sampleX : xSamples) {
                    for (int sampleY : sampleYs) {
                        int y = clamp(sampleY, minY, maxY);
                        for (int z : zCorners) {
                            addPoint(points, center(world, sampleX, y, z));
                        }
                    }
                }
            }

            if (maxZ > minZ) {
                int[] zSamples = axisInteriorSamples(minZ, maxZ);
                for (int sampleZ : zSamples) {
                    for (int sampleY : sampleYs) {
                        int y = clamp(sampleY, minY, maxY);
                        for (int x : xCorners) {
                            addPoint(points, center(world, x, y, sampleZ));
                        }
                    }
                }
            }

            if (maxX > minX) {
                for (int sampleY : sampleYs) {
                    int y = clamp(sampleY, minY, maxY);
                    addPoint(points, center(world, midX, y, minZ));
                    if (minZ != maxZ) {
                        addPoint(points, center(world, midX, y, maxZ));
                    }
                }
            }

            if (maxZ > minZ) {
                for (int sampleY : sampleYs) {
                    int y = clamp(sampleY, minY, maxY);
                    addPoint(points, center(world, minX, y, midZ));
                    if (minX != maxX) {
                        addPoint(points, center(world, maxX, y, midZ));
                    }
                }
            }

            if (maxY > minY) {
                int clampedMidY = clamp(midY, minY, maxY);
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        addPoint(points, center(world, x, clampedMidY, z));
                    }
                }
            }
        }

        return points;
    }

    private static int[] axisValues(int min, int max) {
        if (min == max) {
            return new int[]{min};
        }
        return new int[]{min, max};
    }

    private static int[] axisInteriorSamples(int min, int max) {
        int distance = max - min;
        if (distance <= 1) {
            return new int[0];
        }
        List<Integer> samples = new ArrayList<>();
        addAxisSample(samples, min, max, fractionSample(min, distance, 0.5));
        addAxisSample(samples, min, max, fractionSample(min, distance, 0.25));
        addAxisSample(samples, min, max, fractionSample(min, distance, 0.75));
        int[] result = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            result[i] = samples.get(i);
        }
        return result;
    }

    private static int fractionSample(int min, int distance, double fraction) {
        return min + (int) Math.round(distance * fraction);
    }

    private static void addAxisSample(List<Integer> samples, int min, int max, int candidate) {
        if (candidate <= min || candidate >= max) {
            return;
        }
        if (!samples.contains(candidate)) {
            samples.add(candidate);
        }
    }

    private static void addIfAbsent(List<Integer> values, int value) {
        if (!values.contains(value)) {
            values.add(value);
        }
    }

    private static void addPoint(List<Location> points, Location location) {
        if (!points.contains(location)) {
            points.add(location);
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static Location center(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }
}
