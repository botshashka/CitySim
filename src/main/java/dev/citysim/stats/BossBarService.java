package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossBarService {
    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private int taskId = -1;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();

    public BossBarService(Plugin plugin, CityManager cm, StatsService ss) {
        this.plugin = plugin; this.cityManager = cm; this.statsService = ss;
    }

    public void start() {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 40L);
    }
    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        for (var entry : bars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.hideBossBar(entry.getValue());
        }
        bars.clear();
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isEnabled(p)) { continue; }
            City c = cityManager.cityAt(p.getLocation());
            BossBar bar = bars.get(p.getUniqueId());

            if (c == null) {
                if (bar != null) {
                    p.hideBossBar(bar);
                    bars.remove(p.getUniqueId());
                }
                continue;
            }
            String text = "<white>" + c.name + "</white><white> — </white>" +
                    "<gold>" + c.happiness + "%</gold>";
            Component comp = mm.deserialize(text);

            if (bar == null) {
                bar = BossBar.bossBar(comp, c.happiness / 100f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
                p.showBossBar(bar);
                bars.put(p.getUniqueId(), bar);
            } else {
                bar.name(comp);
                bar.progress(Math.max(0f, Math.min(1f, c.happiness / 100f)));
                bar.color(BossBar.Color.WHITE);
            }
        }
    }


    public void setEnabled(Player p, boolean on) {
        enabled.put(p.getUniqueId(), on);
        if (!on) {
            BossBar bar = bars.remove(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
        }
    }
    public boolean isEnabled(Player p) {
        return enabled.getOrDefault(p.getUniqueId(), true);
    }
}
