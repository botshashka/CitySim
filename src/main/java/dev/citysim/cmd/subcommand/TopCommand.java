package dev.citysim.cmd.subcommand;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.util.CurrencyFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import dev.citysim.util.FormatUtil;
import java.util.Locale;

public class TopCommand implements CitySubcommand {

    private final CityManager cityManager;

    public TopCommand(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @Override
    public String name() {
        return "top";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(CommandMessages.help("/city top [prosperity|pop|gdp|gdppc|landvalue|trust|budget]"));
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        Metric metric = Metric.from(args.length >= 1 ? args[0] : "prosperity");
        List<City> list = new ArrayList<>(cityManager.all());
        metric.sort(list);
        StringBuilder sb = new StringBuilder();
        sb.append("Top cities by ").append(metric.label).append(":\n");
        int limit = Math.min(10, list.size());
        for (int i = 0; i < limit; i++) {
            City city = list.get(i);
            sb.append(metric.formatLine(i + 1, city)).append('\n');
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        player.sendMessage(Component.text(sb.toString(), NamedTextColor.WHITE));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("prosperity", "pop", "gdp", "gdppc", "landvalue", "trust", "budget");
        }
        return List.of();
    }

    private enum Metric {
        PROSPERITY("prosperity") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparing(City::isGhostTown)
                        .thenComparing(Comparator.comparingInt((City c) -> c.prosperity).reversed())
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                String detail = city.isGhostTown()
                        ? "prosperity N/A • pop %d".formatted(city.population)
                        : "prosperity %d%% • pop %d".formatted(city.prosperity, city.population);
                return "%2d. %s — %s".formatted(rank, city.name, detail);
            }
        },
        POPULATION("population") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparing(City::isGhostTown)
                        .thenComparing(Comparator.comparingInt((City c) -> c.population).reversed())
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                String detail = city.isGhostTown()
                        ? "pop %d".formatted(city.population)
                        : "pop %d • prosp %d%%".formatted(city.population, city.prosperity);
                return "%2d. %s — %s".formatted(rank, city.name, detail);
            }
        },
        GDP("GDP") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparing(City::isGhostTown)
                        .thenComparing(Comparator.comparingDouble((City c) -> c.gdp).reversed())
                        .thenComparing(Comparator.comparingInt((City c) -> c.prosperity).reversed())
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                return "%2d. %s — GDP %s • pop %s • prosp %d%%"
                        .formatted(rank, city.name, FormatUtil.formatShort(city.gdp), FormatUtil.formatNumber(city.population), city.prosperity);
            }
        },
        GDP_PER_CAPITA("GDP per capita") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparing(City::isGhostTown)
                        .thenComparing(Comparator.comparingDouble((City c) -> c.gdpPerCapita).reversed())
                        .thenComparing(Comparator.comparingInt((City c) -> c.prosperity).reversed())
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                return "%2d. %s — GDP/cap %s • pop %s • prosp %d%%"
                        .formatted(rank, city.name, FormatUtil.formatShort(city.gdpPerCapita), FormatUtil.formatNumber(city.population), city.prosperity);
            }
        },
        LAND_VALUE("land value") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparing(City::isGhostTown)
                        .thenComparing(Comparator.comparingDouble((City c) -> c.landValue).reversed())
                        .thenComparing(Comparator.comparingInt((City c) -> c.prosperity).reversed())
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                return "%2d. %s — Land %s • pop %s • prosp %d%%"
                        .formatted(rank, city.name, FormatUtil.formatShort(city.landValue), FormatUtil.formatNumber(city.population), city.prosperity);
            }
        },
        TRUST("trust") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparingInt((City c) -> Math.max(0, Math.min(100, c.trust))).reversed()
                        .thenComparing(City::isGhostTown)
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                return "%2d. %s — Trust %d • prosp %d%%"
                        .formatted(rank, city.name, Math.max(0, Math.min(100, city.trust)), city.prosperity);
            }
        },
        BUDGET("budget") {
            @Override
            Comparator<City> comparator() {
                return Comparator
                        .comparingDouble((City c) -> currentTreasury(c)).reversed()
                        .thenComparing(City::isGhostTown)
                        .thenComparing(c -> c.name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            String formatLine(int rank, City city) {
                double treasury = currentTreasury(city);
                return "%2d. %s — Budget %s • prosp %d%%"
                        .formatted(rank, city.name, CurrencyFormatter.format(treasury), city.prosperity);
            }
        };

        final String label;

        Metric(String label) {
            this.label = label;
        }

        abstract Comparator<City> comparator();

        abstract String formatLine(int rank, City city);

        void sort(List<City> cities) {
            cities.sort(comparator());
        }

        static Metric from(String input) {
            if (input == null) {
                return PROSPERITY;
            }
            return switch (input.toLowerCase(Locale.ROOT)) {
                case "pop", "population" -> POPULATION;
                case "gdp" -> GDP;
                case "gdppc", "gdppercapita" -> GDP_PER_CAPITA;
                case "land", "landvalue" -> LAND_VALUE;
                case "trust" -> TRUST;
                case "budget" -> BUDGET;
                default -> PROSPERITY;
            };
        }
    }

        private static double currentTreasury(City city) {
            if (city == null) {
                return 0.0;
            }
            double live = city.treasury;
            if (Double.isFinite(live)) {
                return live;
            }
            double snapshotValue = city.lastBudgetSnapshot != null ? city.lastBudgetSnapshot.treasuryAfter : 0.0;
            return Double.isFinite(snapshotValue) ? snapshotValue : 0.0;
        }
}
