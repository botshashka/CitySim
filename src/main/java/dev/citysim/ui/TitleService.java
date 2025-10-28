package dev.citysim.ui;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.stats.HappinessBreakdown;
import dev.citysim.stats.StatsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class TitleService implements Listener {
    private final Plugin plugin;
    private final CityManager cityManager;
    private final StatsService statsService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private int taskId = -1;

    private final Map<UUID, String> lastCity = new HashMap<>();
    private final Map<UUID, Long> lastShownTick = new HashMap<>();
    private final DisplayPreferencesStore displayPreferencesStore;

    public TitleService(Plugin plugin, CityManager cityManager, StatsService statsService, DisplayPreferencesStore displayPreferencesStore) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.statsService = statsService;
        this.displayPreferencesStore = displayPreferencesStore;
    }

    public void start() {
        if (taskId != -1) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            City current = cityManager.cityAt(player.getLocation());
            lastCity.put(player.getUniqueId(), current != null ? current.id : null);
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = -1;
        HandlerList.unregisterAll(this);
        lastCity.clear();
        lastShownTick.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        City current = cityManager.cityAt(player.getLocation());
        lastCity.put(player.getUniqueId(), current != null ? current.id : null);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastCity.remove(uuid);
        lastShownTick.remove(uuid);
    }

    private void tick() {
        boolean configEnabled = plugin.getConfig().getBoolean("titles.enabled", true);
        int cooldown = Math.max(0, plugin.getConfig().getInt("titles.cooldown_ticks", 80));
        long serverTick = Bukkit.getCurrentTick();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isEnabled(player.getUniqueId())) {
                continue;
            }

            City current = cityManager.cityAt(player.getLocation());
            String currentId = current != null ? current.id : null;
            String previousId = lastCity.get(player.getUniqueId());

            if (Objects.equals(previousId, currentId)) {
                continue;
            }

            lastCity.put(player.getUniqueId(), currentId);

            if (!configEnabled || current == null) {
                continue;
            }

            long lastShown = lastShownTick.getOrDefault(player.getUniqueId(), 0L);
            if (serverTick - lastShown < cooldown) {
                continue;
            }
            lastShownTick.put(player.getUniqueId(), serverTick);

            statsService.updateCity(current);
            HappinessBreakdown breakdown = statsService.computeHappinessBreakdown(current);
            String key = breakdown.pickWeightedMessageKey();

            Component title = Component.text(current.name)
                    .color(NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD);
            Component subtitle = miniMessage.deserialize(resolveMessage(key)
                    .replace("{city}", current.name));

            Title.Times times = Title.Times.of(
                    Duration.ofMillis(500),
                    Duration.ofMillis(2500),
                    Duration.ofMillis(500)
            );

            player.showTitle(Title.title(title, subtitle, times));
        }
    }

    public void setEnabled(UUID uuid, boolean on) {
        displayPreferencesStore.setTitlesEnabled(uuid, on);
    }

    public boolean isEnabled(UUID uuid) {
        return displayPreferencesStore.isTitlesEnabled(uuid);
    }

    private String resolveMessage(String key) {
        String path = "titles.messages." + key;
        List<String> configuredList = plugin.getConfig().getStringList(path);
        if (!configuredList.isEmpty()) {
            List<String> filtered = configuredList.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(filtered.size());
                return filtered.get(idx);
            }
        }

        String configured = plugin.getConfig().getString(path);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return HappinessBreakdown.defaultMessageFor(key);
    }
}

