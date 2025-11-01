package dev.citysim.visual;

import dev.citysim.city.Cuboid;
import dev.citysim.visual.SelectionTracker.SelectionSnapshot;
import dev.citysim.visual.SelectionTracker.YMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ShapeSampler {
    private static final double OUTER_OFFSET = 0.001;

    public List<Vec3> sampleCuboidEdges(Cuboid cuboid,
                                        YMode mode,
                                        double baseStep,
                                        Double sliceYOrNull,
                                        SamplingContext context) {
        if (cuboid == null) {
            return List.of();
        }
        Bounds bounds = new Bounds(cuboid.minX, cuboid.maxX, cuboid.minY, cuboid.maxY, cuboid.minZ, cuboid.maxZ);
        return sampleBounds(bounds, mode, baseStep, sliceYOrNull, context);
    }

    public List<Vec3> sampleSelectionEdges(SelectionSnapshot snapshot,
                                           YMode mode,
                                           double baseStep,
                                           Double sliceYOrNull,
                                           SamplingContext context) {
        if (snapshot == null) {
            return List.of();
        }
        Bounds bounds = new Bounds(snapshot.minX(), snapshot.maxX(), snapshot.minY(), snapshot.maxY(), snapshot.minZ(), snapshot.maxZ());
        return sampleBounds(bounds, mode, baseStep, sliceYOrNull, context);
    }

    private List<Vec3> sampleBounds(Bounds bounds,
                                    YMode mode,
                                    double baseStep,
                                    Double sliceYOrNull,
                                    SamplingContext context) {
        if (bounds.invalid()) {
            return List.of();
        }
        double distance = bounds.distanceTo(context.playerX(), context.playerY(), context.playerZ());
        if (distance > context.viewDistance()) {
            return List.of();
        }

        double effectiveStep = resolveEffectiveStep(bounds, baseStep, context, distance);
        List<Vec3> points = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (mode == YMode.FULL) {
            double sliceY = sliceYOrNull != null ? sliceYOrNull : Math.floor(context.playerY()) + 0.5;
            sampleHorizontalSlice(bounds, sliceY, effectiveStep, context, points, seen);
        } else {
            sampleSpan(bounds, effectiveStep, context, points, seen);
        }
        return points;
    }

    private double resolveEffectiveStep(Bounds bounds,
                                        double baseStep,
                                        SamplingContext context,
                                        double distance) {
        double clamped = Math.max(0.0, Math.min(1.0, distance / Math.max(1.0, context.viewDistance())));
        double step = baseStep * lerp(1.0, context.farDistanceMultiplier(), clamped);
        double estimated = estimateTotalPoints(bounds, step, context, distance);
        if (estimated > context.maxPointsPerTick()) {
            double scale = estimated / (double) context.maxPointsPerTick();
            step *= scale;
        }
        return Math.max(step, 0.25);
    }

    private double estimateTotalPoints(Bounds bounds, double step, SamplingContext context, double distance) {
        if (step <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double perimeterX = 2.0 * ((bounds.sizeX() + bounds.sizeZ()));
        double vertical = 4.0 * bounds.sizeY();
        double slices = 2.0 * perimeterX + vertical;
        double estimate = slices / step;
        if (context.sliceThickness() > 0.0) {
            estimate *= 1.25;
        }
        double distanceFactor = Math.max(0.25, 1.0 - Math.min(1.0, distance / Math.max(1.0, context.viewDistance())) * 0.5);
        return estimate / distanceFactor;
    }

    private void sampleSpan(Bounds bounds,
                            double step,
                            SamplingContext context,
                            List<Vec3> points,
                            Set<Long> seen) {
        double minX = bounds.minFaceX();
        double maxX = bounds.maxFaceX();
        double minY = bounds.minFaceY();
        double maxY = bounds.maxFaceY();
        double minZ = bounds.minFaceZ();
        double maxZ = bounds.maxFaceZ();

        List<Edge> edges = new ArrayList<>(12);

        // Bottom edges (Y = minY)
        edges.add(new Edge(minX, minY, minZ, Axis.X, maxX - minX, 0.0, -OUTER_OFFSET, -OUTER_OFFSET));
        edges.add(new Edge(minX, minY, maxZ, Axis.X, maxX - minX, 0.0, -OUTER_OFFSET, OUTER_OFFSET));
        edges.add(new Edge(minX, minY, minZ, Axis.Z, maxZ - minZ, -OUTER_OFFSET, -OUTER_OFFSET, 0.0));
        edges.add(new Edge(maxX, minY, minZ, Axis.Z, maxZ - minZ, OUTER_OFFSET, -OUTER_OFFSET, 0.0));

        // Top edges (Y = maxY)
        edges.add(new Edge(minX, maxY, minZ, Axis.X, maxX - minX, 0.0, OUTER_OFFSET, -OUTER_OFFSET));
        edges.add(new Edge(minX, maxY, maxZ, Axis.X, maxX - minX, 0.0, OUTER_OFFSET, OUTER_OFFSET));
        edges.add(new Edge(minX, maxY, minZ, Axis.Z, maxZ - minZ, -OUTER_OFFSET, OUTER_OFFSET, 0.0));
        edges.add(new Edge(maxX, maxY, minZ, Axis.Z, maxZ - minZ, OUTER_OFFSET, OUTER_OFFSET, 0.0));

        // Vertical edges
        edges.add(new Edge(minX, minY, minZ, Axis.Y, maxY - minY, -OUTER_OFFSET, 0.0, -OUTER_OFFSET));
        edges.add(new Edge(minX, minY, maxZ, Axis.Y, maxY - minY, -OUTER_OFFSET, 0.0, OUTER_OFFSET));
        edges.add(new Edge(maxX, minY, minZ, Axis.Y, maxY - minY, OUTER_OFFSET, 0.0, -OUTER_OFFSET));
        edges.add(new Edge(maxX, minY, maxZ, Axis.Y, maxY - minY, OUTER_OFFSET, 0.0, OUTER_OFFSET));

        for (Edge edge : edges) {
            emitEdge(points, seen, edge, step, context);
        }
    }

    private void sampleHorizontalSlice(Bounds bounds,
                                        double sliceY,
                                        double step,
                                        SamplingContext context,
                                        List<Vec3> points,
                                        Set<Long> seen) {
        double minX = bounds.minFaceX();
        double maxX = bounds.maxFaceX();
        double minZ = bounds.minFaceZ();
        double maxZ = bounds.maxFaceZ();

        List<Double> yLevels = new ArrayList<>();
        yLevels.add(sliceY);
        double thickness = context.sliceThickness();
        if (thickness > 0.0) {
            double half = thickness * 0.5;
            yLevels.add(sliceY + half);
            yLevels.add(sliceY - half);
        }

        for (double yLevel : yLevels) {
            List<Edge> edges = new ArrayList<>(4);
            edges.add(new Edge(minX, yLevel, minZ, Axis.X, maxX - minX, 0.0, 0.0, -OUTER_OFFSET));
            edges.add(new Edge(minX, yLevel, maxZ, Axis.X, maxX - minX, 0.0, 0.0, OUTER_OFFSET));
            edges.add(new Edge(minX, yLevel, minZ, Axis.Z, maxZ - minZ, -OUTER_OFFSET, 0.0, 0.0));
            edges.add(new Edge(maxX, yLevel, minZ, Axis.Z, maxZ - minZ, OUTER_OFFSET, 0.0, 0.0));
            for (Edge edge : edges) {
                emitEdge(points, seen, edge, step, context);
            }
        }
    }

    private void emitEdge(List<Vec3> target,
                          Set<Long> seen,
                          Edge edge,
                          double step,
                          SamplingContext context) {
        double length = Math.max(0.0, edge.length);
        if (length == 0.0) {
            addPoint(target, seen, edge.startX + edge.offsetX, edge.startY + edge.offsetY, edge.startZ + edge.offsetZ, context);
            return;
        }
        int samples = Math.max(2, (int) Math.ceil(length / step) + 1);
        double actualStep = length / (samples - 1);
        double distance = 0.0;
        for (int i = 0; i < samples; i++) {
            double x = edge.startX + edge.axis.dx * distance + edge.offsetX;
            double y = edge.startY + edge.axis.dy * distance + edge.offsetY;
            double z = edge.startZ + edge.axis.dz * distance + edge.offsetZ;
            addPoint(target, seen, x, y, z, context);
            distance = Math.min(length, distance + actualStep);
        }
    }

    private void addPoint(List<Vec3> target,
                          Set<Long> seen,
                          double x,
                          double y,
                          double z,
                          SamplingContext context) {
        long key = hashPoint(x, y, z);
        if (!seen.add(key)) {
            return;
        }
        double jitter = context.jitterAmount();
        if (jitter > 0.0) {
            long seed = mix(hash(x), hash(z));
            double jx = ((seed & 0xFFFFL) / 65535.0 - 0.5) * jitter;
            double jy = (((seed >> 16) & 0xFFFFL) / 65535.0 - 0.5) * jitter * 0.5;
            double jz = (((seed >> 32) & 0xFFFFL) / 65535.0 - 0.5) * jitter;
            x += jx;
            y += jy;
            z += jz;
        }
        target.add(new Vec3(x, y, z));
    }

    private long hash(double value) {
        long bits = Double.doubleToLongBits(value);
        bits ^= (bits >>> 33);
        bits *= 0xff51afd7ed558ccdL;
        bits ^= (bits >>> 33);
        bits *= 0xc4ceb9fe1a85ec53L;
        bits ^= (bits >>> 33);
        return bits;
    }

    private long mix(long a, long b) {
        long result = 0x9E3779B97F4A7C15L;
        result ^= a;
        result *= 0xBF58476D1CE4E5B9L;
        result ^= b;
        result *= 0x94D049BB133111EBL;
        return result;
    }

    private long hashPoint(double x, double y, double z) {
        long hx = Math.round(x * 512.0);
        long hy = Math.round(y * 512.0);
        long hz = Math.round(z * 512.0);
        long h = hx;
        h = (h * 31) ^ hy;
        h = (h * 31) ^ hz;
        return h;
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private enum Axis {
        X(1.0, 0.0, 0.0),
        Y(0.0, 1.0, 0.0),
        Z(0.0, 0.0, 1.0);

        final double dx;
        final double dy;
        final double dz;

        Axis(double dx, double dy, double dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }

    private record Edge(double startX,
                        double startY,
                        double startZ,
                        Axis axis,
                        double length,
                        double offsetX,
                        double offsetY,
                        double offsetZ) {
    }

    private static final class Bounds {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = Math.min(minX, maxX);
            this.maxX = Math.max(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.maxY = Math.max(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxZ = Math.max(minZ, maxZ);
        }

        private boolean invalid() {
            return maxX < minX || maxY < minY || maxZ < minZ;
        }

        private double minFaceX() {
            return minX;
        }

        private double maxFaceX() {
            return maxX + 1.0;
        }

        private double minFaceY() {
            return minY;
        }

        private double maxFaceY() {
            return maxY + 1.0;
        }

        private double minFaceZ() {
            return minZ;
        }

        private double maxFaceZ() {
            return maxZ + 1.0;
        }

        private double sizeX() {
            return maxFaceX() - minFaceX();
        }

        private double sizeY() {
            return maxFaceY() - minFaceY();
        }

        private double sizeZ() {
            return maxFaceZ() - minFaceZ();
        }

        double distanceTo(double px, double py, double pz) {
            double dx = Math.max(Math.max(minFaceX() - px, 0.0), px - maxFaceX());
            double dy = Math.max(Math.max(minFaceY() - py, 0.0), py - maxFaceY());
            double dz = Math.max(Math.max(minFaceZ() - pz, 0.0), pz - maxFaceZ());
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
