package dev.simcity.selection;

import org.bukkit.Location;
import org.bukkit.World;

public class SelectionState {
    public enum YMode { FULL, HEIGHTS }
    public World world;
    public Location pos1;
    public Location pos2;
    public YMode yMode = YMode.HEIGHTS;

    public boolean ready() { return world != null && pos1 != null && pos2 != null; }
}
