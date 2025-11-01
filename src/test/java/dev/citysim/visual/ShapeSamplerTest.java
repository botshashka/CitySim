package dev.citysim.visual;

import dev.citysim.visual.ShapeSampler.SelectionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeSamplerTest {

    @Test
    void spanSamplingProducesWireframeEdges() {
        SelectionSnapshot snapshot = new SelectionSnapshot(0, 0, 0, 16, 16, 16);
        assertEquals(16, snapshot.maxZ(), 0.0001, "Expected snapshot to span 16 blocks on Z");
        SamplingContext context = new SamplingContext(4, 64, 1.5, 800, 0.0, 0.0, 0.03125, 2.0, 0);

        List<Vec3> points = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, context);

        assertFalse(points.isEmpty(), "Expected span sampling to produce outline points");
        double minX = points.stream().mapToDouble(Vec3::x).min().orElse(Double.NaN);
        double maxX = points.stream().mapToDouble(Vec3::x).max().orElse(Double.NaN);
        double minZ = points.stream().mapToDouble(Vec3::z).min().orElse(Double.NaN);
        double maxZ = points.stream().mapToDouble(Vec3::z).max().orElse(Double.NaN);
        assertTrue(minX <= 0.01 && minZ <= 0.01,
                "Expected outline to start near the origin corner but had minX=" + minX + ", minZ=" + minZ);
        assertTrue(maxX >= 15.99 && maxZ >= 15.99,
                "Expected outline to reach the opposite corner but had maxX=" + maxX + ", maxZ=" + maxZ);
    }

    @Test
    void fullModeFollowsSliceHeight() {
        SelectionSnapshot snapshot = new SelectionSnapshot(5, 0, 5, 25, 30, 25);
        SamplingContext context = new SamplingContext(2, 48, 1.5, 400, 0.0, 0.0, 0.03125, 2.0, 42);

        double sliceY = 10.5;
        List<Vec3> points = ShapeSampler.sampleSelectionEdges(snapshot, YMode.FULL, 0.5, sliceY, context);

        assertFalse(points.isEmpty(), "Expected slice sampling to produce outline points");
        boolean allAtSlice = points.stream().allMatch(vec -> Math.abs(vec.y() - sliceY) < 0.01);
        assertTrue(allAtSlice, "Expected all sampled points to lie near the slice plane");
    }

    @Test
    void budgetReducesPointCount() {
        SelectionSnapshot snapshot = new SelectionSnapshot(0, 0, 0, 64, 64, 64);
        SamplingContext generous = new SamplingContext(4, 64, 1.5, 800, 0.0, 0.0, 0.03125, 2.0, 1);
        SamplingContext tight = new SamplingContext(4, 64, 1.5, 50, 0.0, 0.0, 0.03125, 2.0, 1);

        int generousCount = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, generous).size();
        int limitedCount = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, tight).size();

        assertTrue(generousCount >= limitedCount, "Expected tighter budgets to emit fewer or equal points");
        assertTrue(limitedCount <= 50, "Expected output to respect the max point budget");
    }

    @Test
    void jitterDoesNotMoveAlongNormals() {
        SelectionSnapshot spanSnapshot = new SelectionSnapshot(0, 0, 0, 4, 4, 4);
        double faceOffset = 0.03125;
        double cornerBoost = 2.0;
        SamplingContext jitterContext = new SamplingContext(4, 64, 1.5, 800, 0.2, 0.0, faceOffset, cornerBoost, 99);

        List<Vec3> spanPoints = ShapeSampler.sampleSelectionEdges(spanSnapshot, YMode.SPAN, 0.5, null, jitterContext);
        double expectedFaceX = spanSnapshot.maxX() + faceOffset;
        double expectedCornerX = spanSnapshot.maxX() + faceOffset * cornerBoost;

        long faceSamples = spanPoints.stream()
                .filter(vec -> vec.x() > spanSnapshot.maxX())
                .peek(vec -> {
                    double deltaFace = Math.abs(vec.x() - expectedFaceX);
                    double deltaCorner = Math.abs(vec.x() - expectedCornerX);
                    double minDelta = Math.min(deltaFace, deltaCorner);
                    assertTrue(minDelta < 1.0E-9, "Jitter should not move points inward/outward on +X faces");
                })
                .count();
        assertTrue(faceSamples > 0, "Expected to sample points on the +X face");

        double sliceY = 12.5;
        SamplingContext flatSliceContext = new SamplingContext(2, 48, 1.5, 400, 0.2, 0.0, faceOffset, cornerBoost, 12345L);
        List<Vec3> fullPoints = ShapeSampler.sampleSelectionEdges(spanSnapshot, YMode.FULL, 0.5, sliceY, flatSliceContext);
        assertFalse(fullPoints.isEmpty(), "Expected FULL slice to produce outline points");
        fullPoints.forEach(vec -> assertEquals(sliceY, vec.y(), 1.0E-9, "Jitter must keep flat slices on the slice plane"));
    }

    @Test
    void cornerBoostOnlyAppliesAtEndpoints() {
        SelectionSnapshot snapshot = new SelectionSnapshot(0, 0, 0, 4, 4, 4);
        double faceOffset = 0.03125;
        double cornerBoost = 2.0;
        SamplingContext context = new SamplingContext(4, 64, 1.5, 800, 0.0, 0.0, faceOffset, cornerBoost, 7);

        List<Vec3> points = ShapeSampler.sampleSelectionEdges(snapshot, YMode.SPAN, 0.5, null, context);

        Vec3 midpoint = points.stream()
                .filter(vec -> Math.abs(vec.x() + faceOffset) < 1.0E-6)
                .filter(vec -> Math.abs(vec.z() + faceOffset) < 1.0E-6)
                .filter(vec -> Math.abs(vec.y() - 2.0) < 1.0E-6)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected to find a midpoint sample on the vertical edge"));
        assertEquals(-faceOffset, midpoint.x(), 1.0E-6, "Midpoints should keep single-axis offsets");
        assertEquals(-faceOffset, midpoint.z(), 1.0E-6, "Midpoints should keep single-axis offsets");

        double boosted = -faceOffset * cornerBoost;
        Vec3 bottom = points.stream()
                .filter(vec -> Math.abs(vec.y() - boosted) < 1.0E-6)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected to find the bottom endpoint"));
        assertEquals(boosted, bottom.x(), 1.0E-6, "Endpoints should have boosted X offsets");
        assertEquals(boosted, bottom.z(), 1.0E-6, "Endpoints should have boosted Z offsets");

        double topExpected = 4.0 + faceOffset * cornerBoost;
        Vec3 top = points.stream()
                .filter(vec -> Math.abs(vec.y() - topExpected) < 1.0E-6)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected to find the top endpoint"));
        assertEquals(boosted, top.x(), 1.0E-6, "Endpoints should have boosted X offsets");
        assertEquals(boosted, top.z(), 1.0E-6, "Endpoints should have boosted Z offsets");
    }
}
