package dev.citysim.visual;

public class SamplingContext {
    private final double playerX;
    private final double playerY;
    private final double playerZ;
    private final double viewDistance;
    private final double baseStep;
    private final double farDistanceMultiplier;
    private final int maxPointsPerTick;
    private final double jitterAmount;
    private final double sliceThickness;

    public SamplingContext(double playerX,
                           double playerY,
                           double playerZ,
                           double viewDistance,
                           double baseStep,
                           double farDistanceMultiplier,
                           int maxPointsPerTick,
                           double jitterAmount,
                           double sliceThickness) {
        this.playerX = playerX;
        this.playerY = playerY;
        this.playerZ = playerZ;
        this.viewDistance = viewDistance;
        this.baseStep = baseStep;
        this.farDistanceMultiplier = farDistanceMultiplier;
        this.maxPointsPerTick = maxPointsPerTick;
        this.jitterAmount = jitterAmount;
        this.sliceThickness = sliceThickness;
    }

    public double playerX() {
        return playerX;
    }

    public double playerY() {
        return playerY;
    }

    public double playerZ() {
        return playerZ;
    }

    public double viewDistance() {
        return viewDistance;
    }

    public double baseStep() {
        return baseStep;
    }

    public double farDistanceMultiplier() {
        return farDistanceMultiplier;
    }

    public int maxPointsPerTick() {
        return maxPointsPerTick;
    }

    public double jitterAmount() {
        return jitterAmount;
    }

    public double sliceThickness() {
        return sliceThickness;
    }
}
