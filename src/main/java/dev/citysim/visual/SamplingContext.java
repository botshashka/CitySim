package dev.citysim.visual;

public record SamplingContext(double distance,
                              double viewDistance,
                              double farDistanceStepMultiplier,
                              int maxPoints,
                              double jitter,
                              double sliceThickness,
                              long jitterSeed) {

    public double distanceFactor() {
        if (viewDistance <= 0) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, distance / viewDistance));
        return clamped;
    }
}
