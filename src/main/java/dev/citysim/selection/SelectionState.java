package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

public class SelectionState {
    public enum YMode { FULL, SPAN }
    public World world;
    public Location pos1;
    public Location pos2;
    public YMode yMode = YMode.FULL;
    public BukkitTask previewTask;

    public boolean ready() { return world != null && pos1 != null && pos2 != null; }

    public void reset() {
        cancelPreview();
        world = null;
        pos1 = null;
        pos2 = null;
        yMode = YMode.FULL;
    }

    public void cancelPreview() {
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
    }
}
