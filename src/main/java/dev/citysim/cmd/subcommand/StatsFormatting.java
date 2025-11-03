package dev.citysim.cmd.subcommand;

import dev.citysim.stats.EconomyBreakdownFormatter;
import dev.citysim.stats.HappinessBreakdownFormatter;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

final class StatsFormatting {

    private StatsFormatting() {
    }

    static HappinessBreakdownFormatter.ContributionLists filterTransitIfHidden(StatsService statsService,
                                                                              HappinessBreakdownFormatter.ContributionLists lists) {
        if (lists.ghostTown()) {
            return lists;
        }
        if (statsService.getStationCountingMode() != StationCountingMode.DISABLED) {
            return lists;
        }
        List<HappinessBreakdownFormatter.ContributionLine> positives = new ArrayList<>();
        for (HappinessBreakdownFormatter.ContributionLine line : lists.positives()) {
            if (line.type() != HappinessBreakdownFormatter.ContributionType.TRANSIT) {
                positives.add(line);
            }
        }
        List<HappinessBreakdownFormatter.ContributionLine> negatives = new ArrayList<>();
        for (HappinessBreakdownFormatter.ContributionLine line : lists.negatives()) {
            if (line.type() != HappinessBreakdownFormatter.ContributionType.TRANSIT) {
                negatives.add(line);
            }
        }
        return new HappinessBreakdownFormatter.ContributionLists(List.copyOf(positives), List.copyOf(negatives), false);
    }

    static String joinHappinessContributionLines(List<HappinessBreakdownFormatter.ContributionLine> lines,
                                                 Function<HappinessBreakdownFormatter.ContributionType, String> labelProvider) {
        if (lines.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(lines.size());
        for (HappinessBreakdownFormatter.ContributionLine line : lines) {
            parts.add(formatContributionLine(line, labelProvider.apply(line.type())));
        }
        return String.join("  ", parts);
    }

    private static String formatContributionLine(HappinessBreakdownFormatter.ContributionLine line, String label) {
        String pattern = line.alwaysShowSign() ? "%+.1f" : "%.1f";
        return "%s %s".formatted(label, String.format(Locale.US, pattern, line.value()));
    }

    static String miniMessageLabelForHappiness(HappinessBreakdownFormatter.ContributionType type) {
        return switch (type) {
            case LIGHT -> "<yellow>Light:</yellow>";
            case EMPLOYMENT -> "<aqua>Employment:</aqua>";
            case NATURE -> "<green>Nature:</green>";
            case HOUSING -> "<blue>Housing:</blue>";
            case TRANSIT -> "<light_purple>Transit:</light_purple>";
            case OVERCROWDING, POLLUTION -> "<red>%s:</red>".formatted(capitalize(type.name()));
        };
    }

    static String joinEconomyContributionLines(List<EconomyBreakdownFormatter.ContributionLine> lines,
                                               Function<EconomyBreakdownFormatter.ContributionType, String> labelProvider) {
        if (lines.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(lines.size());
        for (EconomyBreakdownFormatter.ContributionLine line : lines) {
            parts.add(formatEconomyContributionLine(line, labelProvider.apply(line.type())));
        }
        return String.join("  ", parts);
    }

    private static String formatEconomyContributionLine(EconomyBreakdownFormatter.ContributionLine line, String label) {
        String pattern = line.alwaysShowSign() ? "%+.1f" : "%.1f";
        return "%s %s".formatted(label, String.format(Locale.US, pattern, line.value()));
    }

    static String miniMessageLabelForEconomy(EconomyBreakdownFormatter.ContributionType type) {
        return switch (type) {
            case EMPLOYMENT -> "<aqua>Employment:</aqua>";
            case HOUSING -> "<blue>Housing:</blue>";
            case TRANSIT -> "<light_purple>Transit:</light_purple>";
            case LIGHTING -> "<yellow>Lighting:</yellow>";
            case NATURE -> "<green>Nature:</green>";
            case POLLUTION -> "<red>Pollution:</red>";
            case OVERCROWDING -> "<red>Overcrowding:</red>";
            case AREA_MAINTENANCE -> "<gray>Area upkeep:</gray>";
            case LIGHTING_MAINTENANCE -> "<gray>Lighting upkeep:</gray>";
            case TRANSIT_MAINTENANCE -> "<gray>Transit ops:</gray>";
        };
    }

    private static String capitalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
