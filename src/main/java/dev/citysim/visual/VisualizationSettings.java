package dev.citysim.visual;

import org.bukkit.Color;
import org.bukkit.Particle;

public final class VisualizationSettings {
    private final boolean enabled;
    private final Particle particle;
    private final Color dustColor;
    private final double viewDistance;
    private final double baseStep;
    private final double farDistanceStepMultiplier;
    private final int maxPointsPerTick;
    private final int refreshTicks;
    private final boolean asyncPrepare;
    private final double jitter;
    private final double sliceThickness;
    private final boolean debug;

    public VisualizationSettings(boolean enabled,
                                  Particle particle,
                                  Color dustColor,
                                  double viewDistance,
                                  double baseStep,
                                  double farDistanceStepMultiplier,
                                  int maxPointsPerTick,
                                  int refreshTicks,
                                  boolean asyncPrepare,
                                  double jitter,
                                  double sliceThickness,
                                  boolean debug) {
        this.enabled = enabled;
        this.particle = particle;
        this.dustColor = dustColor;
        this.viewDistance = viewDistance;
        this.baseStep = baseStep;
        this.farDistanceStepMultiplier = farDistanceStepMultiplier;
        this.maxPointsPerTick = maxPointsPerTick;
        this.refreshTicks = refreshTicks;
        this.asyncPrepare = asyncPrepare;
        this.jitter = jitter;
        this.sliceThickness = sliceThickness;
        this.debug = debug;
    }

    public boolean enabled() {
        return enabled;
    }

    public Particle particle() {
        return particle;
    }

    public Color dustColor() {
        return dustColor;
    }

    public double viewDistance() {
        return viewDistance;
    }

    public double baseStep() {
        return baseStep;
    }

    public double farDistanceStepMultiplier() {
        return farDistanceStepMultiplier;
    }

    public int maxPointsPerTick() {
        return maxPointsPerTick;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public boolean asyncPrepare() {
        return asyncPrepare;
    }

    public double jitter() {
        return jitter;
    }

    public double sliceThickness() {
        return sliceThickness;
    }

    public boolean debug() {
        return debug;
    }
}
