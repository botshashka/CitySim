package dev.citysim.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EconomyBreakdownFormatter {

    private EconomyBreakdownFormatter() {
    }

    public static ContributionLists buildContributionLists(EconomyBreakdown breakdown) {
        if (breakdown == null) {
            return new ContributionLists(List.of(), List.of(), false);
        }
        if (breakdown.isGhostTown()) {
            return new ContributionLists(List.of(), List.of(), true);
        }

        List<ContributionLine> positives = new ArrayList<>();
        List<ContributionLine> negatives = new ArrayList<>();

        add(positives, negatives, ContributionType.EMPLOYMENT, breakdown.employmentUtilization, false);
        add(positives, negatives, ContributionType.HOUSING, breakdown.housingBalance, false);
        add(positives, negatives, ContributionType.TRANSIT, breakdown.transitCoverage, true);
        add(positives, negatives, ContributionType.LIGHTING, breakdown.lighting, false);
        add(positives, negatives, ContributionType.NATURE, breakdown.nature, false);

        if (breakdown.pollutionPenalty > 0.0) {
            add(positives, negatives, ContributionType.POLLUTION, -breakdown.pollutionPenalty, false);
        }
        if (breakdown.overcrowdingPenalty > 0.0) {
            add(positives, negatives, ContributionType.OVERCROWDING, -breakdown.overcrowdingPenalty, false);
        }

        positives.sort(Comparator.comparingDouble(ContributionLine::value).reversed());
        negatives.sort(Comparator.comparingDouble(ContributionLine::value).reversed());

        return new ContributionLists(List.copyOf(positives), List.copyOf(negatives), false);
    }

    private static void add(List<ContributionLine> positives, List<ContributionLine> negatives,
                            ContributionType type, double value, boolean alwaysShowSign) {
        if (value == 0.0) {
            return;
        }
        ContributionLine line = new ContributionLine(type, value, alwaysShowSign);
        if (value > 0.0) {
            positives.add(line);
        } else {
            negatives.add(line);
        }
    }

    public enum ContributionType {
        EMPLOYMENT,
        HOUSING,
        TRANSIT,
        LIGHTING,
        NATURE,
        POLLUTION,
        OVERCROWDING,
        AREA_MAINTENANCE,
        LIGHTING_MAINTENANCE,
        TRANSIT_MAINTENANCE
    }

    public record ContributionLine(ContributionType type, double value, boolean alwaysShowSign) {
    }

    public record ContributionLists(List<ContributionLine> positives, List<ContributionLine> negatives, boolean ghostTown) {
    }
}
