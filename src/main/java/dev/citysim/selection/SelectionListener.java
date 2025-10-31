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
    private static final int MAX_EDGE_POINTS = 128;

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

        updateSelectionPreview(sel);
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

    private void updateSelectionPreview(SelectionState sel) {
        sel.cancelPreview();
        if (!sel.ready()) {
            return;
        }

        sel.previewTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!sel.ready() || sel.world == null) {
                    cancel();
                    sel.previewTask = null;
                    return;
                }
                SelectionBounds bounds = SelectionBounds.from(sel);
                drawSelectionParticles(sel.world, bounds);
            }
        }.runTaskTimer(plugin, 0L, PREVIEW_TASK_PERIOD_TICKS);
    }

    private void drawSelectionParticles(World world, SelectionBounds bounds) {
        int stepX = edgeIterationStep(bounds.maxX - bounds.minX);
        int lastX = Integer.MIN_VALUE;
        for (int x = bounds.minX; x <= bounds.maxX; x += stepX) {
            lastX = x;
            spawnEdgeParticle(world, x, bounds.minY, bounds.minZ);
            if (bounds.maxZ != bounds.minZ) {
                spawnEdgeParticle(world, x, bounds.minY, bounds.maxZ);
            }
        }
        if (lastX != bounds.maxX) {
            spawnEdgeParticle(world, bounds.maxX, bounds.minY, bounds.minZ);
            if (bounds.maxZ != bounds.minZ) {
                spawnEdgeParticle(world, bounds.maxX, bounds.minY, bounds.maxZ);
            }
        }

        if (bounds.maxZ != bounds.minZ) {
            int stepZ = edgeIterationStep(bounds.maxZ - bounds.minZ);
            int startZ = bounds.minZ + 1;
            int endZ = bounds.maxZ - 1;
            if (startZ <= endZ) {
                int lastZ = Integer.MIN_VALUE;
                for (int z = startZ; z <= endZ; z += stepZ) {
                    lastZ = z;
                    spawnEdgeParticle(world, bounds.minX, bounds.minY, z);
                    if (bounds.maxX != bounds.minX) {
                        spawnEdgeParticle(world, bounds.maxX, bounds.minY, z);
                    }
                }
                if (lastZ != endZ) {
                    spawnEdgeParticle(world, bounds.minX, bounds.minY, endZ);
                    if (bounds.maxX != bounds.minX) {
                        spawnEdgeParticle(world, bounds.maxX, bounds.minY, endZ);
                    }
                }
            }
        }

        if (bounds.maxY != bounds.minY) {
            lastX = Integer.MIN_VALUE;
            for (int x = bounds.minX; x <= bounds.maxX; x += stepX) {
                lastX = x;
                spawnEdgeParticle(world, x, bounds.maxY, bounds.minZ);
                if (bounds.maxZ != bounds.minZ) {
                    spawnEdgeParticle(world, x, bounds.maxY, bounds.maxZ);
                }
            }
            if (lastX != bounds.maxX) {
                spawnEdgeParticle(world, bounds.maxX, bounds.maxY, bounds.minZ);
                if (bounds.maxZ != bounds.minZ) {
                    spawnEdgeParticle(world, bounds.maxX, bounds.maxY, bounds.maxZ);
                }
            }

            if (bounds.maxZ != bounds.minZ) {
                int stepZ = edgeIterationStep(bounds.maxZ - bounds.minZ);
                int startZ = bounds.minZ + 1;
                int endZ = bounds.maxZ - 1;
                if (startZ <= endZ) {
                    int lastZ = Integer.MIN_VALUE;
                    for (int z = startZ; z <= endZ; z += stepZ) {
                        lastZ = z;
                        spawnEdgeParticle(world, bounds.minX, bounds.maxY, z);
                        if (bounds.maxX != bounds.minX) {
                            spawnEdgeParticle(world, bounds.maxX, bounds.maxY, z);
                        }
                    }
                    if (lastZ != endZ) {
                        spawnEdgeParticle(world, bounds.minX, bounds.maxY, endZ);
                        if (bounds.maxX != bounds.minX) {
                            spawnEdgeParticle(world, bounds.maxX, bounds.maxY, endZ);
                        }
                    }
                }
            }
        }

        if (bounds.maxY != bounds.minY) {
            int stepY = edgeIterationStep(bounds.maxY - bounds.minY);
            int startY = bounds.minY + 1;
            int endY = bounds.maxY - 1;
            if (startY <= endY) {
                int lastY = Integer.MIN_VALUE;
                for (int y = startY; y <= endY; y += stepY) {
                    lastY = y;
                    spawnEdgeParticle(world, bounds.minX, y, bounds.minZ);
                    if (bounds.maxZ != bounds.minZ) {
                        spawnEdgeParticle(world, bounds.minX, y, bounds.maxZ);
                    }
                    if (bounds.maxX != bounds.minX) {
                        spawnEdgeParticle(world, bounds.maxX, y, bounds.minZ);
                        if (bounds.maxZ != bounds.minZ) {
                            spawnEdgeParticle(world, bounds.maxX, y, bounds.maxZ);
                        }
                    }
                }
                if (lastY != endY) {
                    spawnEdgeParticle(world, bounds.minX, endY, bounds.minZ);
                    if (bounds.maxZ != bounds.minZ) {
                        spawnEdgeParticle(world, bounds.minX, endY, bounds.maxZ);
                    }
                    if (bounds.maxX != bounds.minX) {
                        spawnEdgeParticle(world, bounds.maxX, endY, bounds.minZ);
                        if (bounds.maxZ != bounds.minZ) {
                            spawnEdgeParticle(world, bounds.maxX, endY, bounds.maxZ);
                        }
                    }
                }
            }
        }
    }

    private static int edgeIterationStep(int length) {
        return Math.max(1, (int) Math.ceil(length / (double) MAX_EDGE_POINTS));
    }

    private void spawnEdgeParticle(World world, int x, int y, int z) {
        world.spawnParticle(Particle.HAPPY_VILLAGER, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0);
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
