package dev.simcity;

import dev.simcity.city.CityManager;
import dev.simcity.cmd.CityCommand;
import dev.simcity.selection.SelectionListener;
import dev.simcity.stats.BossBarService;
import dev.simcity.ui.ScoreboardService;
import dev.simcity.stats.StatsService;
import dev.simcity.ui.TitleService;
import org.bukkit.plugin.java.JavaPlugin;

public class SimCityPlugin extends JavaPlugin {
    private CityManager cityManager;
    private StatsService statsService;
    private BossBarService bossBarService;
    private ScoreboardService scoreboardService;
    private TitleService titleService;

    @Override public void onEnable() {
        getLogger().info("SimCity onEnable starting...");
        saveDefaultConfig(); // reserved for later

        this.cityManager = new CityManager(this);
        getLogger().info("CityManager created");
        this.cityManager.load();
        getLogger().info("Cities loaded");

        this.statsService = new StatsService(this, cityManager);
        getLogger().info("StatsService created");
        this.statsService.start();
        getLogger().info("StatsService started");

        this.bossBarService = new BossBarService(this, cityManager, statsService);
        getLogger().info("BossBarService created");
        this.bossBarService.start();
        getLogger().info("BossBarService started");

        this.scoreboardService = new ScoreboardService(this, cityManager, statsService);
        this.scoreboardService.start();

        this.titleService = new TitleService(this, cityManager, statsService);
        this.titleService.start();

        // Register PlaceholderAPI expansion if present
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new dev.simcity.papi.SimCityExpansion(cityManager).register();
                getLogger().info("PlaceholderAPI detected: SimCity placeholders registered.");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PlaceholderAPI expansion: " + t.getMessage());
            }
        }

        if (getCommand("city") != null) {
            getCommand("city").setTabCompleter(new dev.simcity.cmd.CityTab(cityManager));
            getCommand("city").setExecutor(new CityCommand(this, cityManager, statsService));
            getLogger().info("/city command registered");
        } else {
            getLogger().severe("Command 'city' not found in plugin.yml");
        }
        getServer().getPluginManager().registerEvents(new SelectionListener(), this);

        getLogger().info("SimCity enabled.");
    }

    @Override public void onDisable() {
        if (bossBarService != null) bossBarService.stop();
        if (scoreboardService != null) scoreboardService.stop();
        if (titleService != null) titleService.stop();
        if (statsService != null) statsService.stop();
        if (cityManager != null) cityManager.save();
        getLogger().info("SimCity disabled.");
    }


public dev.simcity.ui.ScoreboardService getScoreboardService() { return scoreboardService; }



    public dev.simcity.stats.BossBarService getBossBarService() { return bossBarService; }


    public dev.simcity.ui.TitleService getTitleService() { return titleService; }
}
