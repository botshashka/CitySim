package dev.citysim.visual;

import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

public class BudgetScheduler {
    private final JavaPlugin plugin;
    private final VisualizationService service;
    private final VisualizationService.PlayerSession session;
    private BukkitTask task;

    public BudgetScheduler(JavaPlugin plugin, VisualizationService service, VisualizationService.PlayerSession session) {
        this.plugin = plugin;
        this.service = service;
        this.session = session;
    }

    public void start(int refreshTicks) {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> service.render(session), 0L, Math.max(1L, refreshTicks));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
