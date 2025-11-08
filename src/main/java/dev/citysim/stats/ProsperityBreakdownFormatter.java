package dev.citysim.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ProsperityBreakdownFormatter {

    private ProsperityBreakdownFormatter() {
    }

    public static ContributionLists buildContributionLists(ProsperityBreakdown breakdown) {
        if (breakdown == null) {
            return new ContributionLists(List.of(), List.of(), false);
        }
        if (breakdown.isGhostTown()) {
            return new ContributionLists(List.of(), List.of(), true);
        }
        List<ContributionLine> positives = new ArrayList<>();
        List<ContributionLine> negatives = new ArrayList<>();

        addContributionLine(positives, negatives, ContributionType.LIGHT, breakdown.lightPoints, false);
        addContributionLine(positives, negatives, ContributionType.EMPLOYMENT, breakdown.employmentPoints, false);
        addContributionLine(positives, negatives, ContributionType.NATURE, breakdown.naturePoints, false);
        addContributionLine(positives, negatives, ContributionType.HOUSING, breakdown.housingPoints, false);
        addContributionLine(positives, negatives, ContributionType.TRANSIT, breakdown.transitPoints, true);

        if (breakdown.overcrowdingPenalty > 0) {
            addContributionLine(positives, negatives, ContributionType.OVERCROWDING, -breakdown.overcrowdingPenalty, false);
        }
        if (breakdown.pollutionPenalty > 0) {
            addContributionLine(positives, negatives, ContributionType.POLLUTION, -breakdown.pollutionPenalty, false);
        }

        positives.sort(Comparator.comparingDouble(ContributionLine::value).reversed());
        negatives.sort(Comparator.comparingDouble(ContributionLine::value).reversed());

        return new ContributionLists(List.copyOf(positives), List.copyOf(negatives), false);
    }

    private static void addContributionLine(List<ContributionLine> positives, List<ContributionLine> negatives,
                                            ContributionType type, double value, boolean alwaysShowSign) {
        ContributionLine line = new ContributionLine(type, value, alwaysShowSign);
        if (value >= 0.0) {
            positives.add(line);
        } else {
            negatives.add(line);
        }
    }

    public enum ContributionType {
        LIGHT,
        EMPLOYMENT,
        NATURE,
        HOUSING,
        TRANSIT,
        OVERCROWDING,
        POLLUTION
    }

    public record ContributionLine(ContributionType type, double value, boolean alwaysShowSign) {
    }

    public record ContributionLists(List<ContributionLine> positives, List<ContributionLine> negatives, boolean ghostTown) {
    }
}
