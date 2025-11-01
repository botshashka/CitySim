package dev.citysim.visual;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public class ParticleRenderer {
    private Particle particleType;
    private Particle fallbackType = Particle.END_ROD;
    private Particle.DustOptions dustOptions;

    public ParticleRenderer(VisualizationConfig config) {
        updateConfig(config);
    }

    public void updateConfig(VisualizationConfig config) {
        if (config == null) {
            this.particleType = Particle.DUST;
            this.dustOptions = new Particle.DustOptions(Color.fromRGB(0xFF7A00), 1.0f);
            return;
        }
        this.particleType = config.particle();
        if (this.particleType == Particle.DUST) {
            this.dustOptions = new Particle.DustOptions(convert(config.dustColor()), 1.0f);
        } else {
            this.dustOptions = null;
        }
    }

    public void emit(Player player, float[] points, int startIndex, int count) {
        if (player == null || points == null || count <= 0) {
            return;
        }
        int totalPoints = points.length / 3;
        if (startIndex < 0 || startIndex >= totalPoints) {
            return;
        }
        int toEmit = Math.min(count, totalPoints - startIndex);
        Particle particle = resolveParticle();
        for (int i = 0; i < toEmit; i++) {
            int base = (startIndex + i) * 3;
            double x = points[base];
            double y = points[base + 1];
            double z = points[base + 2];
            spawnParticle(player, particle, x, y, z);
        }
    }

    private Particle resolveParticle() {
        if (particleType == null) {
            return Particle.DUST;
        }
        return particleType;
    }

    private void spawnParticle(Player player, Particle particle, double x, double y, double z) {
        if (particle == Particle.DUST && dustOptions != null) {
            player.spawnParticle(particle, x, y, z, 0, 0.0, 0.0, 0.0, 0.0, dustOptions, true);
            return;
        }
        player.spawnParticle(particle != null ? particle : fallbackType, x, y, z, 0, 0.0, 0.0, 0.0, 0.0, null, true);
    }

    private Color convert(java.awt.Color color) {
        if (color == null) {
            return Color.fromRGB(0xFF7A00);
        }
        return Color.fromRGB(color.getRed(), color.getGreen(), color.getBlue());
    }
}
