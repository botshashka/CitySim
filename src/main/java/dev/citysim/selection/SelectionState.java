package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

public class SelectionState {
    public enum YMode { FULL, SPAN }
    public World world;
    public Location pos1;
    public Location pos2;
    public YMode yMode = YMode.FULL;
    public BukkitTask previewTask;
    public final PreviewCache preview = new PreviewCache();

    public boolean ready() { return world != null && pos1 != null && pos2 != null; }

    public void reset() {
        cancelPreview();
        world = null;
        pos1 = null;
        pos2 = null;
        yMode = YMode.FULL;
        preview.reset();
    }

    public void cancelPreview() {
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
    }

    public void markPreviewDirty() {
        preview.reset();
    }

    public static final class PreviewCache {
        List<Location> outline = Collections.emptyList();
        final Queue<Location> queue = new ArrayDeque<>();
        BoundsSnapshot bounds;
        int viewerY = Integer.MIN_VALUE;

        void reset() {
            outline = Collections.emptyList();
            queue.clear();
            bounds = null;
            viewerY = Integer.MIN_VALUE;
        }
    }

    public record BoundsSnapshot(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
}
