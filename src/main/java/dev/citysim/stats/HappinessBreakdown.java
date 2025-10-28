package dev.citysim.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class HappinessBreakdown {

    public int base = 50;

    public double lightPoints;
    public double employmentPoints;
    public double safetyPoints;
    public double overcrowdingPenalty;
    public double naturePoints;
    public double pollutionPenalty;
    public double housingPoints;
    public double waterPoints;
    public double beautyPoints;

    public int total;

    public String dominantKey() {
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
        list.add(new Contribution(safetyPoints, "safety_good", "safety_bad"));
        list.add(new Contribution(-overcrowdingPenalty, "crowding_good", "crowding_bad"));
        list.add(new Contribution(naturePoints, "nature_good", "nature_bad"));
        list.add(new Contribution(-pollutionPenalty, "pollution_good", "pollution_bad"));
        list.add(new Contribution(housingPoints, "housing_good", "housing_bad"));
        list.add(new Contribution(waterPoints, "water_good", "water_bad"));
        list.add(new Contribution(beautyPoints, "beauty_good", "beauty_bad"));
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
        String[] options;
        switch (key) {
            case "bright":
                options = new String[]{
                        "Bright, well-lit streets",
                        "Lanterns chase away the night",
                        "No shadows left for trouble to hide"
                };
                break;
            case "dark":
                options = new String[]{
                        "Dark streets feel unsafe",
                        "Too many corners lost to darkness",
                        "Citizens whisper about unlit alleys"
                };
                break;
            case "employment_good":
                options = new String[]{
                        "High employment keeps spirits up",
                        "Every villager has meaningful work",
                        "Jobs are plentiful and morale is high"
                };
                break;
            case "employment_bad":
                options = new String[]{
                        "Rampant unemployment angers citizens",
                        "Idle hands are stirring frustration",
                        "Too many villagers are out of work"
                };
                break;
            case "safety_good":
                options = new String[]{
                        "Guards make it feel safe",
                        "Patrolling defenders keep danger at bay",
                        "Citizens salute their protectors"
                };
                break;
            case "safety_bad":
                options = new String[]{
                        "Citizens feel unprotected",
                        "Where are the defenders when night falls?",
                        "Villagers fear monsters without guardians"
                };
                break;
            case "crowding_good":
                options = new String[]{
                        "Comfortable spacing",
                        "Plenty of room to breathe",
                        "Homes feel cozy without feeling cramped"
                };
                break;
            case "crowding_bad":
                options = new String[]{
                        "Overcrowded — expand the city",
                        "Cramped housing sparks complaints",
                        "Citizens are stacked on top of each other"
                };
                break;
            case "nature_good":
                options = new String[]{
                        "Green, lively parks",
                        "Trees and flowers lift everyone's mood",
                        "Nature weaves through every street"
                };
                break;
            case "nature_bad":
                options = new String[]{
                        "Concrete jungle — residents miss nature",
                        "Citizens crave trees and gardens",
                        "A barren city leaves spirits low"
                };
                break;
            case "pollution_good":
                options = new String[]{
                        "Clean air and skies",
                        "Fresh breezes sweep the city",
                        "Clear skies keep lungs happy"
                };
                break;
            case "pollution_bad":
                options = new String[]{
                        "Polluted air is choking the city",
                        "Smog clouds every sunrise",
                        "Sooty air keeps citizens indoors"
                };
                break;
            case "housing_good":
                options = new String[]{
                        "Everyone has a place to sleep",
                        "Every villager rests easy at night",
                        "Comfortable housing keeps morale high"
                };
                break;
            case "housing_bad":
                options = new String[]{
                        "Homelessness is spreading",
                        "Villagers curl up on floors without shelter",
                        "Citizens are desperate for housing"
                };
                break;
            case "water_good":
                options = new String[]{
                        "Soothing water nearby",
                        "Fountains and canals cool the air",
                        "Water features calm worried minds"
                };
                break;
            case "water_bad":
                options = new String[]{
                        "Parched city — add water",
                        "Residents thirst for fountains",
                        "Dry plazas leave tempers hot"
                };
                break;
            case "beauty_good":
                options = new String[]{
                        "Charming decorations",
                        "Artful touches delight citizens",
                        "Beauty blooms on every corner"
                };
                break;
            case "beauty_bad":
                options = new String[]{
                        "Bleak, uninspired streets",
                        "Drab blocks sap citizen pride",
                        "The city longs for artistic flair"
                };
                break;
            case "default_good":
                options = new String[]{
                        "Citizens are content",
                        "Life is peaceful in the city",
                        "Villagers seem pleased overall"
                };
                break;
            case "default_bad":
                options = new String[]{
                        "Citizens feel uneasy",
                        "Whispers of discontent spread",
                        "Morale is slipping across the city"
                };
                break;
            default:
                options = new String[]{
                        "Citizens are content",
                        "Life is peaceful in the city"
                };
                break;
        }
        return pickRandom(options);
    }

    private static String pickRandom(String[] options) {
        if (options.length == 0) {
            return "Citizens are content";
        }
        int idx = ThreadLocalRandom.current().nextInt(options.length);
        return options[idx];
    }
}
