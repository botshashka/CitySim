package dev.citysim.visual;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public final class ParticleRenderer {

    private final Particle particle;
    private final Particle.DustOptions dustOptions;

    public ParticleRenderer(Particle particle, Color dustColor) {
        this.particle = particle != null ? particle : Particle.DUST;
        if (this.particle == Particle.DUST) {
            Color color = dustColor != null ? dustColor : Color.fromRGB(0xFF7A00);
            this.dustOptions = new Particle.DustOptions(color, 1.0f);
        } else {
            this.dustOptions = null;
        }
    }

    public void emit(Player player, Vec3 point) {
        if (player == null || point == null) {
            return;
        }
        if (particle == Particle.DUST) {
            player.spawnParticle(particle, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0, dustOptions);
        } else {
            player.spawnParticle(particle, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0);
        }
    }
}
