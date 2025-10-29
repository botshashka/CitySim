package dev.citysim;

import dev.citysim.city.CityManager;
import dev.citysim.cmd.CityCommand;
import dev.citysim.cmd.CityTab;
import dev.citysim.integration.traincarts.TrainCartsStationService;
import dev.citysim.papi.CitySimExpansion;
import dev.citysim.selection.SelectionListener;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.StatsService;
import dev.citysim.ui.DisplayPreferencesStore;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.ui.TitleService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class CitySimPlugin extends JavaPlugin {
    private CityManager cityManager;
    private StatsService statsService;
    private BossBarService bossBarService;
    private ScoreboardService scoreboardService;
    private TitleService titleService;
    private DisplayPreferencesStore displayPreferencesStore;
    private TrainCartsStationService trainCartsStationService;

    @Override
    public void onEnable() {
        getLogger().info("CitySim onEnable starting...");
        saveDefaultConfig();
        getLogger().info("Default configuration ensured at " + getDataFolder().getAbsolutePath());

        this.cityManager = new CityManager(this);
        getLogger().info("CityManager created (data folder: " + getDataFolder().getAbsolutePath() + ")");
        this.cityManager.load();
        int loadedCities = this.cityManager.all().size();
        getLogger().info("Loaded " + loadedCities + " " + (loadedCities == 1 ? "city" : "cities") + " from storage");

        this.statsService = new StatsService(this, cityManager, null);

        getServer().getPluginManager().registerEvents(new TrainCartsIntegrationListener(), this);

        if (tryInitializeTrainCartsIntegration()) {
            getLogger().info("TrainCarts detected: station counts can be synchronized automatically when enabled.");
        } else {
            getLogger().info("TrainCarts plugin not detected; station counts will remain manual unless another mode is selected.");
        }
        getLogger().info("StatsService created (tracking " + cityManager.all().size() + " cities)");
        this.statsService.start();
        getLogger().info("StatsService started");

        this.displayPreferencesStore = new DisplayPreferencesStore(this);
        this.displayPreferencesStore.load();
        getLogger().info("Display preferences loaded");

        this.bossBarService = new BossBarService(this, cityManager, statsService, displayPreferencesStore);
        getLogger().info("BossBarService created (enabled worlds: " + cityManager.all().stream().map(city -> city.world).filter(Objects::nonNull).distinct().count() + ")");
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
        disableTrainCartsIntegration();
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

    private boolean tryInitializeTrainCartsIntegration() {
        if (trainCartsStationService != null) {
            return true;
        }
        if (statsService == null) {
            return false;
        }

        Plugin plugin = getServer().getPluginManager().getPlugin("TrainCarts");
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }

        try {
            TrainCartsStationService service = new TrainCartsStationService(this);
            trainCartsStationService = service;
            statsService.setStationCounter(service);
            return true;
        } catch (Exception ex) {
            getLogger().warning("TrainCarts detected but CitySim could not initialize the integration: " + ex.getMessage());
        } catch (LinkageError err) {
            getLogger().warning("TrainCarts detected but CitySim could not initialize the integration: " + err.getMessage());
        }
        return false;
    }

    private void disableTrainCartsIntegration() {
        if (trainCartsStationService != null) {
            trainCartsStationService = null;
            if (statsService != null) {
                statsService.setStationCounter(null);
            }
            getLogger().info("TrainCarts integration disabled; station counts reverting to manual mode.");
        }
    }

    private final class TrainCartsIntegrationListener implements Listener {
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            if (!"TrainCarts".equalsIgnoreCase(event.getPlugin().getName())) {
                return;
            }
            if (tryInitializeTrainCartsIntegration()) {
                getLogger().info("TrainCarts detected after enable: station counts can be synchronized automatically when configured.");
            }
        }

        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (!"TrainCarts".equalsIgnoreCase(event.getPlugin().getName())) {
                return;
            }
            disableTrainCartsIntegration();
        }
    }
}

