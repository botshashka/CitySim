
package dev.simcity.ui;

import net.kyori.adventure.text.minimessage.MiniMessage;

import dev.simcity.city.City;
import dev.simcity.city.CityManager;
import dev.simcity.stats.HappinessBreakdown;
import dev.simcity.stats.StatsService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

public class TitleService {
    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService stats;
    private int taskId = -1;

    private final Map<UUID, String> lastCity = new HashMap<>();
        private final Map<UUID, Boolean> enabled = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastShownTick = new HashMap<>();

    public TitleService(Plugin plugin, CityManager cm, StatsService stats) {
        this.plugin = plugin; this.cityManager = cm; this.stats = stats;
    }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }
    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        lastCity.clear(); lastShownTick.clear();
    }

    private void tick() {
        boolean enabled = plugin.getConfig().getBoolean("titles.enabled", true);
        int cooldown = plugin.getConfig().getInt("titles.cooldown_ticks", 80);
        long serverTick = Bukkit.getCurrentTick();

        for (Player p : Bukkit.getOnlinePlayers()) {
            String prev = lastCity.get(p.getUniqueId());
            if (!isEnabled(p.getUniqueId())) continue;
                City now = cityManager.cityAt(p.getLocation());
            String nowId = now != null ? now.id : null;

            if ((prev == null && nowId != null) || (prev != null && !prev.equals(nowId))) {
                lastCity.put(p.getUniqueId(), nowId);
                if (!enabled || now == null) continue;

                long last = lastShownTick.getOrDefault(p.getUniqueId(), 0L);
                if (serverTick - last < cooldown) continue;
                lastShownTick.put(p.getUniqueId(), serverTick);

                stats.updateCity(now);
                HappinessBreakdown hb = stats.computeHappinessBreakdown(now);

                String key = hb.dominantKey();
                    String path = "titles.messages." + key;
                    String subtitle = plugin.getConfig().getString(path, hb != null ? messageForKey(hb.dominantKey(), hb) : "Citizens are content");
                Title title = Title.title(
                    net.kyori.adventure.text.Component.text(now.name),
                    net.kyori.adventure.text.Component.text(subtitle),
                    Title.Times.of(Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofMillis(500))
                );
                p.showTitle(title);
            }
        }
    }


    public void setEnabled(java.util.UUID uuid, boolean on) {
        enabled.put(uuid, on);
    }
    public boolean isEnabled(java.util.UUID uuid) {
        return enabled.getOrDefault(uuid, true);
    }


private String messageForKey(String key, dev.simcity.stats.HappinessBreakdown hb) {
    // Read from config: titles.messages.<key>, fallback to hb.defaultMessage(key)
    String cfgPath = "titles.messages." + key;
    String fallback = hb == null ? key : hb != null ? messageForKey(hb.dominantKey(), hb) : "Citizens are content";
    String v = plugin.getConfig().getString(cfgPath);
    if (v == null || v.isEmpty()) {
        // use hardcoded defaults similar to HB
        switch (key) {
            case "bright": return "Bright, well-lit streets";
            case "dark": return "Too dark — add lighting";
            case "employment_good": return "High employment";
            case "employment_bad": return "Unemployment is hurting morale";
            case "golems_good": return "Golems make it feel safe";
            case "golems_bad": return "Not enough protection";
            case "crowding_good": return "Comfortable spacing";
            case "crowding_bad": return "Overcrowded — expand the city";
            case "jobs_good": return "Plenty of workplaces";
            case "jobs_bad": return "Not enough job sites";
            case "nature_good": return "Green, lively parks";
            case "nature_bad": return "Too little greenery";
            case "pollution_good": return "Clean air and skies";
            case "pollution_bad": return "Smoggy, industrial feel";
            case "beds_good": return "Everyone has a bed";
            case "beds_bad": return "Not enough housing";
            case "water_good": return "Soothing water nearby";
            case "water_bad": return "No water features";
            case "beauty_good": return "Charming decorations";
            case "beauty_bad": return "Drab and undecorated";
            case "default_good": return "Citizens are content";
            case "default_bad": return "Citizens feel uneasy";
            default: return key;
        }
    }
    return v;
}
}
