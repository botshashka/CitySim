package dev.citysim.cmd.subcommand;

import dev.citysim.cmd.CommandMessages;
import dev.citysim.stats.BossBarService;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.ui.TitleService;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public class DisplayCommand implements CitySubcommand {

    private final TitleService titleService;
    private final BossBarService bossBarService;
    private final ScoreboardService scoreboardService;

    public DisplayCommand(TitleService titleService, BossBarService bossBarService, ScoreboardService scoreboardService) {
        this.titleService = titleService;
        this.bossBarService = bossBarService;
        this.scoreboardService = scoreboardService;
    }

    @Override
    public String name() {
        return "display";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city display titles on|off"),
                CommandMessages.help("/city display bossbar on|off"),
                CommandMessages.help("/city display scoreboard <off|compact|full>")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length < 1) {
            sendUsage(player);
            return true;
        }

        String type = args[0].toLowerCase(Locale.ROOT);
        return switch (type) {
            case "titles" -> toggle(player, args, value -> {
                titleService.setEnabled(player.getUniqueId(), value);
                player.sendMessage(value
                        ? Component.text("Enter titles enabled", NamedTextColor.GREEN)
                        : Component.text("Enter titles disabled", NamedTextColor.RED));
            }, "Usage: /city display titles on|off");
            case "bossbar" -> toggle(player, args, value -> {
                bossBarService.setEnabled(player, value);
                player.sendMessage(value
                        ? Component.text("City bossbar enabled", NamedTextColor.GREEN)
                        : Component.text("City bossbar disabled", NamedTextColor.RED));
            }, "Usage: /city display bossbar on|off");
            case "scoreboard" -> handleScoreboard(player, args);
            default -> {
                sendUsage(player);
                yield true;
            }
        };
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("titles", "bossbar", "scoreboard");
        }
        if (args.length == 2) {
            String target = args[0].toLowerCase(Locale.ROOT);
            if (target.equals("titles") || target.equals("bossbar")) {
                return List.of("on", "off");
            }
            if (target.equals("scoreboard")) {
                return List.of("off", "compact", "full");
            }
        }
        return List.of();
    }

    private boolean toggle(Player player, String[] args, java.util.function.Consumer<Boolean> toggler, String usage) {
        if (args.length < 2) {
            player.sendMessage(CommandMessages.usage(usage));
            return true;
        }
        String option = args[1].toLowerCase(Locale.ROOT);
        return switch (option) {
            case "on" -> {
                toggler.accept(true);
                yield true;
            }
            case "off" -> {
                toggler.accept(false);
                yield true;
            }
            default -> {
                player.sendMessage(CommandMessages.usage(usage));
                yield true;
            }
        };
    }

    private boolean handleScoreboard(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CommandMessages.usage("Usage: /city display scoreboard <off|compact|full>"));
            return true;
        }

        String option = args[1].toLowerCase(Locale.ROOT);
        switch (option) {
            case "off" -> {
                scoreboardService.setEnabled(player, false);
                player.sendMessage(Component.text("Scoreboard disabled", NamedTextColor.RED));
                return true;
            }
            case "compact", "full" -> {
                ScoreboardService.Mode mode = option.equals("full") ? ScoreboardService.Mode.FULL : ScoreboardService.Mode.COMPACT;
                scoreboardService.setMode(player.getUniqueId(), mode);
                scoreboardService.setEnabled(player, true);
                player.sendMessage(Component.text()
                        .append(Component.text("Scoreboard enabled (", NamedTextColor.GREEN))
                        .append(Component.text(option, NamedTextColor.GREEN))
                        .append(Component.text(" mode)", NamedTextColor.GREEN))
                        .build());
                return true;
            }
            default -> {
                player.sendMessage(CommandMessages.usage("Usage: /city display scoreboard <off|compact|full>"));
                return true;
            }
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(AdventureMessages.joinLines(
                CommandMessages.usage("Usage:"),
                CommandMessages.help("/city display titles on|off"),
                CommandMessages.help("/city display bossbar on|off"),
                CommandMessages.help("/city display scoreboard <off|compact|full>")
        ));
    }
}
