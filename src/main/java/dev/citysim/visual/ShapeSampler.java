package dev.citysim.visual;

import dev.citysim.city.Cuboid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ShapeSampler {
    private static final double EPSILON = 0.001;

    public List<Vec3> sampleCuboidEdges(Cuboid cuboid,
                                        YMode mode,
                                        double baseStep,
                                        Double sliceY,
                                        SamplingContext context) {
        if (cuboid == null) {
            return List.of();
        }
        return sampleBox(cuboid.minX,
                cuboid.minY,
                cuboid.minZ,
                cuboid.maxX,
                cuboid.maxY,
                cuboid.maxZ,
                mode,
                sliceY,
                baseStep,
                context);
    }

    public List<Vec3> sampleSelectionEdges(SelectionBounds bounds,
                                           YMode mode,
                                           double baseStep,
                                           Double sliceY,
                                           SamplingContext context) {
        if (bounds == null) {
            return List.of();
        }
        return sampleBox(bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ(),
                mode,
                sliceY,
                baseStep,
                context);
    }

    private List<Vec3> sampleBox(int minX,
                                 int minY,
                                 int minZ,
                                 int maxX,
                                 int maxY,
                                 int maxZ,
                                 YMode mode,
                                 Double sliceY,
                                 double baseStep,
                                 SamplingContext context) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return List.of();
        }

        double minXEdge = minX - EPSILON;
        double maxXEdge = maxX + 1.0 + EPSILON;
        double minYEdge = minY - EPSILON;
        double maxYEdge = maxY + 1.0 + EPSILON;
        double minZEdge = minZ - EPSILON;
        double maxZEdge = maxZ + 1.0 + EPSILON;

        double effectiveStep = computeEffectiveStep(baseStep, context);
        List<Edge> edges = mode == YMode.FULL
                ? sliceEdges(minXEdge, maxXEdge, minZEdge, maxZEdge, sliceY, context)
                : spanEdges(minXEdge, maxXEdge, minYEdge, maxYEdge, minZEdge, maxZEdge);
        if (edges.isEmpty()) {
            return List.of();
        }

        effectiveStep = adjustForBudget(edges, effectiveStep, context.maxPoints());
        Set<Vec3> unique = new LinkedHashSet<>();
        for (Edge edge : edges) {
            List<Vec3> samples = sampleEdge(edge, effectiveStep, context);
            unique.addAll(samples);
        }
        return new ArrayList<>(unique);
    }

    private double computeEffectiveStep(double baseStep, SamplingContext context) {
        double multiplier = lerp(1.0, context.farDistanceStepMultiplier(), context.distanceFactor());
        if (multiplier <= 0.0) {
            multiplier = 1.0;
        }
        return Math.max(0.1, baseStep * multiplier);
    }

    private double adjustForBudget(List<Edge> edges, double step, int maxPoints) {
        if (maxPoints <= 0) {
            return step;
        }
        long predicted = predictPoints(edges, step);
        if (predicted <= maxPoints) {
            return step;
        }
        double scale = Math.max(1.0, predicted / (double) maxPoints);
        return step * scale;
    }

    private long predictPoints(List<Edge> edges, double step) {
        long total = 0;
        for (Edge edge : edges) {
            double length = edge.length();
            if (length <= 1e-6) {
                total += 1;
                continue;
            }
            int segments = Math.max(1, (int) Math.ceil(length / step));
            total += (segments + 1L);
        }
        return total;
    }

    private List<Edge> spanEdges(double minX,
                                 double maxX,
                                 double minY,
                                 double maxY,
                                 double minZ,
                                 double maxZ) {
        List<Edge> edges = new ArrayList<>(12);
        double[] yBounds = {minY, maxY};
        double[] zBounds = {minZ, maxZ};
        double[] xBounds = {minX, maxX};

        for (double y : yBounds) {
            for (double z : zBounds) {
                edges.add(new Edge(new Vec3(minX, y, z), new Vec3(maxX, y, z), Axis.X));
            }
        }
        for (double x : xBounds) {
            for (double z : zBounds) {
                edges.add(new Edge(new Vec3(x, minY, z), new Vec3(x, maxY, z), Axis.Y));
            }
        }
        for (double x : xBounds) {
            for (double y : yBounds) {
                edges.add(new Edge(new Vec3(x, y, minZ), new Vec3(x, y, maxZ), Axis.Z));
            }
        }
        return edges;
    }

    private List<Edge> sliceEdges(double minX,
                                  double maxX,
                                  double minZ,
                                  double maxZ,
                                  Double sliceY,
                                  SamplingContext context) {
        if (sliceY == null) {
            return List.of();
        }
        double thickness = context.sliceThickness();
        List<Double> layerList = new ArrayList<>();
        layerList.add(sliceY);
        if (thickness > 0.0) {
            double half = thickness / 2.0;
            layerList.add(sliceY - half);
            layerList.add(sliceY + half);
        }
        Set<Double> layers = new LinkedHashSet<>(layerList);
        List<Edge> edges = new ArrayList<>();
        for (Double layer : layers) {
            if (layer == null) {
                continue;
            }
            double y = layer;
            edges.add(new Edge(new Vec3(minX, y, minZ), new Vec3(maxX, y, minZ), Axis.X));
            edges.add(new Edge(new Vec3(minX, y, maxZ), new Vec3(maxX, y, maxZ), Axis.X));
            edges.add(new Edge(new Vec3(minX, y, minZ), new Vec3(minX, y, maxZ), Axis.Z));
            edges.add(new Edge(new Vec3(maxX, y, minZ), new Vec3(maxX, y, maxZ), Axis.Z));
        }
        return edges;
    }

    private List<Vec3> sampleEdge(Edge edge, double step, SamplingContext context) {
        double length = edge.length();
        List<Vec3> points = new ArrayList<>();
        if (length <= 1e-6) {
            points.add(applyJitter(edge.start(), context));
            return points;
        }
        int segments = Math.max(1, (int) Math.ceil(length / step));
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3 point = edge.interpolate(t);
            points.add(applyJitter(point, context));
        }
        return points;
    }

    private Vec3 applyJitter(Vec3 point, SamplingContext context) {
        double jitter = context.jitter();
        if (jitter <= 0.0) {
            return point;
        }
        double offsetX = jitterValue(point.x(), point.z(), context.jitterSeed(), jitter);
        double offsetZ = jitterValue(point.z(), point.x(), context.jitterSeed() ^ 0x5DEECE66DL, jitter);
        return new Vec3(point.x() + offsetX, point.y(), point.z() + offsetZ);
    }

    private double jitterValue(double a, double b, long seed, double jitter) {
        long hash = Double.doubleToLongBits(a);
        hash = 31L * hash + Double.doubleToLongBits(b);
        hash ^= seed;
        hash ^= (hash >>> 32);
        long bits = hash & 0xFFFFFL;
        double normalized = bits / 0xFFFFFL;
        return (normalized - 0.5) * jitter;
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private enum Axis {
        X, Y, Z
    }

    private record Edge(Vec3 start, Vec3 end, Axis axis) {
        double length() {
            return switch (axis) {
                case X -> Math.abs(end.x() - start.x());
                case Y -> Math.abs(end.y() - start.y());
                case Z -> Math.abs(end.z() - start.z());
            };
        }

        Vec3 interpolate(double t) {
            double x = start.x() + (end.x() - start.x()) * t;
            double y = start.y() + (end.y() - start.y()) * t;
            double z = start.z() + (end.z() - start.z()) * t;
            return new Vec3(x, y, z);
        }
    }
}
