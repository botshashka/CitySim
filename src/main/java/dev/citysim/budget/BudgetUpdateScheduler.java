package dev.citysim.budget;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

public class BudgetUpdateScheduler {
    private final Plugin plugin;
    private final Runnable tickTask;

    private int taskId = -1;
    private long budgetIntervalTicks = BudgetDefaults.DEFAULT_BUDGET_INTERVAL_TICKS;

    public BudgetUpdateScheduler(Plugin plugin, Runnable tickTask) {
        this.plugin = plugin;
        this.tickTask = tickTask;
    }

    public void updateConfig(FileConfiguration config) {
        if (config == null) {
            return;
        }
        Logger logger = plugin.getLogger();
        long configuredInterval = config.getLong("updates.budget_interval_ticks", BudgetDefaults.DEFAULT_BUDGET_INTERVAL_TICKS);
        if (configuredInterval < BudgetDefaults.MIN_BUDGET_INTERVAL_TICKS || configuredInterval > BudgetDefaults.MAX_BUDGET_INTERVAL_TICKS) {
            logger.warning("updates.budget_interval_ticks out of range; using default interval of " + BudgetDefaults.DEFAULT_BUDGET_INTERVAL_TICKS + " ticks.");
            configuredInterval = BudgetDefaults.DEFAULT_BUDGET_INTERVAL_TICKS;
        }
        budgetIntervalTicks = configuredInterval;
    }

    public void start() {
        if (isRunning()) {
            return;
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, tickTask, budgetIntervalTicks, budgetIntervalTicks);
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

    public long getBudgetIntervalTicks() {
        return budgetIntervalTicks;
    }
}
