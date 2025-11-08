package dev.citysim.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.java.JavaPlugin;

public class ProsperityBreakdown {

    public int base = 50;

    public double lightPoints;
    public double employmentPoints;
    public double overcrowdingPenalty;
    public double naturePoints;
    public double pollutionPenalty;
    public double housingPoints;
    public double transitPoints;

    public int total;

    private boolean ghostTown;

    public boolean isGhostTown() {
        return ghostTown;
    }

    public void setGhostTown(boolean ghostTown) {
        this.ghostTown = ghostTown;
    }

    public ProsperityBreakdown asGhostTown() {
        setGhostTown(true);
        return this;
    }

    public String dominantKey() {
        if (ghostTown) {
            return "ghost_town";
        }
        Contribution best = contributions().stream()
                .max(Comparator.comparingDouble(Contribution::magnitude))
                .orElse(null);

        if (best == null || best.magnitude() <= 0.0001) {
            double mood = total > 0 ? total : base;
            return mood >= base ? "default_good" : "default_bad";
        }

        return best.key();
    }

    public String dominantMessage() {
        return defaultMessageFor(dominantKey());
    }

    public String pickWeightedMessageKey() {
        if (ghostTown) {
            return "ghost_town";
        }
        List<Contribution> sorted = contributions();
        sorted.sort(Comparator.comparingDouble(Contribution::magnitude).reversed());

        List<Contribution> shortlist = new ArrayList<>();
        for (Contribution c : sorted) {
            if (c.magnitude() <= 0.0001) {
                continue;
            }
            shortlist.add(c);
            if (shortlist.size() >= 3) {
                break;
            }
        }

        if (shortlist.isEmpty()) {
            double mood = total > 0 ? total : base;
            return mood >= base ? "default_good" : "default_bad";
        }

        double totalWeight = 0.0;
        for (Contribution contribution : shortlist) {
            totalWeight += contribution.weight();
        }

        if (totalWeight <= 0.0) {
            return shortlist.get(0).key();
        }

        double target = ThreadLocalRandom.current().nextDouble(totalWeight);
        double running = 0.0;
        for (Contribution contribution : shortlist) {
            running += contribution.weight();
            if (target <= running) {
                return contribution.key();
            }
        }

        return shortlist.get(0).key();
    }

    private List<Contribution> contributions() {
        List<Contribution> list = new ArrayList<>();
        list.add(new Contribution(lightPoints, "bright", "dark"));
        list.add(new Contribution(employmentPoints, "employment_good", "employment_bad"));
        list.add(new Contribution(-overcrowdingPenalty, "crowding_good", "crowding_bad"));
        list.add(new Contribution(naturePoints, "nature_good", "nature_bad"));
        list.add(new Contribution(-pollutionPenalty, "pollution_good", "pollution_bad"));
        list.add(new Contribution(housingPoints, "housing_good", "housing_bad"));
        list.add(new Contribution(transitPoints, "transit_good", "transit_bad"));
        return list;
    }

    private static class Contribution {
        private static final double NEGATIVE_WEIGHT_BIAS = 2.5;

        private final double value;
        private final String positiveKey;
        private final String negativeKey;

        private Contribution(double value, String positiveKey, String negativeKey) {
            this.value = value;
            this.positiveKey = positiveKey;
            this.negativeKey = negativeKey;
        }

        private double magnitude() {
            return Math.abs(value);
        }

        private boolean positive() {
            return value >= 0.0;
        }

        private String key() {
            return positive() ? positiveKey : negativeKey;
        }

        private double weight() {
            double magnitude = magnitude();
            if (magnitude == 0.0) {
                return 0.0;
            }
            double bias = positive() ? 1.0 : NEGATIVE_WEIGHT_BIAS;
            return magnitude * bias;
        }
    }

    public static String defaultMessageFor(String key) {
        String path = "titles.messages." + key;
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(ProsperityBreakdown.class);
        Configuration defaults = plugin.getConfig().getDefaults();
        if (defaults != null) {
            List<String> defaultsList = defaults.getStringList(path);
            List<String> filteredDefaults = defaultsList.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (!filteredDefaults.isEmpty()) {
                return pickRandom(filteredDefaults);
            }

            String fallback = defaults.getString(path);
            if (fallback != null && !fallback.isBlank()) {
                return fallback.trim();
            }
        }

        boolean negativeMood = key.endsWith("_bad") || "dark".equals(key);
        return negativeMood ? "Citizens feel uneasy" : "Citizens are content";
    }

    private static String pickRandom(List<String> options) {
        if (options.isEmpty()) {
            return "Citizens are content";
        }
        int idx = ThreadLocalRandom.current().nextInt(options.size());
        return options.get(idx);
    }
}
