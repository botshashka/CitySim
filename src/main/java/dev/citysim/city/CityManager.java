package dev.citysim.city;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import dev.citysim.budget.BudgetDefaults;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.*;
import java.util.logging.Level;
import java.util.concurrent.CopyOnWriteArrayList;

public class CityManager {
    private static final Type CITY_LIST_TYPE = new TypeToken<List<City>>(){}.getType();

    private final Plugin plugin;
    private final Map<String, City> byId = new LinkedHashMap<>();
    private final Map<String, List<City>> citiesByWorld = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File dataFile;
    private final List<CityManagerListener> listeners = new CopyOnWriteArrayList<>();

    public CityManager(Plugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "cities.json");
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public Collection<City> all() { return byId.values(); }
    public City get(String id) { return byId.get(id.toLowerCase(Locale.ROOT)); }

    public void addListener(CityManagerListener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
    }

    public void removeListener(CityManagerListener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

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
        c.taxRate = BudgetDefaults.DEFAULT_TAX_RATE;
        c.landTaxRate = BudgetDefaults.DEFAULT_LAND_TAX_RATE;
        c.treasury = 0.0;
        byId.put(id, c);
        verifyWorldIndexState("create " + id);
        notifyCityCreated(c);
        return c;
    }

    public City remove(String id) {
        if (id == null) {
            return null;
        }
        City removed = byId.remove(id.toLowerCase(Locale.ROOT));
        if (removed != null) {
            removeCityFromWorldIndex(removed);
            int index = 0;
            for (City city : byId.values()) {
                city.priority = index++;
            }
            verifyWorldIndexState("remove " + id);
            notifyCityRemoved(removed);
        }
        return removed;
    }

    public City rename(String id, String newName) {
        City city = get(id);
        if (city == null) {
            throw new IllegalArgumentException("City with id '" + id + "' does not exist");
        }

        String newId = slug(newName);
        if (newId.isEmpty()) {
            throw new IllegalArgumentException("City name must contain letters or numbers");
        }

        String oldId = city.id;
        if (!newId.equals(oldId) && byId.containsKey(newId)) {
            throw new IllegalArgumentException("City with id '" + newId + "' already exists");
        }

        city.name = newName;
        if (newId.equals(oldId)) {
            verifyWorldIndexState("rename " + oldId + " (no id change)");
            notifyCityUpdated(city);
            return city;
        }

        List<City> ordered = new ArrayList<>(byId.values());
        city.id = newId;

        byId.clear();
        for (City c : ordered) {
            byId.put(c.id, c);
        }

        verifyWorldIndexState("rename " + oldId + " -> " + newId);
        notifyCityRenamed(oldId, city);

        return city;
    }

    public int addCuboid(String id, Cuboid cuboid) {
        City c = get(id);
        if (c == null) {
            throw new IllegalArgumentException("City with id '" + id + "' does not exist");
        }
        if (cuboid.world == null) {
            throw new IllegalArgumentException("Cuboid world cannot be null");
        }
        if (cuboid.yMode == null) {
            cuboid.yMode = cuboid.fullHeight ? CuboidYMode.FULL : CuboidYMode.SPAN;
        }
        cuboid.fullHeight = cuboid.yMode == CuboidYMode.FULL;
        if (c.world != null && !c.world.equals(cuboid.world)) {
            throw new IllegalArgumentException("City '" + c.name + "' is bound to world " + c.world + ".");
        }
        World world = Bukkit.getWorld(cuboid.world);
        boolean fullHeight = cuboid.yMode == CuboidYMode.FULL || cuboid.isFullHeight(world);
        if (c.highrise && fullHeight) {
            throw new IllegalArgumentException("Highrise cities cannot contain cuboids with full Y mode.");
        }

        c.cuboids.add(cuboid);
        if (c.world == null) {
            c.world = cuboid.world;
        }
        addCityToWorldIndex(c);
        c.invalidateBlockScanCache();
        verifyWorldIndexState("addCuboid " + id);
        notifyCityUpdated(c);
        return c.cuboids.size();
    }

    public City setHighrise(String id, boolean highrise) {
        City city = get(id);
        if (city == null) {
            throw new IllegalArgumentException("City with id '" + id + "' does not exist");
        }

        if (highrise) {
            for (Cuboid cuboid : city.cuboids) {
                if (cuboid == null) continue;
                org.bukkit.World world = Bukkit.getWorld(cuboid.world);
                boolean full = cuboid.fullHeight || cuboid.isFullHeight(world);
                if (full) {
                    throw new IllegalArgumentException("City '" + city.name + "' has cuboids using full Y mode.");
                }
            }
        }

        city.highrise = highrise;
        city.invalidateBlockScanCache();
        notifyCityUpdated(city);
        return city;
    }

    public int removeCuboidsContaining(String id, Location location) {
        City city = get(id);
        if (city == null) {
            throw new IllegalArgumentException("City with id '" + id + "' does not exist");
        }
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location world cannot be null");
        }

        int before = city.cuboids.size();
        city.cuboids.removeIf(c -> c != null && c.contains(location));
        int removed = before - city.cuboids.size();
        if (city.cuboids.isEmpty()) {
            removeCityFromWorldIndex(city);
            city.world = null;
        } else if (removed > 0) {
            addCityToWorldIndex(city);
        }
        if (removed > 0) {
            city.invalidateBlockScanCache();
        }
        if (removed > 0) {
            verifyWorldIndexState("removeCuboidsContaining " + id);
            notifyCityUpdated(city);
        }
        return removed;
    }

    public City cityAt(Location loc) {
        if (loc == null) {
            return null;
        }

        org.bukkit.World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        String worldName = world.getName();
        List<City> candidates = citiesByWorld.get(worldName);
        if (candidates == null) {
            return null;
        }
        for (City c : candidates) if (c.contains(loc)) return c;
        return null;
    }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            try (Writer writer = Files.newBufferedWriter(dataFile.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(byId.values(), writer);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed saving cities: " + e.getMessage());
        }
    }

    public void load() {
        if (!dataFile.exists()) return;
        List<City> list;
        try {
            list = readCities(StandardCharsets.UTF_8);
        } catch (MalformedInputException malformedInputException) {
            Charset fallbackCharset = Charset.defaultCharset();
            if (fallbackCharset.equals(StandardCharsets.UTF_8)) {
                plugin.getLogger().log(Level.SEVERE, "Failed loading cities: " + malformedInputException.getMessage(), malformedInputException);
                return;
            }

            plugin.getLogger().log(Level.WARNING, "Failed reading cities.json as UTF-8, retrying with " + fallbackCharset.displayName(), malformedInputException);
            try {
                list = readCities(fallbackCharset);
            } catch (JsonParseException e) {
                byId.clear();
                citiesByWorld.clear();
                plugin.getLogger().warning("Failed parsing cities data '" + dataFile.getName() + "': " + e.getMessage() + ". Starting with an empty city list.");
                return;
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed loading cities with fallback charset " + fallbackCharset.displayName() + ": " + e.getMessage(), e);
                return;
            }
        } catch (JsonParseException e) {
            byId.clear();
            citiesByWorld.clear();
            plugin.getLogger().warning("Failed parsing cities data '" + dataFile.getName() + "': " + e.getMessage() + ". Starting with an empty city list.");
            return;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed loading cities: " + e.getMessage());
            return;
        }

        byId.clear();
        citiesByWorld.clear();
        if (list != null) {
            for (City c : list) {
                List<Cuboid> sanitized = new ArrayList<>();
                if (c.cuboids != null) {
                    for (Cuboid cuboid : c.cuboids) {
                        if (cuboid == null || cuboid.world == null) {
                            continue;
                        }
                        if (!cuboid.fullHeight) {
                            org.bukkit.World world = Bukkit.getWorld(cuboid.world);
                            if (cuboid.isFullHeight(world)) {
                                cuboid.fullHeight = true;
                            }
                        }
                        if (cuboid.yMode == null) {
                            cuboid.yMode = cuboid.fullHeight ? CuboidYMode.FULL : CuboidYMode.SPAN;
                        }
                        sanitized.add(cuboid);
                    }
                }
                c.cuboids = sanitized;
                if (c.cuboids.isEmpty()) {
                    c.world = null;
                }
                if (!Double.isFinite(c.taxRate) || c.taxRate < 0.0) {
                    c.taxRate = BudgetDefaults.DEFAULT_TAX_RATE;
                }
                if (!Double.isFinite(c.landTaxRate) || c.landTaxRate < 0.0) {
                    c.landTaxRate = BudgetDefaults.DEFAULT_LAND_TAX_RATE;
                }
                if (!Double.isFinite(c.treasury)) {
                    c.treasury = 0.0;
                }
                byId.put(c.id, c);
                addCityToWorldIndex(c);
            }
        }
        verifyWorldIndexState("load");
    }

    private List<City> readCities(Charset charset) throws IOException, JsonParseException {
        try (Reader reader = Files.newBufferedReader(dataFile.toPath(), charset)) {
            return gson.fromJson(reader, CITY_LIST_TYPE);
        }
    }

    private void notifyCityCreated(City city) {
        for (CityManagerListener listener : listeners) {
            try {
                listener.onCityCreated(city);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityManager listener threw during cityCreated", ex);
            }
        }
    }

    private void notifyCityRemoved(City city) {
        for (CityManagerListener listener : listeners) {
            try {
                listener.onCityRemoved(city);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityManager listener threw during cityRemoved", ex);
            }
        }
    }

    private void notifyCityRenamed(String previousId, City city) {
        for (CityManagerListener listener : listeners) {
            try {
                listener.onCityRenamed(previousId, city);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityManager listener threw during cityRenamed", ex);
            }
        }
    }

    private void notifyCityUpdated(City city) {
        for (CityManagerListener listener : listeners) {
            try {
                listener.onCityUpdated(city);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "CityManager listener threw during cityUpdated", ex);
            }
        }
    }

    private static String slug(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("(^_|_$)","");
    }

    private void addCityToWorldIndex(City city) {
        if (city == null) return;
        if (city.world == null || city.cuboids.isEmpty()) return;
        List<City> list = citiesByWorld.computeIfAbsent(city.world, key -> new ArrayList<>());
        if (!list.contains(city)) {
            list.add(city);
        }
    }

    private void removeCityFromWorldIndex(City city) {
        if (city == null) return;
        if (city.world == null) return;
        List<City> list = citiesByWorld.get(city.world);
        if (list != null) {
            list.remove(city);
            if (list.isEmpty()) {
                citiesByWorld.remove(city.world);
            }
        }
    }

    private void verifyWorldIndexState(String context) {
        if (!plugin.getLogger().isLoggable(Level.FINE)) {
            return;
        }

        Set<String> missing = new LinkedHashSet<>();
        for (City city : byId.values()) {
            if (city.world == null || city.cuboids.isEmpty()) continue;
            List<City> indexed = citiesByWorld.get(city.world);
            if (indexed == null || !indexed.contains(city)) {
                missing.add(city.id + "@" + city.world);
            }
        }

        Set<String> extras = new LinkedHashSet<>();
        for (Map.Entry<String, List<City>> entry : citiesByWorld.entrySet()) {
            String world = entry.getKey();
            for (City indexed : entry.getValue()) {
                if (!Objects.equals(indexed.world, world) || indexed.cuboids.isEmpty() || !byId.containsKey(indexed.id)) {
                    extras.add(indexed.id + "@" + world);
                }
            }
        }

        if (missing.isEmpty() && extras.isEmpty()) {
            plugin.getLogger().fine("City world index verified after " + context + ": " + summarizeWorldIndex());
        } else {
            plugin.getLogger().warning("City world index inconsistency after " + context + ": missing=" + missing + ", extras=" + extras);
        }
    }

    private String summarizeWorldIndex() {
        if (citiesByWorld.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        Iterator<Map.Entry<String, List<City>>> iterator = citiesByWorld.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<City>> entry = iterator.next();
            sb.append(entry.getKey()).append("=");
            List<String> ids = new ArrayList<>();
            for (City city : entry.getValue()) {
                ids.add(city.id);
            }
            sb.append(ids);
            if (iterator.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
