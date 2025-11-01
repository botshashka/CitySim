package dev.citysim.visual;

import org.bukkit.Particle;
import org.bukkit.configuration.file.FileConfiguration;

import java.awt.Color;

public class VisualizationConfig {
    public static final String CONFIG_ROOT = "visualization";

    private boolean enabled;
    private Particle particle;
    private Color dustColor;
    private double viewDistance;
    private double baseStep;
    private double farDistanceStepMultiplier;
    private int maxPointsPerTick;
    private int refreshTicks;
    private boolean asyncPrepare;
    private double jitter;
    private double sliceThickness;

    public VisualizationConfig() {
    }

    public void load(FileConfiguration configuration) {
        String root = CONFIG_ROOT + ".";
        this.enabled = configuration.getBoolean(root + "enabled", true);
        String particleName = configuration.getString(root + "particle", Particle.DUST.name());
        Particle resolvedParticle;
        try {
            resolvedParticle = Particle.valueOf(particleName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            resolvedParticle = Particle.DUST;
        }
        this.particle = resolvedParticle;
        String color = configuration.getString(root + "dust_color", "#FF7A00");
        this.dustColor = parseColor(color);
        this.viewDistance = configuration.getDouble(root + "view_distance", 48.0);
        this.baseStep = configuration.getDouble(root + "base_step", 0.75);
        this.farDistanceStepMultiplier = configuration.getDouble(root + "far_distance_step_multiplier", 1.5);
        this.maxPointsPerTick = Math.max(1, configuration.getInt(root + "max_points_per_tick", 800));
        this.refreshTicks = Math.max(1, configuration.getInt(root + "refresh_ticks", 3));
        this.asyncPrepare = configuration.getBoolean(root + "async_prepare", true);
        this.jitter = Math.max(0.0, configuration.getDouble(root + "jitter", 0.15));
        this.sliceThickness = Math.max(0.0, configuration.getDouble(root + "slice_thickness", 0.0));
    }

    private Color parseColor(String input) {
        if (input == null) {
            return new Color(0xFF7A00);
        }
        String normalized = input.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() == 6) {
            try {
                int rgb = Integer.parseInt(normalized, 16);
                return new Color(rgb);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Color(0xFF7A00);
    }

    public boolean enabled() {
        return enabled;
    }

    public Particle particle() {
        return particle;
    }

    public Color dustColor() {
        return dustColor;
    }

    public double viewDistance() {
        return viewDistance;
    }

    public double baseStep() {
        return baseStep;
    }

    public double farDistanceStepMultiplier() {
        return farDistanceStepMultiplier;
    }

    public int maxPointsPerTick() {
        return maxPointsPerTick;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public boolean asyncPrepare() {
        return asyncPrepare;
    }

    public double jitter() {
        return jitter;
    }

    public double sliceThickness() {
        return sliceThickness;
    }
}
