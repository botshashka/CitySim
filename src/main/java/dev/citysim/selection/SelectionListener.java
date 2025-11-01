package dev.citysim.selection;

import dev.citysim.cmd.subcommand.EditCityCommand;
import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.YMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SelectionListener implements Listener {
    public static final Material WAND = Material.GOLDEN_AXE;

    private final SelectionTracker tracker;

    public SelectionListener(SelectionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
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
        Location blockLocation = event.getClickedBlock().getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            tracker.handleCorner(player, blockLocation, SelectionTracker.Corner.POS1);
            sendSelectionUpdate(player, "Pos1", blockLocation);
        } else {
            tracker.handleCorner(player, blockLocation, SelectionTracker.Corner.POS2);
            sendSelectionUpdate(player, "Pos2", blockLocation);
            blockLocation.getWorld().spawnParticle(Particle.END_ROD, blockLocation.add(0.5, 1, 0.5), 12, 0.25, 0.25, 0.25, 0.001);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tracker.handlePlayerQuit(event.getPlayer());
        EditCityCommand.stopShowCuboids(event.getPlayer());
    }

    private void sendSelectionUpdate(Player player, String label, Location location) {
        SelectionTracker.SelectionState state = tracker.stateFor(player);
        Component base = Component.text()
                .append(Component.text(label + " " + format(location), NamedTextColor.YELLOW))
                .append(Component.text(" | ", NamedTextColor.GOLD))
                .build();
        Component message;
        if (state.pos1() != null && state.pos2() != null) {
            SelectionTracker.SelectionSnapshot snapshot = state.snapshot();
            if (snapshot != null) {
                int width = snapshot.maxX() - snapshot.minX() + 1;
                int length = snapshot.maxZ() - snapshot.minZ() + 1;
                int height = snapshot.maxY() - snapshot.minY() + 1;
                String mode = snapshot.mode() == YMode.FULL ? "full" : "span";
                message = base.append(Component.text("Size: " + width + "x" + length + "x" + height + " (mode: " + mode + ")", NamedTextColor.YELLOW));
            } else {
                message = base.append(Component.text("Size: awaiting selection", NamedTextColor.YELLOW));
            }
        } else {
            message = base.append(Component.text("Size: awaiting second corner", NamedTextColor.YELLOW));
        }
        player.sendMessage(message);
    }

    private String format(Location location) {
        return "(" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ() + ")";
    }
}
