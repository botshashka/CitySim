package dev.citysim.visual;

import dev.citysim.visual.ShapeSampler.SelectionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeSamplerTest {

    @Test
    void spanSamplingProducesWireframeEdges() {
        SelectionSnapshot snapshot = new SelectionSnapshot(0, 0, 0, 16, 16, 16);
        SamplingContext context = new SamplingContext(4, 64, 1.5, 800, 0.0, 0.0, 0);

        List<Vec3> points = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, context);

        assertFalse(points.isEmpty(), "Expected span sampling to produce outline points");
        double minX = points.stream().mapToDouble(Vec3::x).min().orElse(Double.NaN);
        double maxX = points.stream().mapToDouble(Vec3::x).max().orElse(Double.NaN);
        double minZ = points.stream().mapToDouble(Vec3::z).min().orElse(Double.NaN);
        double maxZ = points.stream().mapToDouble(Vec3::z).max().orElse(Double.NaN);
        assertTrue(minX <= 0.01 && minZ <= 0.01, "Expected outline to start near the origin corner");
        assertTrue(maxX >= 15.99 && maxZ >= 15.99, "Expected outline to reach the opposite corner");
    }

    @Test
    void fullModeFollowsSliceHeight() {
        SelectionSnapshot snapshot = new SelectionSnapshot(5, 0, 5, 25, 30, 25);
        SamplingContext context = new SamplingContext(2, 48, 1.5, 400, 0.0, 0.0, 42);

        double sliceY = 10.5;
        List<Vec3> points = ShapeSampler.sampleSelectionEdges(snapshot, YMode.FULL, 0.5, sliceY, context);

        assertFalse(points.isEmpty(), "Expected slice sampling to produce outline points");
        boolean allAtSlice = points.stream().allMatch(vec -> Math.abs(vec.y() - sliceY) < 0.01);
        assertTrue(allAtSlice, "Expected all sampled points to lie near the slice plane");
    }

    @Test
    void budgetReducesPointCount() {
        SelectionSnapshot snapshot = new SelectionSnapshot(0, 0, 0, 64, 64, 64);
        SamplingContext generous = new SamplingContext(4, 64, 1.5, 800, 0.0, 0.0, 1);
        SamplingContext tight = new SamplingContext(4, 64, 1.5, 50, 0.0, 0.0, 1);

        int generousCount = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, generous).size();
        int limitedCount = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, tight).size();

        assertTrue(generousCount >= limitedCount, "Expected tighter budgets to emit fewer or equal points");
        assertTrue(limitedCount <= 50, "Expected output to respect the max point budget");
    }
}
