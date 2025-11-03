package dev.citysim.papi;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.economy.CityEconomy;
import dev.citysim.economy.DistrictKey;
import dev.citysim.economy.DistrictStats;
import dev.citysim.economy.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
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
        // %citysim_lvi_avg% (current city's LVI average)
        // %citysim_lvi_tile% (current tile LVI)
        // %citysim_gdp% / %citysim_gdppc% / %citysim_index%
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

        if ("lvi".equals(key) || "lviavg".equals(key) || "lviaverage".equals(key)) {
            return formatEconomy(city, CityEconomy::lviAverage);
        }
        if ("gdp".equals(key)) {
            return formatEconomy(city, CityEconomy::gdp);
        }
        if ("gdppc".equals(key)) {
            return formatEconomy(city, CityEconomy::gdpPerCapita);
        }
        if ("index".equals(key)) {
            return formatEconomy(city, CityEconomy::indexPrice);
        }
        if ("lvitile".equals(key) || "lvi_tile".equals(key)) {
            return formatTileLvi(player);
        }
        if (city == null) return "0";

        boolean ghostTown = city.isGhostTown();

        switch (key) {
            case "pop": case "population": return String.valueOf(city.population);
            case "employed": return String.valueOf(city.employed);
            case "unemployed": return String.valueOf(city.unemployed);
            case "happy": case "happiness": return ghostTown ? "" : String.valueOf(city.happiness);
            case "cityname": return city.name;
        }
        return null;
    }

    private String formatEconomy(City city, java.util.function.ToDoubleFunction<CityEconomy> extractor) {
        if (economyService == null || city == null) {
            return "";
        }
        CityEconomy economy = economyService.cityEconomy(city.id);
        if (economy == null) {
            return "";
        }
        double value = extractor.applyAsDouble(economy);
        if (!Double.isFinite(value)) {
            return "";
        }
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private String formatTileLvi(OfflinePlayer player) {
        if (economyService == null || player == null) {
            return "";
        }
        if (!player.isOnline()) {
            return "";
        }
        Player online = player.getPlayer();
        if (online == null || online.getWorld() == null) {
            return "";
        }
        String cityId = findCityId(online);
        if (cityId == null) {
            return "";
        }
        int tileBlocks = Math.max(16, economyService.settings().districtTileBlocks());
        int blockX = online.getLocation().getBlockX();
        int blockZ = online.getLocation().getBlockZ();
        int minBlockX = blockX - Math.floorMod(blockX, tileBlocks);
        int minBlockZ = blockZ - Math.floorMod(blockZ, tileBlocks);
        DistrictKey key = new DistrictKey(online.getWorld().getName(), minBlockX >> 4, minBlockZ >> 4);
        DistrictStats stats = economyService.grid(cityId).get(key);
        if (stats == null) {
            return "";
        }
        return String.format(java.util.Locale.US, "%.2f", stats.landValueRaw());
    }

    private String findCityId(Player player) {
        City city = cityManager.cityAt(player.getLocation());
        return city != null ? city.id : null;
    }
}
