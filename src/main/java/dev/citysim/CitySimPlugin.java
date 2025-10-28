package dev.citysim;

import dev.citysim.city.CityManager;
import dev.citysim.cmd.CityCommand;
import dev.citysim.cmd.CityTab;
import dev.citysim.papi.CitySimExpansion;
import dev.citysim.selection.SelectionListener;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.DisplayPreferencesStore;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.ui.TitleService;
import org.bukkit.plugin.java.JavaPlugin;

public class CitySimPlugin extends JavaPlugin {
    private CityManager cityManager;
    private StatsService statsService;
    private BossBarService bossBarService;
    private ScoreboardService scoreboardService;
    private TitleService titleService;
    private DisplayPreferencesStore displayPreferencesStore;

    @Override
    public void onEnable() {
        getLogger().info("CitySim onEnable starting...");
        saveDefaultConfig();

        this.cityManager = new CityManager(this);
        getLogger().info("CityManager created");
        this.cityManager.load();
        getLogger().info("Cities loaded");

        this.statsService = new StatsService(this, cityManager);
        getLogger().info("StatsService created");
        this.statsService.start();
        getLogger().info("StatsService started");

        this.displayPreferencesStore = new DisplayPreferencesStore(this);
        this.displayPreferencesStore.load();

        this.bossBarService = new BossBarService(this, cityManager, statsService, displayPreferencesStore);
        getLogger().info("BossBarService created");
        this.bossBarService.start();
        getLogger().info("BossBarService started");

        this.scoreboardService = new ScoreboardService(this, cityManager, statsService, displayPreferencesStore);
        this.scoreboardService.start();
        getLogger().info("ScoreboardService started");

        this.titleService = new TitleService(this, cityManager, statsService, displayPreferencesStore);
        this.titleService.start();
        getLogger().info("TitleService started");

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new CitySimExpansion(cityManager).register();
                getLogger().info("PlaceholderAPI detected: CitySim placeholders registered.");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PlaceholderAPI expansion: " + t.getMessage());
            }
        }

        if (getCommand("city") != null) {
            getCommand("city").setTabCompleter(new CityTab(cityManager));
            getCommand("city").setExecutor(new CityCommand(this, cityManager, statsService));
            getLogger().info("/city command registered");
        } else {
            getLogger().severe("Command 'city' not found in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(new SelectionListener(), this);
        getLogger().info("CitySim enabled.");
    }

    @Override
    public void onDisable() {
        if (bossBarService != null) {
            bossBarService.stop();
        }
        if (scoreboardService != null) {
            scoreboardService.stop();
        }
        if (titleService != null) {
            titleService.stop();
        }
        if (statsService != null) {
            statsService.stop();
        }
        if (cityManager != null) {
            cityManager.save();
        }
        if (displayPreferencesStore != null) {
            displayPreferencesStore.save();
        }
        getLogger().info("CitySim disabled.");
    }

    public CityManager getCityManager() {
        return cityManager;
    }

    public StatsService getStatsService() {
        return statsService;
    }

    public BossBarService getBossBarService() {
        return bossBarService;
    }

    public ScoreboardService getScoreboardService() {
        return scoreboardService;
    }

    public TitleService getTitleService() {
        return titleService;
    }

    public DisplayPreferencesStore getDisplayPreferencesStore() {
        return displayPreferencesStore;
    }
}

