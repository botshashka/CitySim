package dev.citysim.selection;

import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.SelectionTracker.Selection;
import dev.citysim.visual.VisualizationService;
import dev.citysim.visual.YMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class SelectionListener implements Listener {

    public static final Material WAND = Material.GOLDEN_AXE;

    private final SelectionTracker selectionTracker;
    private final VisualizationService visualizationService;

    public SelectionListener(SelectionTracker selectionTracker, VisualizationService visualizationService) {
        this.selectionTracker = selectionTracker;
        this.visualizationService = visualizationService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null || event.getItem().getType() != WAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        Player player = event.getPlayer();
        Selection selection = selectionTracker.get(player);
        if (selection.world() != null && selection.world() != block.getWorld()) {
            player.sendMessage(Component.text()
                    .append(Component.text("Selection is per-world. Clear or continue in ", NamedTextColor.RED))
                    .append(Component.text(selection.world().getName(), NamedTextColor.RED))
                    .build());
            return;
        }

        boolean first = action == Action.LEFT_CLICK_BLOCK;
        selectionTracker.setCorner(player, first, block.getLocation());
        sendSelectionUpdate(player, selectionTracker.get(player), first ? "Pos1" : "Pos2");
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        selectionTracker.clear(player);
        visualizationService.disableAllCityViews(player);
    }

    private void sendSelectionUpdate(Player player, Selection selection, String label) {
        if (selection == null) {
            return;
        }
        TextComponent.Builder message = Component.text();
        if (selection.pos1() != null || selection.pos2() != null) {
            message.append(Component.text(label + " " + format(selection, label), NamedTextColor.YELLOW));
        }
        if (selection.pos1() != null && selection.pos2() != null) {
            int minX = Math.min(selection.pos1().getBlockX(), selection.pos2().getBlockX());
            int maxX = Math.max(selection.pos1().getBlockX(), selection.pos2().getBlockX());
            int minY = Math.min(selection.pos1().getBlockY(), selection.pos2().getBlockY());
            int maxY = Math.max(selection.pos1().getBlockY(), selection.pos2().getBlockY());
            int minZ = Math.min(selection.pos1().getBlockZ(), selection.pos2().getBlockZ());
            int maxZ = Math.max(selection.pos1().getBlockZ(), selection.pos2().getBlockZ());
            int width = maxX - minX + 1;
            int length = maxZ - minZ + 1;
            int height = maxY - minY + 1;
            long volume = (long) width * length * height;
            message.append(Component.text(" | Size: ", NamedTextColor.YELLOW))
                    .append(Component.text(width + "x" + length + "x" + height, NamedTextColor.WHITE))
                    .append(Component.text(" (" + volume + " blocks)", NamedTextColor.YELLOW));
            if (selection.mode() == YMode.FULL) {
                message.append(Component.text(" | Y-mode: full", NamedTextColor.GRAY));
            } else {
                message.append(Component.text(" | Y-mode: span", NamedTextColor.GRAY));
            }
        } else {
            message.append(Component.text(" | Size: awaiting second corner", NamedTextColor.YELLOW));
        }
        player.sendMessage(message.build());
    }

    private String format(Selection selection, String label) {
        if ("Pos1".equalsIgnoreCase(label) && selection.pos1() != null) {
            return "(" + selection.pos1().getBlockX() + "," + selection.pos1().getBlockY() + "," + selection.pos1().getBlockZ() + ")";
        }
        if ("Pos2".equalsIgnoreCase(label) && selection.pos2() != null) {
            return "(" + selection.pos2().getBlockX() + "," + selection.pos2().getBlockY() + "," + selection.pos2().getBlockZ() + ")";
        }
        if (selection.pos1() != null) {
            return "(" + selection.pos1().getBlockX() + "," + selection.pos1().getBlockY() + "," + selection.pos1().getBlockZ() + ")";
        }
        if (selection.pos2() != null) {
            return "(" + selection.pos2().getBlockX() + "," + selection.pos2().getBlockY() + "," + selection.pos2().getBlockZ() + ")";
        }
        return "(unset)";
    }
}
