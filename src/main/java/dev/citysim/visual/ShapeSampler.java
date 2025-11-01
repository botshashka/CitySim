package dev.citysim.visual;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Math-only sampler that converts axis-aligned boxes into particle coordinates.
 */
public final class ShapeSampler {

    private static final double EPSILON = 1.0E-6;

    private ShapeSampler() {
    }

    public static List<Vec3> sampleSelectionEdges(SelectionSnapshot selection,
                                                  YMode mode,
                                                  double baseStep,
                                                  Double sliceY,
                                                  SamplingContext context) {
        return switch (mode) {
            case FULL -> sampleFullSlice(selection, baseStep, sliceY, context);
            case SPAN -> sampleSpan(selection, baseStep, context);
        };
    }

    public static List<Vec3> sampleCuboidEdges(CuboidSnapshot cuboid,
                                               double baseStep,
                                               Double sliceY,
                                               SamplingContext context) {
        return switch (cuboid.mode()) {
            case FULL -> sampleFullSlice(cuboid, baseStep, sliceY, context);
            case SPAN -> sampleSpan(cuboid, baseStep, context);
        };
    }

    private static final double QUANTIZE_SCALE = 4096.0;
    private static final long FNV64_OFFSET_BASIS = 1469598103934665603L;
    private static final long FNV64_PRIME = 1099511628211L;
    private static final long JITTER_SALT_X = 0x9E3779B97F4A7C15L;
    private static final long JITTER_SALT_Y = 0xC2B2AE3D27D4EB4FL;
    private static final long JITTER_SALT_Z = 0x165667B19E3779F9L;

    private static List<Vec3> sampleSpan(Bounds bounds,
                                         double baseStep,
                                         SamplingContext context) {
        List<Edge> edges = buildSpanEdges(bounds);
        return sampleEdges(edges, baseStep, context, bounds, false);
    }

    private static List<Vec3> sampleFullSlice(Bounds bounds,
                                              double baseStep,
                                              Double sliceY,
                                              SamplingContext context) {
        if (sliceY == null) {
            return List.of();
        }
        double minY = sliceY;
        double maxY = sliceY;
        if (context.sliceThickness() > 0.0) {
            double half = context.sliceThickness() / 2.0;
            minY = sliceY - half;
            maxY = sliceY + half;
        }
        List<Edge> edges = buildSliceEdges(bounds, minY, maxY);
        Bounds sliceBounds = new SimpleBounds(
                bounds.minX(),
                minY,
                bounds.minZ(),
                bounds.maxX(),
                maxY,
                bounds.maxZ()
        );
        boolean isFlatSlice = nearlyEquals(minY, maxY);
        return sampleEdges(edges, baseStep, context, sliceBounds, isFlatSlice);
    }

    private static List<Vec3> sampleEdges(List<Edge> edges,
                                          double baseStep,
                                          SamplingContext context,
                                          Bounds bounds,
                                          boolean isFlatSlice) {
        if (edges.isEmpty()) {
            return List.of();
        }
        double effectiveStep = context.effectiveStep(baseStep);
        int stride = Math.max(1, (int) Math.round(effectiveStep));
        long estimate = estimateTotalPoints(edges, stride);
        int maxPoints = Math.max(1, context.maxPoints());
        while (estimate > maxPoints) {
            stride++;
            estimate = estimateTotalPoints(edges, stride);
        }
        return emitEdges(edges, stride, context, bounds, isFlatSlice);
    }

    private static List<Vec3> emitEdges(List<Edge> edges,
                                        int stride,
                                        SamplingContext context,
                                        Bounds bounds,
                                        boolean isFlatSlice) {
        List<Vec3> points = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Edge edge : edges) {
            Range range = computeRange(edge);
            int start = range.start();
            int end = range.end();
            List<Integer> samples = new ArrayList<>();
            samples.add(start);
            if (start != end) {
                int value = start + stride;
                while (value < end) {
                    samples.add(value);
                    value += stride;
                }
                samples.add(end);
            }
            for (int value : samples) {
                double x = edge.startX;
                double y = edge.startY;
                double z = edge.startZ;
                switch (edge.axis) {
                    case X -> x = value;
                    case Y -> y = value;
                    case Z -> z = value;
                }
                double originalX = x;
                double originalY = y;
                double originalZ = z;
                boolean isEndpoint = value == start || value == end;
                OffsetResult offsets = applyOffsets(x, y, z, bounds, context.faceOffset(), context.cornerBoost(), isEndpoint);
                Vec3 adjusted = applyJitter(offsets.position(), offsets, originalX, originalY, originalZ, context, isFlatSlice);
                long quantized = quantize(adjusted);
                if (seen.add(quantized)) {
                    points.add(adjusted);
                    if (points.size() >= context.maxPoints()) {
                        return points;
                    }
                }
            }
        }
        return points;
    }

    private static long estimateTotalPoints(List<Edge> edges, int stride) {
        long total = 0L;
        for (Edge edge : edges) {
            Range range = computeRange(edge);
            int start = range.start();
            int end = range.end();
            int diff = Math.abs(end - start);
            if (diff == 0) {
                total += 1;
            } else {
                total += 1 + (int) Math.ceil(diff / (double) stride);
            }
        }
        return total;
    }

    private static Range computeRange(Edge edge) {
        double axisStart = axisStart(edge);
        double axisEnd = axisEnd(edge);
        double min = Math.min(axisStart, axisEnd);
        double max = Math.max(axisStart, axisEnd);
        int start = (int) Math.ceil(min);
        int end = (int) Math.floor(max);
        if (start > end) {
            int rounded = (int) Math.round((min + max) / 2.0);
            start = rounded;
            end = rounded;
        }
        return new Range(start, end);
    }

    private static double axisStart(Edge edge) {
        return switch (edge.axis) {
            case X -> edge.startX;
            case Y -> edge.startY;
            case Z -> edge.startZ;
        };
    }

    private static double axisEnd(Edge edge) {
        return switch (edge.axis) {
            case X -> edge.endX;
            case Y -> edge.endY;
            case Z -> edge.endZ;
        };
    }

    private static OffsetResult applyOffsets(double x,
                                             double y,
                                             double z,
                                             Bounds bounds,
                                             double faceOffset,
                                             double cornerBoost,
                                             boolean isEndpoint) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minY = bounds.minY();
        double maxY = bounds.maxY();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        boolean touchesMinX = nearlyEquals(x, minX);
        boolean touchesMaxX = nearlyEquals(x, maxX);
        boolean touchesMinY = nearlyEquals(y, minY);
        boolean touchesMaxY = nearlyEquals(y, maxY);
        boolean touchesMinZ = nearlyEquals(z, minZ);
        boolean touchesMaxZ = nearlyEquals(z, maxZ);

        double offsetX = 0.0;
        double offsetY = 0.0;
        double offsetZ = 0.0;

        if (!nearlyEquals(minX, maxX)) {
            if (touchesMinX) {
                offsetX = -faceOffset;
            } else if (touchesMaxX) {
                offsetX = faceOffset;
            }
        }
        if (!nearlyEquals(minY, maxY)) {
            if (touchesMinY) {
                offsetY = -faceOffset;
            } else if (touchesMaxY) {
                offsetY = faceOffset;
            }
        }
        if (!nearlyEquals(minZ, maxZ)) {
            if (touchesMinZ) {
                offsetZ = -faceOffset;
            } else if (touchesMaxZ) {
                offsetZ = faceOffset;
            }
        }

        int nonZeroOffsets = 0;
        if (hasOffset(offsetX)) {
            nonZeroOffsets++;
        }
        if (hasOffset(offsetY)) {
            nonZeroOffsets++;
        }
        if (hasOffset(offsetZ)) {
            nonZeroOffsets++;
        }

        if (nonZeroOffsets >= 2 && isEndpoint) {
            if (hasOffset(offsetX)) {
                offsetX *= cornerBoost;
            }
            if (hasOffset(offsetY)) {
                offsetY *= cornerBoost;
            }
            if (hasOffset(offsetZ)) {
                offsetZ *= cornerBoost;
            }
        }

        Vec3 position = new Vec3(x + offsetX, y + offsetY, z + offsetZ);
        return new OffsetResult(position, hasOffset(offsetX), hasOffset(offsetY), hasOffset(offsetZ));
    }

    private static boolean nearlyEquals(double a, double b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static boolean hasOffset(double value) {
        return Math.abs(value) > EPSILON;
    }

    private static long quantize(Vec3 vec) {
        long qx = Math.round(vec.x() * QUANTIZE_SCALE);
        long qy = Math.round(vec.y() * QUANTIZE_SCALE);
        long qz = Math.round(vec.z() * QUANTIZE_SCALE);
        long hash = FNV64_OFFSET_BASIS;
        hash = fnvMix(hash, qx);
        hash = fnvMix(hash, qy);
        hash = fnvMix(hash, qz);
        return hash;
    }

    private static long fnvMix(long hash, long value) {
        hash ^= value;
        hash *= FNV64_PRIME;
        return hash;
    }

    private static Vec3 applyJitter(Vec3 position,
                                    OffsetResult offsets,
                                    double originalX,
                                    double originalY,
                                    double originalZ,
                                    SamplingContext context,
                                    boolean isFlatSlice) {
        double jitter = context.jitter();
        if (jitter <= 0.0) {
            return position;
        }
        boolean allowX = !offsets.offsetXApplied();
        boolean allowY = !offsets.offsetYApplied() && !isFlatSlice;
        boolean allowZ = !offsets.offsetZApplied();
        if (!allowX && !allowY && !allowZ) {
            return position;
        }

        long qx = Math.round(originalX * QUANTIZE_SCALE);
        long qy = Math.round(originalY * QUANTIZE_SCALE);
        long qz = Math.round(originalZ * QUANTIZE_SCALE);

        long seed = context.seedBase();
        seed = mixSeed(seed, qx);
        seed = mixSeed(seed, qy);
        seed = mixSeed(seed, qz);

        double half = jitter / 2.0;
        double jitterX = allowX ? jitterComponent(mixSeed(seed, JITTER_SALT_X), jitter, half) : 0.0;
        double jitterY = allowY ? jitterComponent(mixSeed(seed, JITTER_SALT_Y), jitter, half) : 0.0;
        double jitterZ = allowZ ? jitterComponent(mixSeed(seed, JITTER_SALT_Z), jitter, half) : 0.0;

        if (jitterX == 0.0 && jitterY == 0.0 && jitterZ == 0.0) {
            return position;
        }
        return new Vec3(position.x() + jitterX, position.y() + jitterY, position.z() + jitterZ);
    }

    private static long mixSeed(long seed, long value) {
        seed ^= value + 0x9E3779B97F4A7C15L + (seed << 6) + (seed >>> 2);
        return seed;
    }

    private static double jitterComponent(long seed, double jitter, double half) {
        long random = xorshift64(seed);
        double unit = ((random >>> 11) * 0x1.0p-53);
        return unit * jitter - half;
    }

    private static long xorshift64(long seed) {
        if (seed == 0L) {
            seed = 0x2545F4914F6CDD1DL;
        }
        seed ^= (seed << 21);
        seed ^= (seed >>> 35);
        seed ^= (seed << 4);
        return seed;
    }

    private static List<Edge> buildSpanEdges(Bounds bounds) {
        List<Edge> edges = new ArrayList<>(12);

        double minX = bounds.minX();
        double minY = bounds.minY();
        double minZ = bounds.minZ();
        double maxX = bounds.maxX();
        double maxY = bounds.maxY();
        double maxZ = bounds.maxZ();

        // Bottom rectangle (Y = minY)
        edges.add(new Edge(minX, minY, minZ, maxX, minY, minZ, Axis.X));
        edges.add(new Edge(maxX, minY, minZ, maxX, minY, maxZ, Axis.Z));
        edges.add(new Edge(maxX, minY, maxZ, minX, minY, maxZ, Axis.X));
        edges.add(new Edge(minX, minY, maxZ, minX, minY, minZ, Axis.Z));

        // Top rectangle (Y = maxY)
        edges.add(new Edge(minX, maxY, minZ, maxX, maxY, minZ, Axis.X));
        edges.add(new Edge(maxX, maxY, minZ, maxX, maxY, maxZ, Axis.Z));
        edges.add(new Edge(maxX, maxY, maxZ, minX, maxY, maxZ, Axis.X));
        edges.add(new Edge(minX, maxY, maxZ, minX, maxY, minZ, Axis.Z));

        // Vertical edges
        edges.add(new Edge(minX, minY, minZ, minX, maxY, minZ, Axis.Y));
        edges.add(new Edge(maxX, minY, minZ, maxX, maxY, minZ, Axis.Y));
        edges.add(new Edge(minX, minY, maxZ, minX, maxY, maxZ, Axis.Y));
        edges.add(new Edge(maxX, minY, maxZ, maxX, maxY, maxZ, Axis.Y));

        return edges;
    }

    private static List<Edge> buildSliceEdges(Bounds bounds, double minY, double maxY) {
        List<Edge> edges = new ArrayList<>(8);
        double minX = bounds.minX();
        double minZ = bounds.minZ();
        double maxX = bounds.maxX();
        double maxZ = bounds.maxZ();

        // Base slice
        edges.add(new Edge(minX, minY, minZ, maxX, minY, minZ, Axis.X));
        edges.add(new Edge(maxX, minY, minZ, maxX, minY, maxZ, Axis.Z));
        edges.add(new Edge(maxX, minY, maxZ, minX, minY, maxZ, Axis.X));
        edges.add(new Edge(minX, minY, maxZ, minX, minY, minZ, Axis.Z));

        if (maxY > minY + EPSILON) {
            edges.add(new Edge(minX, maxY, minZ, maxX, maxY, minZ, Axis.X));
            edges.add(new Edge(maxX, maxY, minZ, maxX, maxY, maxZ, Axis.Z));
            edges.add(new Edge(maxX, maxY, maxZ, minX, maxY, maxZ, Axis.X));
            edges.add(new Edge(minX, maxY, maxZ, minX, maxY, minZ, Axis.Z));
        }

        return edges;
    }

    private interface Bounds {
        double minX();

        double minY();

        double minZ();

        double maxX();

        double maxY();

        double maxZ();
    }

    public record SelectionSnapshot(double minX,
                                    double minY,
                                    double minZ,
                                    double maxX,
                                    double maxY,
                                    double maxZ) implements Bounds {
    }

    public record CuboidSnapshot(int id,
                                 double minX,
                                 double minY,
                                 double minZ,
                                 double maxX,
                                 double maxY,
                                 double maxZ,
                                 YMode mode) implements Bounds {
    }

    private enum Axis {X, Y, Z}

    private static final class Edge {
        final double startX;
        final double startY;
        final double startZ;
        final double endX;
        final double endY;
        final double endZ;
        final Axis axis;

        Edge(double startX, double startY, double startZ,
             double endX, double endY, double endZ,
             Axis axis) {
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
            this.axis = axis;
        }
    }

    private record Range(int start, int end) {
    }

    private record SimpleBounds(double minX,
                                 double minY,
                                 double minZ,
                                 double maxX,
                                 double maxY,
                                 double maxZ) implements Bounds {
    }

    private record OffsetResult(Vec3 position,
                                boolean offsetXApplied,
                                boolean offsetYApplied,
                                boolean offsetZApplied) {
    }
}
