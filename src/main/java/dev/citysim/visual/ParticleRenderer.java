package dev.citysim.visual;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.List;

public final class ParticleRenderer {
    private final VisualizationSettings settings;
    private final Particle fallbackParticle = Particle.END_ROD;

    public ParticleRenderer(VisualizationSettings settings) {
        this.settings = settings;
    }

    public int emit(Player player, List<Vec3> points, int startIndex, int maxCount) {
        if (player == null || points == null || points.isEmpty() || maxCount <= 0) {
            return 0;
        }
        Particle particle = resolveParticle();
        Particle.DustOptions dustOptions = particle == Particle.DUST ? new Particle.DustOptions(settings.dustColor(), 1.0F) : null;
        int emitted = 0;
        for (int i = startIndex; i < points.size() && emitted < maxCount; i++) {
            Vec3 vec = points.get(i);
            player.spawnParticle(particle, vec.x(), vec.y(), vec.z(), 1, 0, 0, 0, 0, dustOptions, true);
            emitted++;
        }
        return emitted;
    }

    private Particle resolveParticle() {
        Particle configured = settings.particle();
        if (configured == null) {
            return fallbackParticle;
        }
        if (configured == Particle.DUST && settings.dustColor() == null) {
            return fallbackParticle;
        }
        return configured;
    }
}
