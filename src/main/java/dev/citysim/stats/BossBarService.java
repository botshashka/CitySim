package dev.citysim.stats;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.util.AdventureMessages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BossBarService {
    private static final long DEFAULT_INTERVAL_TICKS = 40L;

    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private int taskId = -1;
    private long updateIntervalTicks;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final dev.citysim.ui.DisplayPreferencesStore displayPreferencesStore;

    public BossBarService(Plugin plugin, CityManager cm, StatsService ss, dev.citysim.ui.DisplayPreferencesStore displayPreferencesStore) {
        this.plugin = plugin; this.cityManager = cm; this.statsService = ss; this.displayPreferencesStore = displayPreferencesStore;
        loadUpdateInterval();
    }

    public void start() {
        cancelScheduledTask();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, updateIntervalTicks, updateIntervalTicks);
    }
    public void stop() {
        cancelScheduledTask();
        for (var entry : bars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.hideBossBar(entry.getValue());
        }
        bars.clear();
    }

    public void restart() {
        cancelScheduledTask();
        loadUpdateInterval();
        start();
    }

    private void cancelScheduledTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void loadUpdateInterval() {
        long configured = plugin.getConfig().getLong("updates.bossbar_interval_ticks", DEFAULT_INTERVAL_TICKS);
        if (configured < 1L) {
            plugin.getLogger().warning("Configured boss bar interval (" + configured + ") is not positive; using default of " + DEFAULT_INTERVAL_TICKS + " ticks.");
            configured = DEFAULT_INTERVAL_TICKS;
        }
        this.updateIntervalTicks = Math.max(1L, configured);
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
            boolean ghostTown = c.isGhostTown();
            String safeName = AdventureMessages.escapeMiniMessage(c.name);
            String text;
            float progress;
            if (ghostTown) {
                text = "<gray>" + safeName + " — awaiting residents</gray>";
                progress = 0f;
            } else {
                text = "<white>" + safeName + "</white><white> — </white>" +
                        "<gold>" + c.happiness + "%</gold>";
                progress = Math.max(0f, Math.min(1f, c.happiness / 100f));
            }
            Component comp = mm.deserialize(text);

            if (bar == null) {
                bar = BossBar.bossBar(comp, progress, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
                p.showBossBar(bar);
                bars.put(p.getUniqueId(), bar);
            } else {
                bar.name(comp);
                bar.progress(progress);
                bar.color(BossBar.Color.WHITE);
                p.showBossBar(bar);
            }
        }

        bars.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }


    public void setEnabled(Player p, boolean on) {
        displayPreferencesStore.setBossBarEnabled(p.getUniqueId(), on);
        if (!on) {
            BossBar bar = bars.remove(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
        }
    }
    public boolean isEnabled(Player p) {
        return displayPreferencesStore.isBossBarEnabled(p.getUniqueId());
    }
}
