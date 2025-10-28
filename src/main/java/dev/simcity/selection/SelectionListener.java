package dev.simcity.selection;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionListener implements Listener {
    public static final Material WAND = Material.STICK;
    public static final Map<UUID, SelectionState> selections = new ConcurrentHashMap<>();

    public static SelectionState get(Player p) {
        return selections.computeIfAbsent(p.getUniqueId(), k -> new SelectionState());
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
            p.sendMessage(ChatColor.RED + "Selection is per-world. Clear or continue in " + sel.world.getName());
            return;
        }

        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel.pos1 = block.getLocation();
            p.sendActionBar(ChatColor.YELLOW + "Pos1 " + fmt(sel.pos1));
        } else {
            sel.pos2 = block.getLocation();
            p.sendActionBar(ChatColor.YELLOW + "Pos2 " + fmt(sel.pos2));
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5,1,0.5), 20, 0.4,0.4,0.4, 0.01);
        }
    }

    private static String fmt(org.bukkit.Location l) {
        return "(" + l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ()+")";
    }
}
