package dev.citysim.visual;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

public final class VisualizationSettingsLoader {
    private static final String SECTION = "visualization";
    private static final String ENABLED = "enabled";
    private static final String PARTICLE = "particle";
    private static final String DUST_COLOR = "dust_color";
    private static final String VIEW_DISTANCE = "view_distance";
    private static final String BASE_STEP = "base_step";
    private static final String FAR_DISTANCE_MULTIPLIER = "far_distance_step_multiplier";
    private static final String MAX_POINTS = "max_points_per_tick";
    private static final String REFRESH_TICKS = "refresh_ticks";
    private static final String ASYNC_PREPARE = "async_prepare";
    private static final String JITTER = "jitter";
    private static final String SLICE_THICKNESS = "slice_thickness";
    private static final String DEBUG = "debug";

    private VisualizationSettingsLoader() {
    }

    public static VisualizationSettings load(Plugin plugin) {
        if (plugin == null) {
            return defaults();
        }
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection(SECTION);
        if (section == null) {
            return defaults();
        }

        boolean enabled = section.getBoolean(ENABLED, true);
        Particle particle = parseParticle(section.getString(PARTICLE, "DUST"));
        Color dustColor = parseColor(section.getString(DUST_COLOR, "#FF7A00"));
        double viewDistance = Math.max(1.0, section.getDouble(VIEW_DISTANCE, 48.0));
        double baseStep = Math.max(0.1, section.getDouble(BASE_STEP, 0.75));
        double farMultiplier = Math.max(1.0, section.getDouble(FAR_DISTANCE_MULTIPLIER, 1.5));
        int maxPoints = Math.max(1, section.getInt(MAX_POINTS, 800));
        int refresh = Math.max(1, section.getInt(REFRESH_TICKS, 3));
        boolean async = section.getBoolean(ASYNC_PREPARE, true);
        double jitter = Math.max(0.0, section.getDouble(JITTER, 0.15));
        double sliceThickness = Math.max(0.0, section.getDouble(SLICE_THICKNESS, 0.0));
        boolean debug = section.getBoolean(DEBUG, false);

        return new VisualizationSettings(enabled,
                particle,
                dustColor,
                viewDistance,
                baseStep,
                farMultiplier,
                maxPoints,
                refresh,
                async,
                jitter,
                sliceThickness,
                debug);
    }

    private static VisualizationSettings defaults() {
        return new VisualizationSettings(true,
                Particle.DUST,
                parseColor("#FF7A00"),
                48.0,
                0.75,
                1.5,
                800,
                3,
                true,
                0.15,
                0.0,
                false);
    }

    private static Particle parseParticle(String value) {
        if (value == null) {
            return Particle.DUST;
        }
        try {
            return Particle.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Particle.DUST;
        }
    }

    private static Color parseColor(String value) {
        if (value == null || value.isEmpty()) {
            return Color.fromRGB(0xFF7A00);
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        try {
            int rgb = Integer.parseUnsignedInt(cleaned, 16);
            return Color.fromRGB(rgb);
        } catch (NumberFormatException ex) {
            return Color.fromRGB(0xFF7A00);
        }
    }
}
