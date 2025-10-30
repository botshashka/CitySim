package dev.citysim.cmd.subcommand;

import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLine;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionLists;
import dev.citysim.stats.HappinessBreakdownFormatter.ContributionType;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class StatsFormatting {

    private StatsFormatting() {
    }

    static ContributionLists filterTransitIfHidden(StatsService statsService, ContributionLists lists) {
        if (statsService.getStationCountingMode() != StationCountingMode.DISABLED) {
            return lists;
        }
        List<ContributionLine> positives = new ArrayList<>();
        for (ContributionLine line : lists.positives()) {
            if (line.type() != ContributionType.TRANSIT) {
                positives.add(line);
            }
        }
        List<ContributionLine> negatives = new ArrayList<>();
        for (ContributionLine line : lists.negatives()) {
            if (line.type() != ContributionType.TRANSIT) {
                negatives.add(line);
            }
        }
        return new ContributionLists(List.copyOf(positives), List.copyOf(negatives));
    }

    static String joinContributionLines(List<ContributionLine> lines, Function<ContributionType, String> labelProvider) {
        if (lines.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(lines.size());
        for (ContributionLine line : lines) {
            parts.add(formatContributionLine(line, labelProvider.apply(line.type())));
        }
        return String.join("  ", parts);
    }

    private static String formatContributionLine(ContributionLine line, String label) {
        String pattern = line.alwaysShowSign() ? "%+.1f" : "%.1f";
        return "%s %s".formatted(label, String.format(Locale.US, pattern, line.value()));
    }

    static String miniMessageLabelFor(ContributionType type) {
        return switch (type) {
            case LIGHT -> "<yellow>Light:</yellow>";
            case EMPLOYMENT -> "<aqua>Employment:</aqua>";
            case NATURE -> "<green>Nature:</green>";
            case HOUSING -> "<blue>Housing:</blue>";
            case TRANSIT -> "<light_purple>Transit:</light_purple>";
            case OVERCROWDING, POLLUTION -> "<red>%s:</red>".formatted(capitalize(type.name()));
        };
    }

    private static String capitalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
