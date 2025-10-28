package dev.citysim.city;

import java.util.ArrayList;
import java.util.List;

public class City {
    public String id;
    public String name;
    public String world;
    public int priority = 0;
    public List<Cuboid> cuboids = new ArrayList<>();

    public int population = 0;
    public int employed = 0;
    public int unemployed = 0;
    public int happiness = 50;

    public boolean contains(org.bukkit.Location loc) {
        for (Cuboid c : cuboids) if (c.contains(loc)) return true;
        return false;
    }
}
