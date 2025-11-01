package dev.citysim.visual;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Math-only sampler that converts axis-aligned boxes into particle coordinates.
 */
public final class ShapeSampler {

    private static final double EDGE_OFFSET = 0.001;

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
                                               YMode mode,
                                               double baseStep,
                                               Double sliceY,
                                               SamplingContext context) {
        return switch (mode) {
            case FULL -> sampleFullSlice(cuboid, baseStep, sliceY, context);
            case SPAN -> sampleSpan(cuboid, baseStep, context);
        };
    }

    private static List<Vec3> sampleSpan(Bounds bounds,
                                         double baseStep,
                                         SamplingContext context) {
        double effectiveStep = context.effectiveStep(baseStep);
        List<Edge> edges = buildSpanEdges(bounds);
        double totalPoints = estimateTotalPoints(edges, effectiveStep);
        if (totalPoints > context.maxPoints()) {
            double scale = totalPoints / context.maxPoints();
            effectiveStep *= scale;
        }
        return sampleEdges(edges, effectiveStep, context, false);
    }

    private static List<Vec3> sampleFullSlice(Bounds bounds,
                                              double baseStep,
                                              Double sliceY,
                                              SamplingContext context) {
        if (sliceY == null) {
            return List.of();
        }
        double effectiveStep = context.effectiveStep(baseStep);
        double minY = sliceY;
        double maxY = sliceY;
        if (context.sliceThickness() > 0.0) {
            minY = sliceY - context.sliceThickness() / 2.0;
            maxY = sliceY + context.sliceThickness() / 2.0;
        }
        List<Edge> edges = buildSliceEdges(bounds, minY, maxY);
        double totalPoints = estimateTotalPoints(edges, effectiveStep);
        if (totalPoints > context.maxPoints()) {
            double scale = totalPoints / context.maxPoints();
            effectiveStep *= scale;
        }
        return sampleEdges(edges, effectiveStep, context, true);
    }

    private static List<Vec3> sampleEdges(List<Edge> edges,
                                          double step,
                                          SamplingContext context,
                                          boolean horizontalSlice) {
        if (edges.isEmpty() || step <= 0.0) {
            return List.of();
        }
        List<Vec3> points = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            Edge edge = edges.get(edgeIndex);
            double length = edge.length();
            int segments = Math.max(1, (int) Math.ceil(length / step));
            Vec3 direction = edge.direction(segments);

            for (int i = 0; i <= segments; i++) {
                if (edgeIndex > 0 && i == 0) {
                    continue; // avoid duplicating the start vertex already emitted by previous edge
                }
                double x = edge.startX + direction.x() * i;
                double y = edge.startY + direction.y() * i;
                double z = edge.startZ + direction.z() * i;

                Vec3 adjusted = adjustForOuterFace(x, y, z, edge.axis, horizontalSlice);
                Vec3 jittered = applyJitter(adjusted, context);
                long quantized = quantize(jittered);
                if (seen.add(quantized)) {
                    points.add(jittered);
                    if (points.size() >= context.maxPoints()) {
                        return points;
                    }
                }
            }
        }
        return points;
    }

    private static Vec3 adjustForOuterFace(double x,
                                           double y,
                                           double z,
                                           Axis axis,
                                           boolean horizontalSlice) {
        double offset = EDGE_OFFSET;
        double adjustedX = x;
        double adjustedY = y;
        double adjustedZ = z;
        switch (axis) {
            case X -> {
                adjustedY += y < Math.floor(y) + 0.5 ? -offset : offset;
                adjustedZ += z < Math.floor(z) + 0.5 ? -offset : offset;
            }
            case Y -> {
                adjustedX += x < Math.floor(x) + 0.5 ? -offset : offset;
                adjustedZ += z < Math.floor(z) + 0.5 ? -offset : offset;
            }
            case Z -> {
                adjustedX += x < Math.floor(x) + 0.5 ? -offset : offset;
                adjustedY += horizontalSlice ? 0.0 : (y < Math.floor(y) + 0.5 ? -offset : offset);
            }
        }
        return new Vec3(adjustedX, adjustedY, adjustedZ);
    }

    private static Vec3 applyJitter(Vec3 point, SamplingContext context) {
        if (!context.jitterEnabled()) {
            return point;
        }
        long seed = Double.doubleToLongBits(Math.floor(point.x() * 16.0))
                ^ (Double.doubleToLongBits(Math.floor(point.z() * 16.0)) << 1)
                ^ context.seedBase();
        // simple xorshift for deterministic pseudo-random values
        seed ^= (seed << 21);
        seed ^= (seed >>> 35);
        seed ^= (seed << 4);
        double jitter = context.jitter() / 2.0;
        double jx = ((seed & 0xFF) / 255.0) * 2 - 1;
        double jy = (((seed >> 8) & 0xFF) / 255.0) * 2 - 1;
        double jz = (((seed >> 16) & 0xFF) / 255.0) * 2 - 1;
        return new Vec3(point.x() + jx * jitter, point.y() + jy * jitter, point.z() + jz * jitter);
    }

    private static double estimateTotalPoints(List<Edge> edges, double step) {
        double total = 0;
        for (Edge edge : edges) {
            double length = edge.length();
            int segments = Math.max(1, (int) Math.ceil(length / step));
            total += segments + 1;
        }
        return total;
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

        if (maxY > minY) {
            edges.add(new Edge(minX, maxY, minZ, maxX, maxY, minZ, Axis.X));
            edges.add(new Edge(maxX, maxY, minZ, maxX, maxY, maxZ, Axis.Z));
            edges.add(new Edge(maxX, maxY, maxZ, minX, maxY, maxZ, Axis.X));
            edges.add(new Edge(minX, maxY, maxZ, minX, maxY, minZ, Axis.Z));
        }

        return edges;
    }

    private static long quantize(Vec3 vec) {
        long qx = Double.doubleToLongBits(Math.round(vec.x() * 1000.0));
        long qy = Double.doubleToLongBits(Math.round(vec.y() * 1000.0));
        long qz = Double.doubleToLongBits(Math.round(vec.z() * 1000.0));
        return qx ^ (qy << 21) ^ (qz << 42);
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

    public record CuboidSnapshot(double minX,
                                 double minY,
                                 double minZ,
                                 double maxX,
                                 double maxY,
                                 double maxZ) implements Bounds {
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

        double length() {
            return switch (axis) {
                case X -> Math.abs(endX - startX);
                case Y -> Math.abs(endY - startY);
                case Z -> Math.abs(endZ - startZ);
            };
        }

        Vec3 direction(int segments) {
            if (segments <= 0) {
                return new Vec3(0, 0, 0);
            }
            double dx = (endX - startX) / segments;
            double dy = (endY - startY) / segments;
            double dz = (endZ - startZ) / segments;
            return new Vec3(dx, dy, dz);
        }
    }
}
