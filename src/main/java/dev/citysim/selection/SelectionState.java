package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;

public class SelectionState {
    public enum YMode { FULL, SPAN }
    public World world;
    public Location pos1;
    public Location pos2;
    public YMode yMode = YMode.FULL;

    public boolean ready() { return world != null && pos1 != null && pos2 != null; }

    public void reset() {
        world = null;
        pos1 = null;
        pos2 = null;
        yMode = YMode.FULL;
    }
}
