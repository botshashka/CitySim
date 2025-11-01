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

    private static List<Vec3> sampleSpan(Bounds bounds,
                                         double baseStep,
                                         SamplingContext context) {
        List<Edge> edges = buildSpanEdges(bounds);
        return sampleEdges(edges, baseStep, context, bounds);
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
        return sampleEdges(edges, baseStep, context, sliceBounds);
    }

    private static List<Vec3> sampleEdges(List<Edge> edges,
                                          double baseStep,
                                          SamplingContext context,
                                          Bounds bounds) {
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
        return emitEdges(edges, stride, context, bounds);
    }

    private static List<Vec3> emitEdges(List<Edge> edges,
                                        int stride,
                                        SamplingContext context,
                                        Bounds bounds) {
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
                Vec3 adjusted = applyOffsets(x, y, z, bounds, edge.axis, context.faceOffset(), context.cornerBoost());
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

    private static Vec3 applyOffsets(double x,
                                     double y,
                                     double z,
                                     Bounds bounds,
                                     Axis axis,
                                     double faceOffset,
                                     double cornerBoost) {
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
        if (offsetX != 0.0) {
            nonZeroOffsets++;
        }
        if (offsetY != 0.0) {
            nonZeroOffsets++;
        }
        if (offsetZ != 0.0) {
            nonZeroOffsets++;
        }

        if (nonZeroOffsets >= 2) {
            offsetX *= cornerBoost;
            offsetY *= cornerBoost;
            offsetZ *= cornerBoost;
        }

        return new Vec3(x + offsetX, y + offsetY, z + offsetZ);
    }

    private static boolean nearlyEquals(double a, double b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static long quantize(Vec3 vec) {
        long qx = Double.doubleToLongBits(Math.round(vec.x() * 100000.0));
        long qy = Double.doubleToLongBits(Math.round(vec.y() * 100000.0));
        long qz = Double.doubleToLongBits(Math.round(vec.z() * 100000.0));
        long hash = 1469598103934665603L;
        hash ^= qx;
        hash *= 1099511628211L;
        hash ^= qy;
        hash *= 1099511628211L;
        hash ^= qz;
        hash *= 1099511628211L;
        return hash;
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
}
