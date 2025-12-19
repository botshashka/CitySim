package dev.citysim.cmd.subcommand;

import dev.citysim.budget.BudgetService;
import dev.citysim.budget.BudgetDefaults;
import dev.citysim.budget.BudgetSnapshot;
import dev.citysim.budget.BudgetSubsystem;
import dev.citysim.budget.SubsystemBudget;
import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CommandFeedback;
import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.EconomyBreakdown;
import dev.citysim.ui.ScoreboardService;
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
    private final ScoreboardService scoreboardService;

    public BudgetCommand(CityManager cityManager, BudgetService budgetService, ScoreboardService scoreboardService) {
        this.cityManager = cityManager;
        this.budgetService = budgetService;
        this.scoreboardService = scoreboardService;
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
            if ("austerity".equals(sub)) {
                return handleAusterity(sender, Arrays.copyOfRange(args, 1, args.length));
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
            options.add("austerity");
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
        if (args.length == 2 && "austerity".equalsIgnoreCase(args[0])) {
            return List.of("on", "off");
        }
        if (args.length == 3 && "austerity".equalsIgnoreCase(args[0])) {
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

        BudgetSnapshot snapshot = budgetService.previewCity(city, true);
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
        budgetService.applyPolicyChangeTrust(city);
        CommandFeedback.sendSuccess(sender, "Set tax rate for " + city.name + " to " + formatPercent(city.taxRate) + ".");
        refreshBudget(city);
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
        budgetService.applyPolicyChangeTrust(city);
        CommandFeedback.sendSuccess(sender, "Set land tax rate for " + city.name + " to " + formatPercent(city.landTaxRate) + ".");
        refreshBudget(city);
        return true;
    }

    private boolean handleAusterity(CommandSender sender, String[] args) {
        if (!sender.hasPermission("citysim.admin")) {
            CommandFeedback.sendNoPermission(sender);
            return true;
        }
        if (args.length < 1) {
            CommandFeedback.sendError(sender, "Usage: /city budget austerity <on|off> [cityId]");
            return true;
        }
        boolean enable;
        if ("on".equalsIgnoreCase(args[0])) {
            enable = true;
        } else if ("off".equalsIgnoreCase(args[0])) {
            enable = false;
        } else {
            CommandFeedback.sendError(sender, "Usage: /city budget austerity <on|off> [cityId]");
            return true;
        }
        String[] cityArgs = args.length >= 2 ? new String[]{args[1]} : new String[0];
        City city = resolveCity(sender, cityArgs);
        if (city == null) {
            CommandFeedback.sendError(sender, "Provide a city id or stand in a city: /city budget austerity <on|off> [cityId]");
            return true;
        }
        long nowTick = currentTick(sender);

        if (enable && city.austerityEnabled) {
            CommandFeedback.sendSuccess(sender, "Austerity for " + city.name + " is already ON.");
            return true;
        }
        if (!enable && !city.austerityEnabled) {
            CommandFeedback.sendSuccess(sender, "Austerity for " + city.name + " is already OFF.");
            return true;
        }

        if (enable) {
            long sinceDisabled = nowTick - city.austerityLastDisabledAtTick;
            long cooldownTicks = budgetService != null ? budgetService.getAusterityCooldownTicks() : 0L;
            if (city.austerityLastDisabledAtTick > 0 && sinceDisabled < cooldownTicks) {
                long remaining = cooldownTicks - sinceDisabled;
                CommandFeedback.sendError(sender, "Austerity was just disabled. Wait ~" + ticksToSeconds(remaining) + "s before enabling again.");
                return true;
            }
        } else {
            long sinceEnabled = nowTick - city.austerityEnabledAtTick;
            long minOnTicks = budgetService != null ? budgetService.getAusterityMinOnTicks() : 0L;
            if (city.austerityEnabledAtTick > 0 && sinceEnabled < minOnTicks) {
                long remaining = minOnTicks - sinceEnabled;
                CommandFeedback.sendError(sender, "Austerity must stay ON for ~" + ticksToSeconds(remaining) + "s before disabling.");
                return true;
            }
        }

        if (enable && !confirmAusterity(sender, city, args)) {
            return true;
        }

        city.austerityEnabled = enable;
        if (enable) {
            city.austerityEnabledAtTick = nowTick;
        } else {
            city.austerityLastDisabledAtTick = nowTick;
        }
        budgetService.applyPolicyChangeTrust(city);
        CommandFeedback.sendSuccess(sender, "Austerity for " + city.name + " set to " + (enable ? "ON" : "OFF") + ".");
        budgetService.invalidatePreview(city);
        refreshBudget(city);
        return true;
    }

    private boolean confirmAusterity(CommandSender sender, City city, String[] args) {
        long nowTick = currentTick(sender);
        long lastPrompt = city.austerityEnablePromptTick;
        long window = budgetService != null ? budgetService.getAusterityConfirmWindowTicks() : 3000L;
        if (nowTick > 0 && nowTick - lastPrompt <= window && city.austerityEnablePromptTick != 0L) {
            city.austerityEnablePromptTick = 0L;
            return true;
        }

        int trustBefore = city.trust;
        BudgetSnapshot snapshot = null;
        int projectedTrust = trustBefore;
        if (budgetService != null) {
            boolean prev = city.austerityEnabled;
            city.austerityEnabled = true;
            budgetService.invalidatePreview(city);
            snapshot = budgetService.previewPolicySnapshot(city);
            projectedTrust = budgetService.projectedTrustAfterPolicy(city, snapshot);
            city.austerityEnabled = prev;
        }
        double cap = BudgetDefaults.AUSTERITY_CAP;

        List<String> lines = new ArrayList<>();
        lines.add("<yellow>Austerity cuts upkeep costs and caps effects, but reduces trust immediately and blocks trust gains while active.</yellow>");
        lines.add("<white>Current trust: %d → projected: %d</white>".formatted(trustBefore, projectedTrust));
        lines.add("<white>Admin/Logistics/Public Works capped at %.0f%% while ON.</white>".formatted(cap * 100.0));
        String rerun = "/city budget austerity on" + (args != null && args.length >= 2 ? " " + args[1] : "");
        lines.add("<gray>Run %s again within ~%ds to confirm.</gray>".formatted(rerun, Math.max(1, (int) (window / 20))));
        sender.sendMessage(AdventureMessages.mini(String.join("\n", lines)));

        city.austerityEnablePromptTick = nowTick;
        return false;
    }

    private long currentTick(CommandSender sender) {
        return System.currentTimeMillis() / 50L;
    }

    private long ticksToSeconds(long ticks) {
        if (ticks <= 0) {
            return 0;
        }
        return ticks / 20L;
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

    private void refreshBudget(City city) {
        if (city == null) {
            return;
        }
        budgetService.previewCity(city, true);
        if (scoreboardService != null) {
            scoreboardService.refreshNow();
        }
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
                kv("Treasury", treasuryLabel(snapshot.treasuryBefore)),
                kv("Net", formatSignedCurrency(snapshot.net))
        ));
        lines.add(joinLine(
                kv("Trust", snapshot.trust + " (" + snapshot.trustState + ")"),
                kv("Austerity", city.austerityEnabled ? "ON" : "OFF")
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("TAXES"));
        lines.add(joinLine(
                kv("Tax", formatPercent(city.taxRate) + " (tolerated: " + formatPercent(snapshot.toleratedTax) + ")"),
                snapshot.income != null && snapshot.income.landTaxEnabled
                        ? kv("Land tax", formatPercent(city.landTaxRate) + " (tolerated: " + formatPercent(snapshot.toleratedLandTax) + ")")
                        : null
        ));
        lines.add(joinLine(
                kv("Collection", formatMultiplier(snapshot.collectionEfficiency) + " (floor " + formatMultiplier(BudgetDefaults.TRUST_COLLECTION_FLOOR) + ")")
        ));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("INCOME"));
        lines.add(kv("GDP tax", formatSignedCurrency(snapshot.income.gdpTax * snapshot.adminEffectiveMultiplier * snapshot.collectionEfficiency)));
        if (snapshot.income.landTaxEnabled) {
            lines.add(kv("Land tax", formatSignedCurrency(snapshot.income.landTax * snapshot.adminEffectiveMultiplier * snapshot.collectionEfficiency)));
        }

        lines.add(sectionSpacer());
        lines.add(sectionHeader("EXPENSES"));
        lines.add(formatExpense(snapshot.expenses.administration, false));
        lines.add(formatExpense(snapshot.expenses.logistics, true));
        lines.add(formatExpense(snapshot.expenses.publicWorks, true));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("MULTIPLIERS"));
        lines.add(joinLine(
                kv("Admin", formatMultiplier(snapshot.adminMultiplier)),
                kv("Logi", formatMultiplier(snapshot.logisticsMultiplier)),
                kv("Works", formatMultiplier(snapshot.publicWorksMultiplier))
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

        double gdpIncome = snapshot.income.gdpTax * snapshot.adminEffectiveMultiplier * snapshot.collectionEfficiency;
        double landIncome = snapshot.income.landTax * snapshot.adminEffectiveMultiplier * snapshot.collectionEfficiency;

        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<white><b>%s — Budget explain</b></white>".formatted(safeName));

        lines.add(sectionSpacer());
        lines.add(sectionHeader("CURRENCY"));
        lines.add("<white>City Credits (" + CurrencyFormatter.SYMBOL + ") are rounded to whole credits each tick.</white>");

        lines.add(sectionSpacer());
        lines.add(sectionHeader("INCOME FORMULAS"));
        lines.add("<white>GDP tax income = gdp(%s) * taxRate(%s) * adminEff(%s) * collection(%s) = %s</white>"
                .formatted(formatNumber(city.gdp), formatPercent(snapshot.income.taxRate), formatMultiplier(snapshot.adminEffectiveMultiplier), formatMultiplier(snapshot.collectionEfficiency), formatSignedCurrency(gdpIncome)));
        if (snapshot.income.landTaxEnabled) {
            lines.add("<white>Land tax income = population(%d) * landValue(%s%%) * landTaxRate(%s) * adminMultiplier(%s) = %s</white>"
                    .formatted(city.population, formatFlatNumber(city.landValue), formatPercent(city.landTaxRate), formatMultiplier(snapshot.adminEffectiveMultiplier), formatSignedCurrency(landIncome)));
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

        lines.add(sectionSpacer());
        lines.add(sectionHeader("CURRENT FUNDING"));
        lines.add(formatExpense(snapshot.expenses.administration, true));
        lines.add(formatExpense(snapshot.expenses.logistics, true));
        lines.add(formatExpense(snapshot.expenses.publicWorks, true));

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
        long percent = Math.round(budget.ratio * 100.0);
        return "<gold>%s:</gold> <white>%s (%s %d%%)</white>".formatted(
                fullLabel(budget.subsystem),
                amount,
                budget.state.name(),
                percent
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

    private String fullLabel(BudgetSubsystem subsystem) {
        return switch (subsystem) {
            case ADMINISTRATION -> "Administration";
            case LOGISTICS -> "Logistics";
            case PUBLIC_WORKS -> "Public Works";
        };
    }
}
