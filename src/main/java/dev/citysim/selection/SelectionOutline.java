package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SelectionOutline {
    public static final String MAX_OUTLINE_PARTICLES_PATH = "selection.max_outline_particles";
    public static final String SIMPLE_OUTLINE_MIDPOINTS_PATH = "selection.simple_outline_midpoints";
    public static final int DEFAULT_MAX_OUTLINE_PARTICLES = 1500;
    public static final boolean DEFAULT_SIMPLE_OUTLINE_MIDPOINTS = true;

    private static final double EDGE_OFFSET = 0.01;

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
            int clampedViewerY = clamp(viewerY, minWorldY, maxWorldY);
            List<Location> outline = generateFullHeightColumns(
                    world,
                    minX,
                    minWorldY,
                    minZ,
                    maxX,
                    maxWorldY,
                    maxZ,
                    includeMidpoints,
                    clampedViewerY,
                    maxParticles
            );
            if (maxParticles > 0 && outline.size() > maxParticles) {
                return limitPoints(outline, maxParticles);
            }
            return outline;
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

    private static List<Location> limitPoints(List<Location> points, int maxParticles) {
        if (points.isEmpty() || maxParticles <= 0 || points.size() <= maxParticles) {
            return points;
        }
        int stride = Math.max(1, (int) Math.ceil(points.size() / (double) maxParticles));
        List<Location> limited = new ArrayList<>(Math.min(points.size(), maxParticles));
        for (int i = 0; i < points.size(); i += stride) {
            limited.add(points.get(i));
        }
        Location last = points.get(points.size() - 1);
        if (!limited.contains(last)) {
            limited.add(last);
        }
        return limited;
    }

    private static List<Location> generateFullHeightColumns(World world,
                                                            int minX,
                                                            int minY,
                                                            int minZ,
                                                            int maxX,
                                                            int maxY,
                                                            int maxZ,
                                                            boolean includeMidpoints,
                                                            int viewerY,
                                                            int maxParticles) {
        List<Location> points = new ArrayList<>();
        if (world == null || minX > maxX || minZ > maxZ) {
            return points;
        }

        List<Integer> ySamples = new ArrayList<>();
        int height = Math.max(1, maxY - minY + 1);
        int targetSamples = Math.min(6, height);
        for (int i = 0; i < targetSamples; i++) {
            double fraction = targetSamples == 1 ? 0d : (double) i / (targetSamples - 1);
            int sampleY = minY + (int) Math.round(fraction * (maxY - minY));
            addIfAbsent(ySamples, clamp(sampleY, minY, maxY));
        }
        addIfAbsent(ySamples, clamp(viewerY, minY, maxY));
        Collections.sort(ySamples);

        List<int[]> columns = new ArrayList<>();
        addColumn(columns, minX, minZ);
        addColumn(columns, maxX, minZ);
        addColumn(columns, minX, maxZ);
        addColumn(columns, maxX, maxZ);

        if (includeMidpoints) {
            int midX = minX + (maxX - minX) / 2;
            int midZ = minZ + (maxZ - minZ) / 2;
            addColumn(columns, midX, minZ);
            addColumn(columns, midX, maxZ);
            addColumn(columns, minX, midZ);
            addColumn(columns, maxX, midZ);
            addColumn(columns, midX, midZ);
        }

        for (int[] column : columns) {
            int columnX = column[0];
            int columnZ = column[1];
            for (int sampleY : ySamples) {
                Location location = outlineLocation(world, columnX, sampleY, columnZ, minX, maxX, minY, maxY, minZ, maxZ, false);
                addPoint(points, location);
                if (maxParticles > 0 && points.size() >= maxParticles) {
                    return points;
                }
            }
        }

        return points;
    }

    private static void addColumn(List<int[]> columns, int x, int z) {
        for (int[] column : columns) {
            if (column[0] == x && column[1] == z) {
                return;
            }
        }
        columns.add(new int[]{x, z});
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
            addOutlinePoints(points, world, x, y, minZ, minX, maxX, y, y, minZ, maxZ, false);
            if (minZ != maxZ) {
                addOutlinePoints(points, world, x, y, maxZ, minX, maxX, y, y, minZ, maxZ, false);
            }
        }

        if (maxZ > minZ + 1 || minX == maxX) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                addOutlinePoints(points, world, minX, y, z, minX, maxX, y, y, minZ, maxZ, false);
                if (minX != maxX) {
                    addOutlinePoints(points, world, maxX, y, z, minX, maxX, y, y, minZ, maxZ, false);
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
            ensureCorner(limited, outlineLocation(world, minX, y, minZ, minX, maxX, y, y, minZ, maxZ, false));
            ensureCorner(limited, outlineLocation(world, minX, y, maxZ, minX, maxX, y, y, minZ, maxZ, false));
            ensureCorner(limited, outlineLocation(world, maxX, y, minZ, minX, maxX, y, y, minZ, maxZ, false));
            ensureCorner(limited, outlineLocation(world, maxX, y, maxZ, minX, maxX, y, y, minZ, maxZ, false));
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
        FaceTracker faceTracker = new FaceTracker();
        int[] xCorners = axisValues(minX, maxX);
        int[] yCorners = axisValues(minY, maxY);
        int[] zCorners = axisValues(minZ, maxZ);

        for (int x : xCorners) {
            for (int y : yCorners) {
                for (int z : zCorners) {
                    addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                }
            }
        }

        if (maxX > minX + 1) {
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int y : yCorners) {
                    for (int z : zCorners) {
                        addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    }
                }
            }
        }

        if (maxZ > minZ + 1) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                for (int x : xCorners) {
                    for (int y : yCorners) {
                        addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    }
                }
            }
        }

        if (maxY > minY + 1) {
            for (int y = minY + 1; y <= maxY - 1; y++) {
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
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
        FaceTracker faceTracker = new FaceTracker();
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
                        addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
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
                            addOutlinePoints(points, world, sampleX, y, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
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
                            addOutlinePoints(points, world, x, y, sampleZ, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                        }
                    }
                }
            }

            if (maxX > minX) {
                for (int sampleY : sampleYs) {
                    int y = clamp(sampleY, minY, maxY);
                    addOutlinePoints(points, world, midX, y, minZ, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    if (minZ != maxZ) {
                        addOutlinePoints(points, world, midX, y, maxZ, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    }
                }
            }

            if (maxZ > minZ) {
                for (int sampleY : sampleYs) {
                    int y = clamp(sampleY, minY, maxY);
                    addOutlinePoints(points, world, minX, y, midZ, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    if (minX != maxX) {
                        addOutlinePoints(points, world, maxX, y, midZ, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
                    }
                }
            }

            if (maxY > minY) {
                int clampedMidY = clamp(midY, minY, maxY);
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        addOutlinePoints(points, world, x, clampedMidY, z, minX, maxX, minY, maxY, minZ, maxZ, true, faceTracker);
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

    private static void addOutlinePoints(List<Location> points,
                                         World world,
                                         int x,
                                         int y,
                                         int z,
                                         int minX,
                                         int maxX,
                                         int minY,
                                         int maxY,
                                         int minZ,
                                         int maxZ,
                                         boolean offsetY) {
        addOutlinePoints(points, world, x, y, z, minX, maxX, minY, maxY, minZ, maxZ, offsetY, null);
    }

    private static void addOutlinePoints(List<Location> points,
                                         World world,
                                         int x,
                                         int y,
                                         int z,
                                         int minX,
                                         int maxX,
                                         int minY,
                                         int maxY,
                                         int minZ,
                                         int maxZ,
                                         boolean offsetY,
                                         FaceTracker faceTracker) {
        if (world == null) {
            return;
        }
        if (offsetY && minY < maxY) {
            if (!(x == minX && y == minY && z == minZ)) {
                return;
            }
            double minXEdge = minX - EDGE_OFFSET;
            double maxXEdge = maxX + 1 + EDGE_OFFSET;
            double minYEdge = minY - EDGE_OFFSET;
            double maxYEdge = maxY + 1 + EDGE_OFFSET;
            double minZEdge = minZ - EDGE_OFFSET;
            double maxZEdge = maxZ + 1 + EDGE_OFFSET;
            double[] xCenters = centers(minX, maxX);
            double[] yCenters = centers(minY, maxY);
            double[] zCenters = centers(minZ, maxZ);
            double[] xPerim = new double[]{minXEdge, maxXEdge};
            double[] yPerim = new double[]{minYEdge, maxYEdge};
            double[] zPerim = new double[]{minZEdge, maxZEdge};

            addFacePoints(points, world, Axis.X, minXEdge, yCenters, zPerim);
            addFacePoints(points, world, Axis.X, minXEdge, yPerim, zCenters);
            addFacePoints(points, world, Axis.X, maxXEdge, yCenters, zPerim);
            addFacePoints(points, world, Axis.X, maxXEdge, yPerim, zCenters);

            addFacePoints(points, world, Axis.Z, minZEdge, xPerim, yCenters);
            addFacePoints(points, world, Axis.Z, minZEdge, xCenters, yPerim);
            addFacePoints(points, world, Axis.Z, maxZEdge, xPerim, yCenters);
            addFacePoints(points, world, Axis.Z, maxZEdge, xCenters, yPerim);

            addFacePoints(points, world, Axis.Y, minYEdge, xCenters, zPerim);
            addFacePoints(points, world, Axis.Y, minYEdge, xPerim, zCenters);
            addFacePoints(points, world, Axis.Y, maxYEdge, xCenters, zPerim);
            addFacePoints(points, world, Axis.Y, maxYEdge, xPerim, zCenters);
            return;
        }

        double[] xEdges = axisEdges(x, minX, maxX);
        double[] yEdges = offsetY ? axisEdges(y, minY, maxY) : new double[]{y + 0.5};
        double[] zEdges = axisEdges(z, minZ, maxZ);

        double minXEdge = lowerEdge(xEdges);
        double maxXEdge = upperEdge(xEdges);
        double minYEdge = lowerEdge(yEdges);
        double maxYEdge = upperEdge(yEdges);
        double minZEdge = lowerEdge(zEdges);
        double maxZEdge = upperEdge(zEdges);

        if (x == minX) {
            addFacePoints(points, world, Axis.X, minXEdge, yEdges, zEdges);
        }
        if (x == maxX) {
            addFacePoints(points, world, Axis.X, maxXEdge, yEdges, zEdges);
        }
        if (offsetY && y == minY) {
            addFacePoints(points, world, Axis.Y, minYEdge, xEdges, zEdges);
        }
        if (offsetY && y == maxY) {
            addFacePoints(points, world, Axis.Y, maxYEdge, xEdges, zEdges);
        }
        if (z == minZ) {
            addFacePoints(points, world, Axis.Z, minZEdge, xEdges, yEdges);
        }
        if (z == maxZ) {
            addFacePoints(points, world, Axis.Z, maxZEdge, xEdges, yEdges);
        }
    }

    private static boolean shouldEmitFace(FaceTracker tracker, Axis axis, double coordinate) {
        return tracker == null || tracker.markIfNew(axis, coordinate);
    }

    private static Location outlineLocation(World world,
                                            int x,
                                            int y,
                                            int z,
                                            int minX,
                                            int maxX,
                                            int minY,
                                            int maxY,
                                            int minZ,
                                            int maxZ,
                                            boolean offsetY) {
        double[] yEdges = offsetY ? axisEdges(y, minY, maxY) : new double[]{y + 0.5};
        double[] xEdges = axisEdges(x, minX, maxX);
        double[] zEdges = axisEdges(z, minZ, maxZ);
        double xCoord = selectEdgeCoordinate(x, minX, maxX, xEdges, x + 0.5);
        double yCoord = selectEdgeCoordinate(y, minY, maxY, yEdges, y + 0.5);
        double zCoord = selectEdgeCoordinate(z, minZ, maxZ, zEdges, z + 0.5);
        return new Location(world, xCoord, yCoord, zCoord);
    }

    private static double[] axisEdges(int coordinate, int min, int max) {
        if (min == max) {
            return new double[]{coordinate - EDGE_OFFSET, coordinate + 1 + EDGE_OFFSET};
        }
        if (coordinate == min) {
            return new double[]{coordinate - EDGE_OFFSET};
        }
        if (coordinate == max) {
            return new double[]{coordinate + 1 + EDGE_OFFSET};
        }
        return new double[]{coordinate, coordinate + 1};
    }

    private static double lowerEdge(double[] edges) {
        return edges[0];
    }

    private static double upperEdge(double[] edges) {
        return edges[edges.length - 1];
    }

    private static double selectEdgeCoordinate(int coordinate,
                                               int min,
                                               int max,
                                               double[] edges,
                                               double fallback) {
        if (edges.length == 0) {
            return fallback;
        }
        if (coordinate == min) {
            return lowerEdge(edges);
        }
        if (coordinate == max) {
            return upperEdge(edges);
        }
        if (edges.length == 1) {
            return edges[0];
        }
        return fallback;
    }

    private static double[] centers(int min, int max) {
        if (min > max) {
            return new double[0];
        }
        int length = max - min + 1;
        double[] values = new double[length];
        for (int i = 0; i < length; i++) {
            values[i] = min + i + 0.5;
        }
        return values;
    }

    private static void addFacePoints(List<Location> points,
                                      World world,
                                      Axis axis,
                                      double fixedCoord,
                                      double[] firstEdges,
                                      double[] secondEdges) {
        for (double first : firstEdges) {
            for (double second : secondEdges) {
                Location location;
                switch (axis) {
                    case X:
                        location = new Location(world, fixedCoord, first, second);
                        break;
                    case Y:
                        location = new Location(world, first, fixedCoord, second);
                        break;
                    case Z:
                        location = new Location(world, first, second, fixedCoord);
                        break;
                    default:
                        continue;
                }
                addPoint(points, location);
            }
        }
    }

    private static final double QUANTIZE_EPSILON = 1e-6;

    private static double quantize(double coordinate) {
        return Math.round(coordinate / QUANTIZE_EPSILON) * QUANTIZE_EPSILON;
    }

    private static final class FaceTracker {
        private final EnumMap<Axis, Set<Double>> processed = new EnumMap<>(Axis.class);

        boolean markIfNew(Axis axis, double coordinate) {
            Set<Double> coordinates = processed.computeIfAbsent(axis, ignored -> new HashSet<>());
            double quantized = quantize(coordinate);
            if (coordinates.contains(quantized)) {
                return false;
            }
            coordinates.add(quantized);
            return true;
        }
    }

    private enum Axis {
        X,
        Y,
        Z
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

}
