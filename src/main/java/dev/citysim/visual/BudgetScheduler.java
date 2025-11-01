package dev.citysim.visual;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BudgetScheduler {
    private final JavaPlugin plugin;
    private final VisualizationService service;
    private final Map<UUID, PlayerTask> tasks = new ConcurrentHashMap<>();

    public BudgetScheduler(JavaPlugin plugin, VisualizationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void ensureTask(VisualizationService.PlayerSession session) {
        if (session == null) {
            return;
        }
        tasks.compute(session.playerId(), (uuid, existing) -> {
            if (existing != null) {
                existing.updateSession(session);
                if (!existing.isScheduled()) {
                    existing.start();
                }
                return existing;
            }
            PlayerTask task = new PlayerTask(session);
            task.start();
            return task;
        });
    }

    public void stop(UUID playerId) {
        if (playerId == null) {
            return;
        }
        PlayerTask task = tasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    public void reload() {
        for (PlayerTask task : tasks.values()) {
            task.restart();
        }
    }

    public void shutdown() {
        for (PlayerTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    private final class PlayerTask implements Runnable {
        private final UUID playerId;
        private VisualizationService.PlayerSession session;
        private BukkitTask task;

        private PlayerTask(VisualizationService.PlayerSession session) {
            this.playerId = session.playerId();
            this.session = session;
        }

        @Override
        public void run() {
            VisualizationSettings settings = service.getSettings();
            if (!settings.enabled()) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                cancel();
                service.ensureSessionState(playerId, null);
                tasks.remove(playerId);
                return;
            }
            if (session == null || !session.hasActiveShapes()) {
                cancel();
                service.ensureSessionState(playerId, session);
                tasks.remove(playerId);
                return;
            }
            int budget = settings.maxPointsPerTick();
            if (budget <= 0) {
                return;
            }
            int emitted = service.renderSelection(session, player, settings, budget);
            if (emitted < budget) {
                emitted += service.renderCityShapes(session, player, settings, budget - emitted);
            }
        }

        void updateSession(VisualizationService.PlayerSession session) {
            this.session = session;
        }

        void start() {
            cancel();
            long period = Math.max(1L, service.getSettings().refreshTicks());
            this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this, 0L, period);
        }

        void restart() {
            if (task == null) {
                start();
            } else {
                start();
            }
        }

        boolean isScheduled() {
            return task != null;
        }

        void cancel() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
