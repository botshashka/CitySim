package dev.citysim.links;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Computes lightweight inter-city links based on proximity and station counts.
 */
public class LinkService {

    private static final double DEFAULT_STATION_FACTOR = 0.2;
    private static final double DEFAULT_DISTANCE_SCALE = 0.4;

    private final CityManager cityManager;

    private boolean enabled = false;
    private double linkDistance = 0.0;
    private double stationFactor = DEFAULT_STATION_FACTOR;
    private double distanceFactor = 0.0;

    public LinkService(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    public void reload(FileConfiguration config) {
        if (config == null) {
            this.enabled = false;
            this.linkDistance = 0.0;
            this.distanceFactor = 0.0;
            return;
        }
        this.enabled = config.getBoolean("links.enabled", false);
        int configuredDistance = config.getInt("links.link_distance_blocks", 0);
        this.linkDistance = Math.max(0, configuredDistance);
        if (linkDistance > 0.0) {
            this.distanceFactor = DEFAULT_DISTANCE_SCALE / linkDistance;
        } else {
            this.distanceFactor = 0.0;
        }
        this.stationFactor = DEFAULT_STATION_FACTOR;
    }

    public boolean isEnabled() {
        return enabled && linkDistance > 0.0;
    }

    public double getLinkDistance() {
        return linkDistance;
    }

    public List<CityLink> computeLinks(City city) {
        if (!isEnabled() || city == null || city.world == null || city.stations <= 0) {
            return List.of();
        }
        Point2D origin = centroidOf(city);
        if (origin == null) {
            return List.of();
        }

        List<CityLink> links = new ArrayList<>();
        for (City candidate : cityManager.all()) {
            if (candidate == null || candidate == city) {
                continue;
            }
            if (!Objects.equals(city.world, candidate.world)) {
                continue;
            }
            if (city.stations <= 0 || candidate.stations <= 0) {
                continue;
            }
            Point2D candidateCentroid = centroidOf(candidate);
            if (candidateCentroid == null) {
                continue;
            }
            double distance = origin.distance(candidateCentroid);
            if (linkDistance > 0.0 && distance > linkDistance) {
                continue;
            }
            double rawStrength = computeRawStrength(city.stations, candidate.stations, distance);
            if (rawStrength <= 0) {
                continue;
            }
            double logisticsMultiplier = Math.min(logisticsMultiplier(city), logisticsMultiplier(candidate));
            double effectiveStrength = rawStrength * logisticsMultiplier;
            int strength = (int) Math.round(effectiveStrength);
            if (strength <= 0) {
                continue;
            }
            links.add(new CityLink(candidate, distance, strength));
        }
        links.sort(Comparator
                .comparingInt(CityLink::strength)
                .reversed()
                .thenComparing(link -> link.neighbor().name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(link -> link.neighbor().id, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return List.copyOf(links);
    }

    public List<CityLink> topLinks(City city, int limit) {
        List<CityLink> links = computeLinks(city);
        if (limit < 0 || limit >= links.size()) {
            return links;
        }
        return List.copyOf(links.subList(0, limit));
    }

    public int linkCount(City city) {
        return computeLinks(city).size();
    }

    private double computeRawStrength(int stationsA, int stationsB, double distance) {
        double s = Math.max(0, stationsA) + Math.max(0, stationsB);
        double raw = stationFactor * s - distanceFactor * distance;
        double clamped = Math.max(0.0, Math.min(1.0, raw));
        return clamped * 100.0;
    }

    private Point2D centroidOf(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null) {
                continue;
            }
            if (city.world != null && cuboid.world != null && !Objects.equals(city.world, cuboid.world)) {
                continue;
            }
            minX = Math.min(minX, cuboid.minX);
            minZ = Math.min(minZ, cuboid.minZ);
            maxX = Math.max(maxX, cuboid.maxX);
            maxZ = Math.max(maxZ, cuboid.maxZ);
            found = true;
        }
        if (!found) {
            return null;
        }
        double centerX = (minX + maxX) / 2.0;
        double centerZ = (minZ + maxZ) / 2.0;
        return new Point2D(centerX, centerZ);
    }

    private double logisticsMultiplier(City city) {
        if (city == null) {
            return 1.0;
        }
        double multiplier = city.logisticsFundingMultiplier;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = city.lastBudgetSnapshot != null ? city.lastBudgetSnapshot.logisticsMultiplier : 1.0;
        }
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return 0.0;
        }
        if (multiplier > 1.0) {
            multiplier = 1.0;
        }
        return multiplier;
    }

    private record Point2D(double x, double z) {
        double distance(Point2D other) {
            return Math.hypot(x - other.x, z - other.z);
        }
    }
}
