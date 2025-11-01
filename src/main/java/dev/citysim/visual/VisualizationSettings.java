package dev.citysim.visual;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Immutable snapshot of the visualization configuration. Values are loaded from config.yml
 * so that the renderer can react to reloads without hammering the configuration API.
 */
public final class VisualizationSettings {

    private static final String ROOT = "visualization";

    private final boolean enabled;
    private final Particle particle;
    private final Color dustColor;
    private final double viewDistance;
    private final double baseStep;
    private final double farDistanceStepMultiplier;
    private final int maxPointsPerTick;
    private final int refreshTicks;
    private final boolean asyncPrepare;
    private final double jitter;
    private final double sliceThickness;
    private final boolean debug;

    private VisualizationSettings(boolean enabled,
                                   Particle particle,
                                   Color dustColor,
                                   double viewDistance,
                                   double baseStep,
                                   double farDistanceStepMultiplier,
                                   int maxPointsPerTick,
                                   int refreshTicks,
                                   boolean asyncPrepare,
                                   double jitter,
                                   double sliceThickness,
                                   boolean debug) {
        this.enabled = enabled;
        this.particle = particle;
        this.dustColor = dustColor;
        this.viewDistance = viewDistance;
        this.baseStep = baseStep;
        this.farDistanceStepMultiplier = farDistanceStepMultiplier;
        this.maxPointsPerTick = maxPointsPerTick;
        this.refreshTicks = refreshTicks;
        this.asyncPrepare = asyncPrepare;
        this.jitter = jitter;
        this.sliceThickness = sliceThickness;
        this.debug = debug;
    }

    public static VisualizationSettings fromConfig(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        ConfigurationSection section = config.getConfigurationSection(ROOT);
        if (section == null) {
            section = config.createSection(ROOT);
        }

        boolean enabled = section.getBoolean("enabled", true);

        Particle particle = parseParticle(section.getString("particle", "DUST"));
        Color color = parseColor(section.getString("dust_color", "#FF7A00"));

        double viewDistance = clamp(section.getDouble("view_distance", 48.0), 4.0, 256.0);
        double baseStep = clamp(section.getDouble("base_step", 0.75), 0.1, 5.0);
        double farMultiplier = Math.max(1.0, section.getDouble("far_distance_step_multiplier", 1.5));
        int maxPoints = Math.max(16, section.getInt("max_points_per_tick", 800));
        int refreshTicks = Math.max(1, section.getInt("refresh_ticks", 3));
        boolean asyncPrepare = section.getBoolean("async_prepare", true);
        double jitter = clamp(section.getDouble("jitter", 0.15), 0.0, 1.0);
        double sliceThickness = clamp(section.getDouble("slice_thickness", 0.0), 0.0, 1.0);
        boolean debug = section.getBoolean("debug", false);

        return new VisualizationSettings(
                enabled,
                particle,
                color,
                viewDistance,
                baseStep,
                farMultiplier,
                maxPoints,
                refreshTicks,
                asyncPrepare,
                jitter,
                sliceThickness,
                debug
        );
    }

    private static Particle parseParticle(String input) {
        if (input == null) {
            return Particle.DUST;
        }
        String upper = input.trim().toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return Particle.DUST;
        }
    }

    private static Color parseColor(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Color.fromRGB(0xFF7A00);
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            int rgb = (int) Long.parseLong(value, 16);
            return Color.fromRGB(rgb);
        } catch (NumberFormatException ex) {
            return Color.fromRGB(0xFF7A00);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static VisualizationSettings defaults() {
        return new VisualizationSettings(
                true,
                Particle.DUST,
                Color.fromRGB(0xFF7A00),
                48.0,
                0.75,
                1.5,
                800,
                3,
                true,
                0.15,
                0.0,
                false
        );
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

    public boolean debug() {
        return debug;
    }
}
