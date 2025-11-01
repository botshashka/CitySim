package dev.citysim;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.cmd.CityCommand;
import dev.citysim.cmd.CityTab;
import dev.citysim.integration.traincarts.StationSignParser;
import dev.citysim.integration.traincarts.TrainCartsLocator;
import dev.citysim.integration.traincarts.TrainCartsReflectionBinder;
import dev.citysim.integration.traincarts.TrainCartsStationService;
import dev.citysim.papi.CitySimExpansion;
import dev.citysim.selection.SelectionListener;
import dev.citysim.visual.SelectionTracker;
import dev.citysim.visual.VisualizationService;
import dev.citysim.visual.VisualizationSettings;
import dev.citysim.visual.VisualizationSettingsLoader;
import dev.citysim.stats.BossBarService;
import dev.citysim.stats.StatsService;
import dev.citysim.stats.StationCountingMode;
import dev.citysim.ui.DisplayPreferencesStore;
import dev.citysim.ui.ScoreboardService;
import dev.citysim.ui.TitleService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
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
    private VisualizationService visualizationService;
    private SelectionTracker selectionTracker;

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

        VisualizationSettings visualizationSettings = VisualizationSettingsLoader.load(this);
        this.visualizationService = new VisualizationService(this, cityManager, visualizationSettings);
        this.selectionTracker = new SelectionTracker(visualizationService);
        this.visualizationService.setSelectionTracker(selectionTracker);

        this.trainCartsStationService = attemptTrainCartsBootstrap();
        if (this.trainCartsStationService == null) {
            getLogger().info("TrainCarts plugin not detected yet; station counts will remain manual unless another mode is selected.");
        }

        this.statsService = new StatsService(this, cityManager, trainCartsStationService);
        getLogger().info("StatsService created (tracking " + cityManager.all().size() + " cities)");
        this.statsService.start();
        getLogger().info("StatsService started");

        getServer().getPluginManager().registerEvents(new DependencyListener(), this);

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
            CityCommand cityCommand = new CityCommand(this,
                    cityManager,
                    statsService,
                    titleService,
                    bossBarService,
                    scoreboardService,
                    selectionTracker,
                    visualizationService);
            getCommand("city").setExecutor(cityCommand);
            getCommand("city").setTabCompleter(new CityTab(cityCommand.getRegistry()));
            getLogger().info("/city command registered");
        } else {
            getLogger().severe("Command 'city' not found in plugin.yml");
        }

        getServer().getPluginManager().registerEvents(new SelectionListener(selectionTracker), this);
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
        if (visualizationService != null) {
            visualizationService.shutdown();
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

    public VisualizationService getVisualizationService() {
        return visualizationService;
    }

    public SelectionTracker getSelectionTracker() {
        return selectionTracker;
    }

    private TrainCartsStationService attemptTrainCartsBootstrap() {
        var plugin = TrainCartsLocator.locate(this).orElse(null);
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            TrainCartsReflectionBinder binder = new TrainCartsReflectionBinder(getLogger());
            StationSignParser parser = new StationSignParser();
            TrainCartsStationService service = new TrainCartsStationService(this, getServer(), plugin, binder, parser);
            getLogger().info("TrainCarts detected: station counts can be synchronized automatically when enabled.");
            return service;
        } catch (Exception ex) {
            getLogger().warning("TrainCarts detected but CitySim could not initialize the integration: " + ex.getMessage());
        } catch (LinkageError err) {
            getLogger().warning("TrainCarts detected but CitySim could not initialize the integration: " + err.getMessage());
        }
        return null;
    }

    private void refreshStationsForAllCities(String reason) {
        if (statsService == null || cityManager == null) {
            return;
        }
        for (City city : cityManager.all()) {
            if (city == null) {
                continue;
            }
            statsService.requestCityUpdate(city, true, reason);
        }
    }

    private final class DependencyListener implements Listener {
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            if (!TrainCartsLocator.isTrainCartsName(event.getPlugin().getName())) {
                return;
            }
            if (trainCartsStationService != null) {
                return;
            }
            if (!event.getPlugin().isEnabled()) {
                return;
            }
            TrainCartsStationService service = attemptTrainCartsBootstrap();
            if (service == null) {
                return;
            }
            trainCartsStationService = service;
            if (statsService != null) {
                statsService.setStationCounter(trainCartsStationService);
                if (statsService.getStationCountingMode() == StationCountingMode.TRAIN_CARTS) {
                    refreshStationsForAllCities("TrainCarts integration initialized");
                }
            }
        }

        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (!TrainCartsLocator.isTrainCartsName(event.getPlugin().getName())) {
                return;
            }
            if (trainCartsStationService == null || statsService == null) {
                trainCartsStationService = null;
                return;
            }
            StationCountingMode previousMode = statsService.getStationCountingMode();
            statsService.setStationCounter(null);
            trainCartsStationService = null;
            CitySimPlugin.this.getLogger().info("TrainCarts disabled: station counts will remain manual until it is re-enabled.");
            if (previousMode == StationCountingMode.TRAIN_CARTS) {
                refreshStationsForAllCities("TrainCarts integration disabled");
            }
        }
    }
}

