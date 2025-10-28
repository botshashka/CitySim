package dev.simcity.stats;

public class HappinessBreakdown {

    public int base = 50;

    public double lightPoints;
    public double employmentPoints;
    public double golemPoints;
    public double overcrowdingPenalty;
    public double jobDensityPoints;

    public double naturePoints;
    public double pollutionPenalty;
    public double bedsPoints;
    public double waterPoints;
    public double beautyPoints;

    public int total;

    public String dominantKey() {
        double[][] arr = new double[][]{
            { lightPoints, 1 },
            { employmentPoints, 2 },
            { golemPoints, 3 },
            { -overcrowdingPenalty, 4 },
            { jobDensityPoints, 5 },
            { naturePoints, 6 },
            { -pollutionPenalty, 7 },
            { bedsPoints, 8 },
            { waterPoints, 9 },
            { beautyPoints, 10 }
        };

        double maxMag = Double.NEGATIVE_INFINITY;
        int which = 0;
        double value = 0.0;

        for (double[] a : arr) {
            double v = a[0];
            double mag = Math.abs(v);
            if (mag > maxMag) {
                maxMag = mag;
                which = (int)a[1];
                value = v;
            }
        }

        boolean positive = value >= 0.0;
        switch (which) {
            case 1:  return positive ? "bright" : "dark";
            case 2:  return positive ? "employment_good" : "employment_bad";
            case 3:  return positive ? "golems_good" : "golems_bad";
            case 4:  return positive ? "crowding_good" : "crowding_bad";
            case 5:  return positive ? "jobs_good" : "jobs_bad";
            case 6:  return positive ? "nature_good" : "nature_bad";
            case 7:  return positive ? "pollution_good" : "pollution_bad";
            case 8:  return positive ? "beds_good" : "beds_bad";
            case 9:  return positive ? "water_good" : "water_bad";
            case 10: return positive ? "beauty_good" : "beauty_bad";
            default: return positive ? "default_good" : "default_bad";
        }
    }

    public String dominantMessage() {
        return defaultMessage(dominantKey());
    }

    private String defaultMessage(String key) {
        switch (key) {
            case "bright": return "Bright, well-lit streets";
            case "dark": return "Too dark — add lighting";
            case "employment_good": return "High employment";
            case "employment_bad": return "Unemployment is hurting morale";
            case "golems_good": return "Golems make it feel safe";
            case "golems_bad": return "Not enough protection";
            case "crowding_good": return "Comfortable spacing";
            case "crowding_bad": return "Overcrowded — expand the city";
            case "jobs_good": return "Plenty of workplaces";
            case "jobs_bad": return "Not enough job sites";
            case "nature_good": return "Green, lively parks";
            case "nature_bad": return "Too little greenery";
            case "pollution_good": return "Clean air and skies";
            case "pollution_bad": return "Smoggy, industrial feel";
            case "beds_good": return "Everyone has a bed";
            case "beds_bad": return "Not enough housing";
            case "water_good": return "Soothing water nearby";
            case "water_bad": return "No water features";
            case "beauty_good": return "Charming decorations";
            case "beauty_bad": return "Drab and undecorated";
            case "default_good": return "Citizens are content";
            case "default_bad": return "Citizens feel uneasy";
            default: return "Citizens are content";
        }
    }
}
