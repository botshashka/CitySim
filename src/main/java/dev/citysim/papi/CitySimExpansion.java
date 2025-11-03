package dev.citysim.papi;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.economy.CityEconomy;
import dev.citysim.economy.DistrictStats;
import dev.citysim.economy.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class CitySimExpansion extends PlaceholderExpansion {
    private final CityManager cityManager;
    private final EconomyService economyService;

    public CitySimExpansion(CityManager cm, EconomyService economyService) {
        this.cityManager = cm;
        this.economyService = economyService;
    }

    @Override public @NotNull String getIdentifier() { return "citysim"; }
    @Override public @NotNull String getAuthor() { return "you+gpt"; }
    @Override public @NotNull String getVersion() { return "0.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Supported:
        // %citysim_city% (current city name)
        // %citysim_pop% / employed / unemployed / happiness (current city)
        // %citysim_pop_<cityId>% (by id); same for employed/unemployed/happiness/cityname_
        String[] parts = params.split("_", 2);
        String key = parts[0].toLowerCase();

        City city = null;
        if (parts.length == 2) {
            // by id
            city = cityManager.get(parts[1]);
        } else if (player != null && player.isOnline()) {
            var p = player.getPlayer();
            if (p != null) city = cityManager.cityAt(p.getLocation());
        }

        if ("city".equals(key) || "cityname".equals(key)) {
            return city != null ? city.name : "";
        }
        if (city == null) return "0";

        boolean ghostTown = city.isGhostTown();

        switch (key) {
            case "pop": case "population": return String.valueOf(city.population);
            case "employed": return String.valueOf(city.employed);
            case "unemployed": return String.valueOf(city.unemployed);
            case "happy": case "happiness": return ghostTown ? "" : String.valueOf(city.happiness);
            case "cityname": return city.name;
            case "lvi":
            case "lviavg":
            case "lvi_avg":
                return formatLvi(city);
            case "lvi_tile":
                return formatTileLvi(player, city);
            case "gdp":
                return formatGdp(city, false);
            case "gdppc":
            case "gdp_per_capita":
                return formatGdp(city, true);
            case "index":
            case "cityindex":
                return formatIndex(city);
        }
        return null;
    }

    private String formatLvi(City city) {
        if (economyService == null || !economyService.isEnabled()) {
            return "";
        }
        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            return "";
        }
        return String.format(java.util.Locale.US, "%.1f", economy.lviAverage());
    }

    private String formatTileLvi(OfflinePlayer player, City city) {
        if (economyService == null || !economyService.isEnabled() || player == null || !player.isOnline()) {
            return "";
        }
        var p = player.getPlayer();
        if (p == null) {
            return "";
        }
        DistrictStats stats = economyService.findDistrict(city, p.getLocation()).orElse(null);
        if (stats == null) {
            return "";
        }
        return String.format(java.util.Locale.US, "%.1f", stats.landValue0to100());
    }

    private String formatGdp(City city, boolean perCapita) {
        if (economyService == null || !economyService.isEnabled()) {
            return "";
        }
        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            return "";
        }
        double value = perCapita ? economy.gdpPerCapita() : economy.gdp();
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private String formatIndex(City city) {
        if (economyService == null || !economyService.isEnabled()) {
            return "";
        }
        CityEconomy economy = economyService.economy(city.id);
        if (economy == null) {
            return "";
        }
        return String.format(java.util.Locale.US, "%.1f", economy.indexPrice());
    }
}
