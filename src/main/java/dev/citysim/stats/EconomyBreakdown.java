package dev.citysim.stats;

public class EconomyBreakdown {

    public int base = 50;

    public double employmentUtilization;
    public double housingBalance;
    public double transitCoverage;
    public double lighting;
    public double nature;

    public double pollutionPenalty;
    public double overcrowdingPenalty;

    public double employmentMaxPts;
    public double housingMaxPts;
    public double transitMaxPts;
    public double lightingMaxPts;
    public double natureMaxPts;

    public double pollutionMaxPenalty;
    public double overcrowdingMaxPenalty;

    public double employmentNeutral;
    public double transitNeutral;
    public double lightNeutral;
    public double natureTargetRatio;
    public double pollutionTargetRatio;

    public double maintenanceArea;
    public double maintenanceLighting;
    public double maintenanceTransit;

    public int total;

    private boolean ghostTown;

    public boolean isGhostTown() {
        return ghostTown;
    }

    public void setGhostTown(boolean ghostTown) {
        this.ghostTown = ghostTown;
    }
}
