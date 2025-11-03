package dev.citysim.economy;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Container for economy configuration values loaded from {@code config.yml}.
 */
public final class EconomySettings {
    private final boolean enabled;
    private final int districtTileBlocks;
    private final int districtSampleStep;
    private final long refreshIntervalMillis;
    private final long overlayTtlSeconds;
    private final List<Integer> overlayBuckets;
    private final int stationRadiusChunks;
    private final double weightLight;
    private final double weightNature;
    private final double weightAccess;
    private final double weightPollution;
    private final double weightCrowding;
    private final double gdpPerWorker;
    private final double happinessMultiplierMin;
    private final double happinessMultiplierMax;
    private final double lviMultiplierMin;
    private final double lviMultiplierMax;
    private final double accessMultiplierMin;
    private final double accessMultiplierMax;
    private final double indexWeightGdp;
    private final double indexWeightLvi;
    private final double indexWeightPopulation;
    private final double indexEmaAlpha;
    private final BehaviorMode behaviorMode;
    private final int maxMovesPerMinute;
    private final int maxSpawnsPerMinute;
    private final int villagerCooldownMinutes;
    private final boolean economyDebug;

    public EconomySettings(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("economy");
        if (section == null) {
            section = config.createSection("economy");
        }
        this.enabled = section.getBoolean("enabled", true);
        this.districtTileBlocks = Math.max(16, section.getInt("district_tile_blocks", 32));
        this.districtSampleStep = Math.max(2, section.getInt("district_sample_step", 8));
        this.refreshIntervalMillis = Math.max(1000L, section.getLong("refresh_interval_millis", 45_000L));
        this.overlayTtlSeconds = Math.max(5L, section.getLong("overlay_ttl_seconds", 30L));
        List<Integer> defaults = List.of(20, 40, 60, 80);
        List<Integer> configured = section.getIntegerList("overlay_buckets");
        this.overlayBuckets = configured == null || configured.isEmpty() ? defaults : List.copyOf(configured);
        this.stationRadiusChunks = Math.max(1, section.getInt("station_radius_chunks", 6));
        ConfigurationSection weights = section.getConfigurationSection("lvi_weights");
        if (weights == null) {
            weights = section.createSection("lvi_weights");
        }
        this.weightLight = weights.getDouble("light", 0.25D);
        this.weightNature = weights.getDouble("nature", 0.20D);
        this.weightAccess = weights.getDouble("access", 0.20D);
        this.weightPollution = weights.getDouble("pollution", 0.20D);
        this.weightCrowding = weights.getDouble("crowding", 0.15D);
        ConfigurationSection gdp = section.getConfigurationSection("gdp");
        if (gdp == null) {
            gdp = section.createSection("gdp");
        }
        this.gdpPerWorker = gdp.getDouble("per_worker", 4.0D);
        this.happinessMultiplierMin = gdp.getDouble("happiness_multiplier_min", 0.80D);
        this.happinessMultiplierMax = gdp.getDouble("happiness_multiplier_max", 1.20D);
        this.lviMultiplierMin = gdp.getDouble("lvi_multiplier_min", 0.90D);
        this.lviMultiplierMax = gdp.getDouble("lvi_multiplier_max", 1.10D);
        this.accessMultiplierMin = gdp.getDouble("access_multiplier_min", 0.80D);
        this.accessMultiplierMax = gdp.getDouble("access_multiplier_max", 1.20D);
        ConfigurationSection index = section.getConfigurationSection("index");
        if (index == null) {
            index = section.createSection("index");
        }
        this.indexWeightGdp = index.getDouble("a_gdp_return", 0.6D);
        this.indexWeightLvi = index.getDouble("b_lvi_return", 0.3D);
        this.indexWeightPopulation = index.getDouble("c_population_return", 0.1D);
        this.indexEmaAlpha = Math.min(Math.max(index.getDouble("ema_alpha", 0.2D), 0.01D), 1.0D);
        ConfigurationSection behavior = section.getConfigurationSection("behavior");
        if (behavior == null) {
            behavior = section.createSection("behavior");
        }
        this.behaviorMode = BehaviorMode.safeParse(behavior.getString("mode", "DISABLED"));
        this.maxMovesPerMinute = Math.max(0, behavior.getInt("max_moves_per_minute", 10));
        this.maxSpawnsPerMinute = Math.max(0, behavior.getInt("max_spawns_per_minute", 5));
        this.villagerCooldownMinutes = Math.max(0, behavior.getInt("villager_cooldown_minutes", 30));
        ConfigurationSection logging = section.getConfigurationSection("logging");
        if (logging == null) {
            logging = section.createSection("logging");
        }
        this.economyDebug = logging.getBoolean("economy_debug", false);
    }

    public boolean enabled() {
        return enabled;
    }

    public int districtTileBlocks() {
        return districtTileBlocks;
    }

    public int districtSampleStep() {
        return districtSampleStep;
    }

    public long refreshIntervalMillis() {
        return refreshIntervalMillis;
    }

    public long overlayTtlSeconds() {
        return overlayTtlSeconds;
    }

    public List<Integer> overlayBuckets() {
        return overlayBuckets;
    }

    public int stationRadiusChunks() {
        return stationRadiusChunks;
    }

    public double weightLight() {
        return weightLight;
    }

    public double weightNature() {
        return weightNature;
    }

    public double weightAccess() {
        return weightAccess;
    }

    public double weightPollution() {
        return weightPollution;
    }

    public double weightCrowding() {
        return weightCrowding;
    }

    public double gdpPerWorker() {
        return gdpPerWorker;
    }

    public double happinessMultiplierMin() {
        return happinessMultiplierMin;
    }

    public double happinessMultiplierMax() {
        return happinessMultiplierMax;
    }

    public double lviMultiplierMin() {
        return lviMultiplierMin;
    }

    public double lviMultiplierMax() {
        return lviMultiplierMax;
    }

    public double accessMultiplierMin() {
        return accessMultiplierMin;
    }

    public double accessMultiplierMax() {
        return accessMultiplierMax;
    }

    public double indexWeightGdp() {
        return indexWeightGdp;
    }

    public double indexWeightLvi() {
        return indexWeightLvi;
    }

    public double indexWeightPopulation() {
        return indexWeightPopulation;
    }

    public double indexEmaAlpha() {
        return indexEmaAlpha;
    }

    public BehaviorMode behaviorMode() {
        return behaviorMode;
    }

    public int maxMovesPerMinute() {
        return maxMovesPerMinute;
    }

    public int maxSpawnsPerMinute() {
        return maxSpawnsPerMinute;
    }

    public int villagerCooldownMinutes() {
        return villagerCooldownMinutes;
    }

    public boolean economyDebug() {
        return economyDebug;
    }

    public enum BehaviorMode {
        DISABLED,
        TELEPORT,
        SPAWN_DESPAWN;

        static BehaviorMode safeParse(String value) {
            if (value == null) {
                return DISABLED;
            }
            try {
                return BehaviorMode.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return DISABLED;
            }
        }
    }
}
