package dev.citysim.stats;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Encapsulates scheduling of periodic stats updates, including configuration of timing parameters.
 */
public class StatsUpdateScheduler {
    private static final long DEFAULT_STATS_INITIAL_DELAY_TICKS = 40L;
    private static final long DEFAULT_STATS_INTERVAL_TICKS = 100L;
    private static final long MIN_STATS_INTERVAL_TICKS = 20L;
    private static final long MAX_STATS_INTERVAL_TICKS = 12000L; // 10 minutes at 20 TPS
    private static final long MAX_STATS_INITIAL_DELAY_TICKS = 6000L; // 5 minutes at 20 TPS

    private final Plugin plugin;
    private final Runnable tickTask;

    private int taskId = -1;
    private long statsInitialDelayTicks = DEFAULT_STATS_INITIAL_DELAY_TICKS;
    private long statsIntervalTicks = DEFAULT_STATS_INTERVAL_TICKS;

    public StatsUpdateScheduler(Plugin plugin, Runnable tickTask) {
        this.plugin = plugin;
        this.tickTask = tickTask;
    }

    public void updateConfig(FileConfiguration config) {
        if (config == null) {
            return;
        }
        Logger logger = plugin.getLogger();

        long configuredInterval = config.getLong("updates.stats_interval_ticks", DEFAULT_STATS_INTERVAL_TICKS);
        if (configuredInterval < MIN_STATS_INTERVAL_TICKS || configuredInterval > MAX_STATS_INTERVAL_TICKS) {
            logger.warning("updates.stats_interval_ticks out of range; using default interval of " + DEFAULT_STATS_INTERVAL_TICKS + " ticks.");
            configuredInterval = DEFAULT_STATS_INTERVAL_TICKS;
        }
        statsIntervalTicks = configuredInterval;

        long configuredDelay = config.getLong("updates.stats_initial_delay_ticks", DEFAULT_STATS_INITIAL_DELAY_TICKS);
        if (configuredDelay < 0L || configuredDelay > MAX_STATS_INITIAL_DELAY_TICKS) {
            logger.warning("updates.stats_initial_delay_ticks out of range; using default delay of " + DEFAULT_STATS_INITIAL_DELAY_TICKS + " ticks.");
            configuredDelay = DEFAULT_STATS_INITIAL_DELAY_TICKS;
        }
        statsInitialDelayTicks = configuredDelay;
    }

    public void start() {
        if (isRunning()) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, tickTask, statsInitialDelayTicks, statsIntervalTicks);
    }

    public void stop() {
        if (!isRunning()) {
            return;
        }
        Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    public void restart() {
        stop();
        start();
    }

    public boolean isRunning() {
        return taskId != -1;
    }

    public long getStatsIntervalTicks() {
        return statsIntervalTicks;
    }

    public long getStatsInitialDelayTicks() {
        return statsInitialDelayTicks;
    }
}
