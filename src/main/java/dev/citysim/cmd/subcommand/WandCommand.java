package dev.citysim.cmd.subcommand;

import dev.citysim.cmd.CommandMessages;
import dev.citysim.selection.SelectionListener;
import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.YMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

public class WandCommand implements CitySubcommand {

    private final SelectionTracker tracker;

    public WandCommand(SelectionTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public String name() {
        return "wand";
    }

    @Override
    public String permission() {
        return "citysim.admin";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public List<Component> help() {
        return List.of(
                CommandMessages.help("/city wand [clear]"),
                CommandMessages.help("/city wand ymode <full|span>")
        );
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        if (args.length >= 1) {
            String option = args[0].toLowerCase(Locale.ROOT);
            if (option.equals("clear")) {
                tracker.clear(player);
                player.sendMessage(Component.text("Selection cleared.", NamedTextColor.GREEN));
                return true;
            }
            if (option.equals("ymode")) {
                return handleYMode(player, args);
            }
        }

        ItemStack wand = new ItemStack(SelectionListener.WAND);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("CitySim Wand", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Left click: set corner 1", NamedTextColor.YELLOW),
                Component.text("Right click: set corner 2", NamedTextColor.YELLOW)
        ));
        wand.setItemMeta(meta);

        player.getInventory().addItem(wand);
        player.sendMessage(Component.text("CitySim wand given. Left/right click blocks to set the selection.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("clear", "ymode");
        }
        if (args.length == 2 && "ymode".equalsIgnoreCase(args[0])) {
            return List.of("full", "span");
        }
        return List.of();
    }

    private boolean handleYMode(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CommandMessages.usage("Usage: /city wand ymode <full|span>"));
            return true;
        }

        String modeArg = args[1].toLowerCase(Locale.ROOT);
        switch (modeArg) {
            case "full" -> {
                tracker.setMode(player, YMode.FULL);
                player.sendMessage(Component.text("Y-mode set to full.", NamedTextColor.WHITE));
            }
            case "span" -> {
                tracker.setMode(player, YMode.SPAN);
                player.sendMessage(Component.text("Y-mode set to span.", NamedTextColor.WHITE));
            }
            default -> player.sendMessage(Component.text("Unknown mode. Use full or span.", NamedTextColor.RED));
        }
        return true;
    }
}
