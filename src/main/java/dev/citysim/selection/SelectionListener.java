package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionListener implements Listener {
    public static final Material WAND = Material.GOLDEN_AXE;
    public static final Map<UUID, SelectionState> selections = new ConcurrentHashMap<>();
    private static final long PREVIEW_TASK_PERIOD_TICKS = 10L;

    private final JavaPlugin plugin;

    public SelectionListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static SelectionState get(Player p) {
        return selections.computeIfAbsent(p.getUniqueId(), k -> new SelectionState());
    }

    public static void clear(Player player) {
        SelectionState state = selections.remove(player.getUniqueId());
        if (state != null) {
            state.reset();
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getItem() == null || e.getItem().getType() != WAND) return;
        if (!(e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        var block = e.getClickedBlock();
        if (block == null) return;

        SelectionState sel = get(p);
        if (sel.world == null) sel.world = block.getWorld();
        if (sel.world != block.getWorld()) {
            p.sendMessage(Component.text()
                    .append(Component.text("Selection is per-world. Clear or continue in ", NamedTextColor.RED))
                    .append(Component.text(sel.world.getName(), NamedTextColor.RED))
                    .build());
            return;
        }

        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel.pos1 = block.getLocation();
            sendSelectionUpdate(p, sel, "Pos1", sel.pos1);
        } else {
            sel.pos2 = block.getLocation();
            sendSelectionUpdate(p, sel, "Pos2", sel.pos2);
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.4, 0.4, 0.01);
        }

        updateSelectionPreview(p, sel);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    private void sendSelectionUpdate(Player player, SelectionState sel, String label, Location location) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(' ').append(fmt(location));
        if (sel.pos1 != null && sel.pos2 != null) {
            SelectionBounds bounds = SelectionBounds.from(sel);
            sb.append(" | Size: ")
              .append(bounds.width()).append('x')
              .append(bounds.length()).append('x')
              .append(bounds.height());
            long volume = bounds.volume();
            sb.append(" (" + volume + " blocks)");
        } else {
            sb.append(" | Size: awaiting second corner");
        }
        player.sendActionBar(Component.text(sb.toString(), NamedTextColor.YELLOW));
    }

    private void updateSelectionPreview(Player player, SelectionState sel) {
        sel.cancelPreview();
        if (!sel.ready()) {
            return;
        }

        sel.previewTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!sel.ready() || sel.world == null || !player.isOnline()) {
                    cancel();
                    sel.previewTask = null;
                    return;
                }
                SelectionBounds bounds = SelectionBounds.from(sel);
                drawSelectionParticles(player, sel, bounds);
            }
        }.runTaskTimer(plugin, 0L, PREVIEW_TASK_PERIOD_TICKS);
    }

    private void drawSelectionParticles(Player player, SelectionState sel, SelectionBounds bounds) {
        World world = bounds == null ? null : sel.world;
        if (world == null || player.getWorld() != world) {
            return;
        }
        int maxParticles = SelectionOutline.resolveMaxOutlineParticles(plugin);
        boolean includeMidpoints = SelectionOutline.resolveSimpleMidpoints(plugin);
        boolean fullHeight = sel.yMode == SelectionState.YMode.FULL;
        int viewerY = player.getLocation().getBlockY();
        for (Location location : SelectionOutline.planOutline(
                world,
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ,
                maxParticles,
                includeMidpoints,
                fullHeight,
                viewerY)) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, location, 1, 0, 0, 0, 0);
        }
    }

    private static String fmt(Location l) {
        return "(" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + ")";
    }

    private static class SelectionBounds {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;

        private SelectionBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        private static SelectionBounds from(SelectionState sel) {
            int minX = Math.min(sel.pos1.getBlockX(), sel.pos2.getBlockX());
            int maxX = Math.max(sel.pos1.getBlockX(), sel.pos2.getBlockX());
            int minY = Math.min(sel.pos1.getBlockY(), sel.pos2.getBlockY());
            int maxY = Math.max(sel.pos1.getBlockY(), sel.pos2.getBlockY());
            int minZ = Math.min(sel.pos1.getBlockZ(), sel.pos2.getBlockZ());
            int maxZ = Math.max(sel.pos1.getBlockZ(), sel.pos2.getBlockZ());
            return new SelectionBounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private int width() {
            return maxX - minX + 1;
        }

        private int height() {
            return maxY - minY + 1;
        }

        private int length() {
            return maxZ - minZ + 1;
        }

        private long volume() {
            return (long) width() * length() * height();
        }
    }
}
