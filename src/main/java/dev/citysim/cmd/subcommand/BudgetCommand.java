package dev.citysim.cmd.subcommand;

import dev.citysim.budget.BudgetService;
import dev.citysim.budget.BudgetSnapshot;
import dev.citysim.budget.SubsystemBudget;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.util.AdventureMessages;
import dev.citysim.util.CurrencyFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public class BudgetCommand implements CitySubcommand {

    private final CityManager cityManager;
    private final BudgetService budgetService;

    public BudgetCommand(CityManager cityManager, BudgetService budgetService) {
        this.cityManager = cityManager;
        this.budgetService = budgetService;
    }

    @Override
    public String name() {
        return "budget";
    }

    @Override
    public boolean playerOnly() {
        return false;
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city budget [cityId]"),
                CommandMessages.help("/city budget explain [cityId]"),
                CommandMessages.help("/city budget set-tax <rate> [cityId]")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("set-tax".equals(sub) || "settax".equals(sub)) {
                return handleSetTax(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            if ("set-land-tax".equals(sub) || "setlandtax".equals(sub)) {
                return handleSetLandTax(sender, Arrays.copyOfRange(args, 1, args.length));
            }
            if ("explain".equals(sub)) {
                return handleExplain(sender, Arrays.copyOfRange(args, 1, args.length));
            }
        }
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }
        return handleSummary(player, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("explain");
            options.add("set-tax");
            options.add("set-land-tax");
            options.addAll(cityManager.all().stream().map(c -> c.id).toList());
            return options;
        }
        if (args.length == 2 && "explain".equalsIgnoreCase(args[0])) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        if (args.length == 2 && "set-tax".equalsIgnoreCase(args[0])) {
            return List.of("6");
        }
        if (args.length == 3 && "set-tax".equalsIgnoreCase(args[0])) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        if (args.length == 2 && "set-land-tax".equalsIgnoreCase(args[0])) {
            return List.of("5");
        }
        if (args.length == 3 && "set-land-tax".equalsIgnoreCase(args[0])) {
            return cityManager.all().stream().map(c -> c.id).collect(Collectors.toList());
        }
        return List.of();
    }

    private boolean handleSummary(Player player, String[] args) {
        City city = resolveCity(player, args);
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city budget <cityId>", NamedTextColor.RED));
            return true;
        }

        BudgetSnapshot snapshot = budgetService.previewCity(city);
        if (snapshot == null) {
            snapshot = budgetService.ensureSnapshot(city);
        }
        if (snapshot == null) {
            player.sendMessage(Component.text("Budget data not available yet.", NamedTextColor.RED));
            return true;
        }

        String message = buildSummaryMessage(city, snapshot);
        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    private boolean handleExplain(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            CommandFeedback.sendPlayersOnly(sender);
            return true;
        }
        City city = resolveCity(player, args);
        if (city == null) {
            player.sendMessage(Component.text("Stand in a city or pass /city budget explain <cityId>", NamedTextColor.RED));
            return true;
        }

        BudgetSnapshot snapshot = budgetService.ensureSnapshot(city);
        if (snapshot == null) {
            player.sendMessage(Component.text("Budget data not available yet.", NamedTextColor.RED));
            return true;
        }

        String message = buildExplainMessage(city, snapshot);
        player.sendMessage(AdventureMessages.mini(message));
        return true;
    }

    private boolean handleSetTax(CommandSender sender, String[] args) {
        if (!sender.hasPermission("citysim.admin")) {
            CommandFeedback.sendNoPermission(sender);
            return true;
        }
        if (args.length < 1) {
            CommandFeedback.sendError(sender, "Usage: /city budget set-tax <rate> [cityId]");
            return true;
        }
        double ratePercent;
        try {
            ratePercent = Double.parseDouble(args[0]);
        } catch (NumberFormatException ex) {
            CommandFeedback.sendError(sender, "Tax rate must be a number (e.g. 6 for 6%).");
            return true;
        }
        if (ratePercent < 0.0) {
            CommandFeedback.sendError(sender, "Tax rate cannot be negative.");
            return true;
        }
        double rate = ratePercent / 100.0;
        String[] cityArgs = args.length >= 2 ? new String[]{args[1]} : new String[0];
        City city = resolveCity(sender, cityArgs);
        if (city == null) {
            CommandFeedback.sendError(sender, "Provide a city id or stand in a city: /city budget set-tax <rate> [cityId]");
            return true;
        }

        budgetService.setTaxRate(city, rate);
        CommandFeedback.sendSuccess(sender, "Set tax rate for " + city.name + " to " + formatPercent(city.taxRate) + ".");
        return true;
    }

    private boolean handleSetLandTax(CommandSender sender, String[] args) {
        if (!sender.hasPermission("citysim.admin")) {
            CommandFeedback.sendNoPermission(sender);
            return true;
        }
        if (args.length < 1) {
            CommandFeedback.sendError(sender, "Usage: /city budget set-land-tax <rate> [cityId]");
            return true;
        }
        double ratePercent;
        try {
            ratePercent = Double.parseDouble(args[0]);
        } catch (NumberFormatException ex) {
            CommandFeedback.sendError(sender, "Land tax rate must be a number (e.g. 5 for 5%).");
            return true;
        }
        if (ratePercent < 0.0) {
            CommandFeedback.sendError(sender, "Land tax rate cannot be negative.");
            return true;
        }
        double rate = ratePercent / 100.0;
        String[] cityArgs = args.length >= 2 ? new String[]{args[1]} : new String[0];
        City city = resolveCity(sender, cityArgs);
        if (city == null) {
            CommandFeedback.sendError(sender, "Provide a city id or stand in a city: /city budget set-land-tax <rate> [cityId]");
            return true;
        }

        budgetService.setLandTaxRate(city, rate);
        CommandFeedback.sendSuccess(sender, "Set land tax rate for " + city.name + " to " + formatPercent(city.landTaxRate) + ".");
        return true;
    }

    private City resolveCity(CommandSender sender, String[] args) {
        City city = null;
        if (args.length >= 1) {
            city = cityManager.get(args[0]);
        }
        if (city == null && sender instanceof Player player) {
            city = cityManager.cityAt(player.getLocation());
        }
        return city;
    }

    private String buildSummaryMessage(City city, BudgetSnapshot snapshot) {
        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<white><b>%s — Budget</b></white>".formatted(safeName));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("OVERVIEW"));
        lines.add(joinLine(
                kv("City", safeName),
                kv("Treasury", treasuryLabel(snapshot.treasuryAfter)),
                kv("TaxRate", formatPercent(city.taxRate)),
                kv("Net", formatSignedCurrency(snapshot.net))
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("INCOME"));
        lines.add(kv("GDP tax", formatSignedCurrency(snapshot.income.gdpTax * snapshot.adminMultiplier)));
        if (snapshot.income.landTaxEnabled) {
            lines.add(kv("Land tax", formatSignedCurrency(snapshot.income.landTax * snapshot.adminMultiplier)));
        }

        lines.add(sectionSpacer());
        lines.add(sectionHeader("EXPENSES"));
        lines.add(formatExpense(snapshot.expenses.administration, false));
        lines.add(formatExpense(snapshot.expenses.logistics, true));
        lines.add(formatExpense(snapshot.expenses.publicWorks, true));
        lines.add(formatExpense(snapshot.expenses.landManagement, true));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("MULTIPLIERS"));
        lines.add(joinLine(
                kv("Admin", formatMultiplier(snapshot.adminMultiplier)),
                kv("Logistics", formatMultiplier(snapshot.logisticsMultiplier)),
                kv("Public Works", formatMultiplier(snapshot.publicWorksMultiplier)),
                kv("Land Mgmt", formatMultiplier(snapshot.landManagementMultiplier))
        ));

        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .stripTrailing();
    }

    private String buildExplainMessage(City city, BudgetSnapshot snapshot) {
        String safeName = AdventureMessages.escapeMiniMessage(city.name);
        EconomyBreakdown breakdown = city.economyBreakdown;
        double maintenanceTransit = breakdown != null ? breakdown.maintenanceTransit : 0.0;
        double maintenanceLighting = breakdown != null ? breakdown.maintenanceLighting : 0.0;
        double maintenanceArea = breakdown != null ? breakdown.maintenanceArea : 0.0;

        double gdpIncome = snapshot.income.gdpTax * snapshot.adminMultiplier;
        double landIncome = snapshot.income.landTax * snapshot.adminMultiplier;

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<white><b>%s — Budget explain</b></white>".formatted(safeName));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("CURRENCY"));
        lines.add("<white>City Credits (" + CurrencyFormatter.SYMBOL + ") are rounded to whole credits each tick.</white>");

        lines.add(sectionSpacer());
        lines.add(sectionHeader("INCOME FORMULAS"));
        lines.add("<white>GDP tax income = gdp(%s) * taxRate(%s) * adminMultiplier(%s) = %s</white>"
                .formatted(formatNumber(city.gdp), formatPercent(snapshot.income.taxRate), formatMultiplier(snapshot.adminMultiplier), formatSignedCurrency(gdpIncome)));
        if (snapshot.income.landTaxEnabled) {
            lines.add("<white>Land tax income = population(%d) * landValue(%s%%) * landTaxRate(%s) * adminMultiplier(%s) = %s</white>"
                    .formatted(city.population, formatFlatNumber(city.landValue), formatPercent(city.landTaxRate), formatMultiplier(snapshot.adminMultiplier), formatSignedCurrency(landIncome)));
        } else {
            lines.add("<white>Land tax is disabled in config.</white>");
        }

        lines.add(sectionSpacer());
        lines.add(sectionHeader("UPKEEP FORMULAS"));
        lines.add("<white>Administration upkeep = population(%d) * ADMIN_PER_CAPITA(%s) = %s</white>"
                .formatted(city.population, formatFlatNumber(budgetService.getAdminPerCapita()), CurrencyFormatter.format(snapshot.expenses.administration.required)));
        lines.add("<white>Logistics upkeep = maintenanceTransit(%s) * TRANSIT_COST(%s) = %s</white>"
                .formatted(formatFlatNumber(maintenanceTransit), formatFlatNumber(budgetService.getTransitCost()), CurrencyFormatter.format(snapshot.expenses.logistics.required)));
        lines.add("<white>Public Works upkeep = maintenanceLighting(%s) * LIGHTING_COST(%s) = %s</white>"
                .formatted(formatFlatNumber(maintenanceLighting), formatFlatNumber(budgetService.getLightingCost()), CurrencyFormatter.format(snapshot.expenses.publicWorks.required)));
        lines.add("<white>Land Management upkeep = maintenanceArea(%s) * AREA_COST(%s) = %s</white>"
                .formatted(formatFlatNumber(maintenanceArea), formatFlatNumber(budgetService.getAreaCost()), CurrencyFormatter.format(snapshot.expenses.landManagement.required)));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("CURRENT FUNDING"));
        lines.add(formatExpense(snapshot.expenses.administration, true));
        lines.add(formatExpense(snapshot.expenses.logistics, true));
        lines.add(formatExpense(snapshot.expenses.publicWorks, true));
        lines.add(formatExpense(snapshot.expenses.landManagement, true));

        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .stripTrailing();
    }

    private String formatExpense(SubsystemBudget budget, boolean includeMultiplier) {
        if (budget == null) {
            return null;
        }
        String amount = CurrencyFormatter.format(-budget.paid);
        String multiplier = includeMultiplier ? ", mult " + formatMultiplier(budget.multiplier) : "";
        long percent = Math.round(budget.ratio * 100.0);
        return "<gold>%s:</gold> <white>%s (%s %d%%%s)</white>".formatted(
                budget.subsystem.displayName(),
                amount,
                budget.state.name(),
                percent,
                multiplier
        );
    }

    private String treasuryLabel(double treasury) {
        String base = CurrencyFormatter.format(treasury);
        if (treasury < 0.0) {
            base += " <red><b>IN DEBT</b></red>";
        }
        return base;
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

    private String formatPercent(double rate) {
        return String.format(Locale.US, "%.2f%%", rate * 100.0);
    }

    private String formatMultiplier(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatNumber(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    private String formatFlatNumber(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatSignedCurrency(double amount) {
        String formatted = CurrencyFormatter.format(amount);
        if (amount > 0) {
            return "+" + formatted;
        }
        return formatted;
    }
}
