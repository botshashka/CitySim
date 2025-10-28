
package dev.simcity.papi;

import dev.simcity.city.City;
import dev.simcity.city.CityManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class SimCityExpansion extends PlaceholderExpansion {
    private final CityManager cityManager;

    public SimCityExpansion(CityManager cm) {
        this.cityManager = cm;
    }

    @Override public @NotNull String getIdentifier() { return "simcity"; }
    @Override public @NotNull String getAuthor() { return "you+gpt"; }
    @Override public @NotNull String getVersion() { return "0.1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // Supported:
        // %simcity_city% (current city name)
        // %simcity_pop% / employed / unemployed / happiness (current city)
        // %simcity_pop_<cityId>% (by id); same for employed/unemployed/happiness/cityname_
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

        if ("city".equals(key)) return city != null ? city.name : "";
        if (city == null) return "0";

        switch (key) {
            case "pop": case "population": return String.valueOf(city.population);
            case "employed": return String.valueOf(city.employed);
            case "unemployed": return String.valueOf(city.unemployed);
            case "happy": case "happiness": return String.valueOf(city.happiness);
            case "cityname": return city.name;
        }
        return null;
    }
}
