package dev.citysim.visual;

/**
 * Runtime parameters describing how a particular outline should be sampled.
 */
public record SamplingContext(
        double distance,
        double viewDistance,
        double farDistanceStepMultiplier,
        int maxPoints,
        double jitter,
        double sliceThickness,
        long seedBase
) {

    public double effectiveStep(double baseStep) {
        double factor = 1.0;
        if (viewDistance > 0.0) {
            double ratio = Math.max(0.0, Math.min(1.0, distance / viewDistance));
            double lerp = 1.0 + (farDistanceStepMultiplier - 1.0) * ratio;
            factor *= lerp;
        }
        return baseStep * factor;
    }

    public boolean jitterEnabled() {
        return jitter > 0.0;
    }
}
