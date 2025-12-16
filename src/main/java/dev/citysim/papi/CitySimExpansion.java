package dev.citysim.papi;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class CitySimExpansion extends PlaceholderExpansion {
    private final CityManager cityManager;

    public CitySimExpansion(CityManager cm) {
        this.cityManager = cm;
    }

    @Override public @NotNull String getIdentifier() { return "citysim"; }
    @Override public @NotNull String getAuthor() { return "botshashka"; }
    @Override public @NotNull String getVersion() { return "0.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Supported:
        // %citysim_city% (current city name)
        // %citysim_pop% / employed / unemployed / prosperity (current city)
        // %citysim_pop_<cityId>% (by id); same for employed/unemployed/prosperity/cityname_
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
            case "prosperity": return ghostTown ? "" : String.valueOf(city.prosperity);
            case "cityname": return city.name;
        }
        return null;
    }
}
