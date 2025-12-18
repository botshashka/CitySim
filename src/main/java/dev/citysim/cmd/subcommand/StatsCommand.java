package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.links.CityLink;
import dev.citysim.links.LinkService;
import dev.citysim.migration.MigrationService;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.stats.ProsperityBreakdown;
import dev.citysim.stats.ProsperityBreakdownFormatter;
import dev.citysim.stats.ProsperityBreakdownFormatter.ContributionLine;
import dev.citysim.stats.ProsperityBreakdownFormatter.ContributionType;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.stats.StatsService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public class StatsCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final StatsService statsService;
    private final LinkService linkService;
    private final MigrationService migrationService;

    public StatsCommand(CityManager cityManager,
                        StatsService statsService,
                        LinkService linkService,
                        MigrationService migrationService) {
        this.cityManager = cityManager;
        this.statsService = statsService;
        this.linkService = linkService;
        this.migrationService = migrationService;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public Collection<String> aliases() {
        return List.of("info");
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city stats [cityId]"),
                CommandMessages.help("/city info [cityId]")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        City city = null;
        if (args.length >= 1) {
            city = cityManager.get(args[0]);
        }
        if (city == null) {
            city = cityManager.cityAt(player.getLocation());
        }
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city stats <cityId>", NamedTextColor.RED));
            return true;
        }

        ProsperityBreakdown prosperity = statsService.computeProsperityBreakdown(city);
        prosperity.setGhostTown(prosperity.isGhostTown() || city.isGhostTown() || city.population <= 0);

        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        boolean showStations = statsService.getStationCountingMode() != StationCountingMode.DISABLED;

        if (prosperity.isGhostTown()) {
            player.sendMessage(AdventureMessages.mini(buildGhostTownMessage(city, safeName, showStations, prosperity)));
            return true;
        }

        EconomyBreakdown economyBreakdown = city.economyBreakdown;
        int prosperityTotal = economyBreakdown != null ? economyBreakdown.total : prosperity.total;
        int prosperityBase = economyBreakdown != null ? economyBreakdown.base : prosperity.base;

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<white><b>%s — City stats</b></white>".formatted(safeName));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("OVERVIEW"));
        lines.add(joinLine(
                kv("City", safeName),
                kv("Population", formatNumber(city.population)),
                kv("Prosperity", prosperityString(prosperityTotal, prosperityBase)),
                formatLandValue(city),
                city.highrise ? kv("Highrise", "Yes") : null
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("PROSPERITY POINTS"));
        if (economyBreakdown != null) {
            lines.add("<white><b>General</b></white>");
            lines.add(joinLine(
                    kv("Employment", formatPointsWithCap(economyBreakdown.employmentUtilization, economyBreakdown.employmentMaxPts, economyBreakdown.employmentNeutral)),
                    kv("Housing", formatPointsWithCap(economyBreakdown.housingBalance, economyBreakdown.housingMaxPts, 1.0))
            ));
            lines.add(joinLine(
                    kv("Transit", formatPointsWithCap(economyBreakdown.transitCoverage, economyBreakdown.transitMaxPts, economyBreakdown.transitNeutral))
            ));
            lines.add("<white><b>Environment</b></white>");
            lines.add(joinLine(
                    kv("Lighting", formatPointsWithCap(economyBreakdown.lighting, economyBreakdown.lightingMaxPts, economyBreakdown.lightNeutral)),
                    kv("Nature", formatPointsWithCap(economyBreakdown.nature, economyBreakdown.natureMaxPts, economyBreakdown.natureTargetRatio))
            ));
            lines.add(joinLine(
                    kv("Pollution", formatPenaltyWithCap(economyBreakdown.pollutionPenalty, economyBreakdown.pollutionMaxPenalty, economyBreakdown.pollutionTargetRatio)),
                    kv("Crowding", formatPenaltyWithCap(economyBreakdown.overcrowdingPenalty, economyBreakdown.overcrowdingMaxPenalty, null))
            ));
        }

        lines.add(sectionSpacer());
        lines.add(sectionHeader("MIGRATION PRESSURES Δ"));
        lines.add(joinLine(
                kv("JobsΔ", formatSigned(city.jobsPressure)),
                kv("HousingΔ", formatSigned(city.housingPressure)),
                kv("TransitΔ", formatSigned(city.transitPressure))
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("ECONOMY"));
        lines.add(joinLine(
                kv("GDP", formatShortNumber(city.gdp)),
                kv("GDP per capita", formatShortNumber(city.gdpPerCapita))
        ));
        lines.add(joinLine(
                kv("Employment rate", formatPercentValue(city.employmentRate)),
                kv("Unemployed adults", formatUnemployedAdults(city.unemployed, city.adultNitwit))
        ));
        lines.add(joinLine(
                kv("Sectors", formatSectorBreakdown(city))
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("CONNECTIVITY"));
        lines.addAll(buildConnectivityLines(city, economyBreakdown));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("INFRASTRUCTURE"));
        lines.add(joinLine(
                kv("Homes", "%d/%d".formatted(city.beds, city.population)),
                kv("Stations", String.valueOf(city.stations))
        ));
        lines.add(joinLine(
                kv("Area", formatArea(city))
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("GOVERNANCE"));
        lines.add(joinLine(
                formatMayorEntry(city)
        ));

        String message = lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .stripTrailing();
        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        return List.of();
    }

    private String buildGhostTownMessage(City city, String safeName, boolean showStations, ProsperityBreakdown prosperity) {
        List<String> lines = new ArrayList<>();
        lines.add("<white><b>%s — City stats</b></white>".formatted(safeName));
        lines.add(sectionSpacer());
        lines.add(sectionHeader("OVERVIEW"));
        lines.add(joinLine(
                kv("City", safeName),
                kv("Pop", formatNumber(city.population)),
                kv("Prosperity", "N/A (ghost town)")
        ));
        lines.add(sectionSpacer());
        lines.add(sectionHeader("POPULATION"));
        lines.add(joinLine(
                kv("Employed", formatNumber(city.employed)),
                kv("Unemployed", formatNumber(city.unemployed))
        ));
        lines.add(sectionSpacer());
        lines.add(sectionHeader("HOUSING"));
        lines.add(joinLine(
                kv("Homes", "%d/%d".formatted(city.beds, city.population)),
                showStations ? kv("Stations", String.valueOf(city.stations)) : null
        ));
        lines.add(sectionSpacer());
        lines.add(sectionHeader("GOVERNANCE"));
        lines.add(formatMayorEntry(city));
        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .stripTrailing();
    }

    private String sectionHeader(String label) {
        return "<yellow><b>%s</b></yellow>".formatted(label);
    }

    private String sectionSpacer() {
        return "<gray>────────────</gray>";
    }

    private String kv(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "<gold>%s:</gold> <white>%s</white>".formatted(label, value);
    }

    private String joinLine(String... parts) {
        return Arrays.stream(parts)
                .filter(Objects::nonNull)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" <gray>•</gray> "));
    }

    private String formatNumber(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private String formatUnemployedAdults(int unemployedAdults, int nitwits) {
        String base = formatNumber(unemployedAdults);
        if (nitwits > 0) {
            base += " (" + formatNumber(nitwits) + " nitwits)";
        }
        return base;
    }

    private String formatShortNumber(double raw) {
        if (!Double.isFinite(raw) || raw == 0.0) {
            return "0";
        }
        double value = raw;
        double abs = Math.abs(value);
        String[] suffixes = {"", "K", "M", "B", "T"};
        int index = 0;
        while (abs >= 1000.0 && index < suffixes.length - 1) {
            value /= 1000.0;
            abs /= 1000.0;
            index++;
        }
        if (index == 0) {
            return String.format(Locale.US, "%,.0f", value);
        }
        return String.format(Locale.US, "%,.1f%s", value, suffixes[index]);
    }

    private String prosperityString(int total, int base) {
        return "%d%% (base %d)".formatted(total, base);
    }

    private ContributionSummary buildContributionSummary(City city) {
        List<NamedContribution> positives = new ArrayList<>();
        List<NamedContribution> negatives = new ArrayList<>();
        EconomyBreakdown economy = city.economyBreakdown;
        if (economy != null) {
            addContribution(positives, negatives, "Employment", economy.employmentUtilization);
            addContribution(positives, negatives, "Housing", economy.housingBalance);
            addContribution(positives, negatives, "Transit", economy.transitCoverage);
            addContribution(positives, negatives, "Lighting", economy.lighting);
            addContribution(positives, negatives, "Nature", economy.nature);
            addContribution(positives, negatives, "Pollution", -Math.abs(economy.pollutionPenalty));
            addContribution(positives, negatives, "Crowding", -Math.abs(economy.overcrowdingPenalty));
        } else {
            boolean showTransit = statsService.getStationCountingMode() != StationCountingMode.DISABLED;
            ProsperityBreakdown breakdown = city.prosperityBreakdown;
            if (breakdown != null) {
                for (ContributionLine line : ProsperityBreakdownFormatter.buildContributionLists(breakdown).positives()) {
                    if (!showTransit && line.type() == ProsperityBreakdownFormatter.ContributionType.TRANSIT) {
                        continue;
                    }
                    addContribution(positives, negatives, labelFor(line.type()), line.value());
                }
                for (ContributionLine line : ProsperityBreakdownFormatter.buildContributionLists(breakdown).negatives()) {
                    if (!showTransit && line.type() == ProsperityBreakdownFormatter.ContributionType.TRANSIT) {
                        continue;
                    }
                    addContribution(positives, negatives, labelFor(line.type()), line.value());
                }
            }
        }

        positives.sort(Comparator.comparingDouble(NamedContribution::value).reversed());
        negatives.sort(Comparator.comparingDouble(NamedContribution::value));
        return new ContributionSummary(positives, negatives);
    }

    private String labelFor(ProsperityBreakdownFormatter.ContributionType type) {
        return switch (type) {
            case LIGHT -> "Lighting";
            case EMPLOYMENT -> "Employment";
            case NATURE -> "Nature";
            case HOUSING -> "Housing";
            case TRANSIT -> "Transit";
            case OVERCROWDING -> "Crowding";
            case POLLUTION -> "Pollution";
        };
    }

    private void addContribution(List<NamedContribution> positives,
                                 List<NamedContribution> negatives,
                                 String label,
                                 double value) {
        if (!Double.isFinite(value) || label == null || label.isBlank()) {
            return;
        }
        NamedContribution entry = new NamedContribution(label, value);
        if (value >= 0.0) {
            positives.add(entry);
        } else {
            negatives.add(entry);
        }
    }

    private String formatContributionEntry(List<NamedContribution> contributions, int index) {
        if (index >= contributions.size()) {
            return "—";
        }
        NamedContribution entry = contributions.get(index);
        if (Math.abs(entry.value()) < 0.05) {
            return "—";
        }
        return entry.label() + " " + formatContributionValue(entry.value());
    }

    private String formatContributionValue(double value) {
        return String.format(Locale.US, "%+.1f", value);
    }

    private String formatPointsWithCap(double value, double cap, double target) {
        if (!Double.isFinite(value) || !Double.isFinite(cap)) {
            return "—";
        }
        return "%s / %s".formatted(formatSigned(value), formatSigned(cap));
    }

    private String formatPenaltyWithCap(double penaltyValue, double cap, Double target) {
        if (!Double.isFinite(penaltyValue) || !Double.isFinite(cap)) {
            return "—";
        }
        double signedValue = -Math.abs(penaltyValue);
        double signedCap = -Math.abs(cap);
        return "%s / %s".formatted(formatSigned(signedValue), formatSigned(signedCap));
    }

    private List<String> buildConnectivityLines(City city, EconomyBreakdown economyBreakdown) {
        List<String> lines = new ArrayList<>();
        String linksValue = "0";
        String topLinkValue = "—";
        if (linkService != null) {
            int count = Math.max(0, linkService.linkCount(city));
            linksValue = String.valueOf(count);
            List<CityLink> topLinks = linkService.topLinks(city, 1);
            if (!topLinks.isEmpty()) {
                CityLink top = topLinks.get(0);
                String neighbor = top.neighbor() != null ? top.neighbor().name : "?";
                topLinkValue = "%s (S=%d)".formatted(neighbor, top.strength());
            }
        }
        lines.add(joinLine(
                kv("Links", linksValue),
                kv("Top link", topLinkValue)
        ));
        if (economyBreakdown != null) {
            lines.add(joinLine(
                    kv("Transit score", formatSigned(economyBreakdown.transitCoverage))
            ));
        }

        String migrationValue = "0";
        if (migrationService != null) {
            MigrationService.CityMigrationSnapshot snapshot = migrationService.snapshot(city);
            if (snapshot != null) {
                long net = snapshot.net();
                migrationValue = net > 0 ? "+" + net : String.valueOf(net);
            }
        }
        lines.add(joinLine(
                kv("Migration", migrationValue)
        ));
        return lines;
    }

    private String formatSigned(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.US, "%+.2f", value);
    }

    private String formatRatio(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatSectorBreakdown(City city) {
        String services = formatPercentValue(city.sectorServ);
        String industry = formatPercentValue(city.sectorInd);
        String agriculture = formatPercentValue(city.sectorAgri);
        return "Serv %s / Ind %s / Agri %s".formatted(services, industry, agriculture);
    }

    private String formatPercentValue(double value) {
        return String.format(Locale.US, "%.0f%%", clamp01(value) * 100.0);
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String formatLandValue(City city) {
        if (!Double.isFinite(city.landValue)) {
            return null;
        }
        return kv("Land value", String.format(Locale.US, "%.0f", city.landValue));
    }

    private String formatArea(City city) {
        long area = computeArea(city);
        if (area <= 0) {
            return "—";
        }
        return formatShortNumber(area) + " m²";
    }

    private long computeArea(City city) {
        if (city == null || city.cuboids == null) {
            return 0;
        }
        long total = 0;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null) {
                continue;
            }
            long width = Math.abs(cuboid.maxX - cuboid.minX) + 1L;
            long depth = Math.abs(cuboid.maxZ - cuboid.minZ) + 1L;
            total += width * depth;
        }
        return total;
    }

    private String formatMayorEntry(City city) {
        List<String> names = resolveMayorNames(city);
        if (names.isEmpty()) {
            return kv("Mayor", "None");
        }
        String label = names.size() == 1 ? "Mayor" : "Mayors";
        return kv(label, String.join(", ", names));
    }

    private List<String> resolveMayorNames(City city) {
        List<String> formatted = new ArrayList<>();
        if (city == null || city.mayors == null) {
            return formatted;
        }
        for (String raw : city.mayors) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String display = resolveMayorDisplay(raw);
            if (display == null || display.isBlank()) {
                continue;
            }
            formatted.add(AdventureMessages.escapeMiniMessage(display));
        }
        return formatted;
    }

    private String resolveMayorDisplay(String raw) {
        try {
            UUID uuid = UUID.fromString(raw);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            if (offline != null) {
                String name = offline.getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
            return uuid.toString();
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private record NamedContribution(String label, double value) {
    }

    private record ContributionSummary(List<NamedContribution> positives, List<NamedContribution> negatives) {
        List<NamedContribution> all() {
            List<NamedContribution> combined = new ArrayList<>(positives);
            combined.addAll(negatives);
            combined.sort(Comparator.comparingDouble(NamedContribution::value).reversed());
            return combined;
        }
    }
}
