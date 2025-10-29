package dev.citysim.selection;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionListener implements Listener {
    public static final Material WAND = Material.STICK;
    public static final Map<UUID, SelectionState> selections = new ConcurrentHashMap<>();

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
            p.sendActionBar(Component.text("Pos1 " + fmt(sel.pos1), NamedTextColor.YELLOW));
        } else {
            sel.pos2 = block.getLocation();
            p.sendActionBar(Component.text("Pos2 " + fmt(sel.pos2), NamedTextColor.YELLOW));
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5,1,0.5), 20, 0.4,0.4,0.4, 0.01);
        }
    }

    private static String fmt(org.bukkit.Location l) {
        return "(" + l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ()+")";
    }
}
