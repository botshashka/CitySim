package dev.citysim.selection;

import dev.citysim.cmd.subcommand.EditCityCommand;
import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.SelectionTracker.SelectionSnapshot;
import dev.citysim.visual.VisualizationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class SelectionListener implements Listener {
    public static final Material WAND = Material.GOLDEN_AXE;

    private final SelectionTracker selectionTracker;
    private final VisualizationService visualizationService;

    public SelectionListener(SelectionTracker selectionTracker,
                             VisualizationService visualizationService) {
        this.selectionTracker = selectionTracker;
        this.visualizationService = visualizationService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        if (event.getItem() == null || event.getItem().getType() != WAND) {
            return;
        }
        Action action = event.getAction();
        if (!(action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (event.getClickedBlock() == null) {
            return;
        }

        Location location = event.getClickedBlock().getLocation();
        boolean first = action == Action.LEFT_CLICK_BLOCK;
        SelectionSnapshot snapshot = selectionTracker.updateCorner(player, location, first);
        sendSelectionUpdate(player, snapshot, first ? "Pos1" : "Pos2", location);
        if (!first) {
            player.getWorld().spawnParticle(Particle.END_ROD, location.add(0.5, 1, 0.5), 12, 0.25, 0.25, 0.25, 0.001);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        visualizationService.handlePlayerQuit(event.getPlayer());
        EditCityCommand.stopShowCuboids(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        visualizationService.updateSelectionView(event.getPlayer());
    }

    private void sendSelectionUpdate(Player player,
                                     SelectionSnapshot snapshot,
                                     String label,
                                     Location location) {
        if (player == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(' ').append(format(location));
        if (snapshot != null && snapshot.ready()) {
            int width = snapshot.maxX() - snapshot.minX() + 1;
            int height = snapshot.maxY() - snapshot.minY() + 1;
            int length = snapshot.maxZ() - snapshot.minZ() + 1;
            long volume = (long) width * height * length;
            sb.append(" | Size: ")
                    .append(width).append('x')
                    .append(length).append('x')
                    .append(height)
                    .append(" (" + volume + " blocks)");
        } else {
            sb.append(" | Size: awaiting second corner");
        }
        player.sendMessage(Component.text(sb.toString(), NamedTextColor.YELLOW));
    }

    private String format(Location location) {
        if (location == null) {
            return "(?, ?, ?)";
        }
        return "(" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ() + ")";
    }
}
