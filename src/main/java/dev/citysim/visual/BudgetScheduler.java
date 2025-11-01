package dev.citysim.visual;

import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BudgetScheduler {

    private final JavaPlugin plugin;
    private final VisualizationService service;
    private final Map<UUID, PlayerRenderTask> tasks = new ConcurrentHashMap<>();

    BudgetScheduler(JavaPlugin plugin, VisualizationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void ensure(VisualizationService.PlayerSession session, int period) {
        tasks.compute(session.playerId, (id, existing) -> {
            if (existing != null) {
                if (existing.period == period) {
                    return existing;
                }
                existing.cancel();
            }
            PlayerRenderTask task = new PlayerRenderTask(session.playerId, period);
            task.schedule();
            return task;
        });
    }

    void ensure(VisualizationService.PlayerSession session) {
        ensure(session, service.settings().refreshTicks());
    }

    void cancel(UUID playerId) {
        PlayerRenderTask task = tasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    void shutdown() {
        for (PlayerRenderTask task : tasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        tasks.clear();
    }

    private final class PlayerRenderTask extends BukkitRunnable {
        private final UUID playerId;
        private final int period;
        private BukkitTask handle;

        PlayerRenderTask(UUID playerId, int period) {
            this.playerId = playerId;
            this.period = period;
        }

        void schedule() {
            handle = runTaskTimer(plugin, 1L, period);
        }

        @Override
        public void run() {
            service.render(playerId);
        }

        @Override
        public synchronized void cancel() {
            super.cancel();
            if (handle != null) {
                handle.cancel();
                handle = null;
            }
        }
    }
}
