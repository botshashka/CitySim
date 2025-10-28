package dev.citysim.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Type;
import java.text.Normalizer;
import java.util.*;

public class CityManager {
    private final Plugin plugin;
    private final Map<String, City> byId = new LinkedHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File dataFile;

    public CityManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "cities.json");
    }

    public Collection<City> all() { return byId.values(); }
    public City get(String id) { return byId.get(id.toLowerCase(Locale.ROOT)); }

    public City create(String name) {
        String id = slug(name);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("City name must contain letters or numbers");
        }
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("City with id '" + id + "' already exists");
        }
        City c = new City();
        c.id = id;
        c.name = name;
        c.priority = byId.size();
        byId.put(id, c);
        return c;
    }

    public void addCuboid(String id, Cuboid cuboid) {
        City c = get(id);
        if (c != null) {
            c.cuboids.add(cuboid);
            if (c.world == null) c.world = cuboid.world;
        }
    }

    public City cityAt(Location loc) {
        for (City c : byId.values()) if (c.contains(loc)) return c;
        return null;
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            try (FileWriter fw = new FileWriter(dataFile)) {
                gson.toJson(byId.values(), fw);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed saving cities: " + e.getMessage());
        }
    }

    public void load() {
        if (!dataFile.exists()) return;
        try (FileReader fr = new FileReader(dataFile)) {
            Type listType = new TypeToken<List<City>>(){}.getType();
            List<City> list = gson.fromJson(fr, listType);
            byId.clear();
            if (list != null) for (City c : list) byId.put(c.id, c);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed loading cities: " + e.getMessage());
        }
    }

    private static String slug(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
    }
}
