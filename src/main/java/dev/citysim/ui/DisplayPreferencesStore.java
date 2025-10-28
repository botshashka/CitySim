package dev.citysim.ui;

import dev.citysim.ui.ScoreboardService.Mode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Stores per-player display preferences and persists them to disk.
 */
public class DisplayPreferencesStore {
    private final Plugin plugin;
    private final File file;
    private final Map<UUID, DisplayPreferences> cache = new ConcurrentHashMap<>();

    public DisplayPreferencesStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "display-preferences.yml");
    }

    public void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        Set<String> keys = config.getKeys(false);
        for (String key : keys) {
            try {
                UUID uuid = UUID.fromString(key);
                DisplayPreferences prefs = new DisplayPreferences();
                prefs.setTitlesEnabled(config.getBoolean(key + ".titles", true));
                prefs.setBossBarEnabled(config.getBoolean(key + ".bossbar", true));
                prefs.setScoreboardEnabled(config.getBoolean(key + ".scoreboard", false));
                prefs.setScoreboardMode(config.getString(key + ".scoreboard-mode", Mode.COMPACT.name()));
                cache.put(uuid, prefs);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid UUID in display preferences: " + key);
            }
        }
    }

    public void save() {
        if (cache.isEmpty()) {
            if (file.exists() && !file.delete()) {
                plugin.getLogger().warning("Failed to delete empty display preferences file");
            }
            return;
        }

        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Failed to create data folder for display preferences");
            return;
        }

        FileConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, DisplayPreferences> entry : cache.entrySet()) {
            String base = entry.getKey().toString();
            DisplayPreferences prefs = entry.getValue();
            config.set(base + ".titles", prefs.isTitlesEnabled());
            config.set(base + ".bossbar", prefs.isBossBarEnabled());
            config.set(base + ".scoreboard", prefs.isScoreboardEnabled());
            config.set(base + ".scoreboard-mode", prefs.getScoreboardMode());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save display preferences", e);
        }
    }

    private DisplayPreferences getOrCreate(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> new DisplayPreferences());
    }

    public boolean isTitlesEnabled(UUID uuid) {
        return getOrCreate(uuid).isTitlesEnabled();
    }

    public void setTitlesEnabled(UUID uuid, boolean enabled) {
        getOrCreate(uuid).setTitlesEnabled(enabled);
        save();
    }

    public boolean isBossBarEnabled(UUID uuid) {
        return getOrCreate(uuid).isBossBarEnabled();
    }

    public void setBossBarEnabled(UUID uuid, boolean enabled) {
        getOrCreate(uuid).setBossBarEnabled(enabled);
        save();
    }

    public boolean isScoreboardEnabled(UUID uuid) {
        return getOrCreate(uuid).isScoreboardEnabled();
    }

    public void setScoreboardEnabled(UUID uuid, boolean enabled) {
        getOrCreate(uuid).setScoreboardEnabled(enabled);
        save();
    }

    public Mode getScoreboardMode(UUID uuid) {
        String value = getOrCreate(uuid).getScoreboardMode();
        try {
            return Mode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Mode.COMPACT;
        }
    }

    public void setScoreboardMode(UUID uuid, Mode mode) {
        getOrCreate(uuid).setScoreboardMode(mode.name());
        save();
    }
}
