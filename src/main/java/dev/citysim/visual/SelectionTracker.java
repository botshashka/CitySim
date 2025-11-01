package dev.citysim.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.citysim.visual.ShapeSampler.SelectionSnapshot;

/**
 * Maintains wand selections for every player and notifies the visualization service when changes occur.
 */
public final class SelectionTracker {

    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final VisualizationService visualizationService;

    public SelectionTracker(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    public Selection get(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
    }

    public Optional<Selection> getIfPresent(Player player) {
        return Optional.ofNullable(selections.get(player.getUniqueId()));
    }

    public void clear(Player player) {
        Selection selection = selections.remove(player.getUniqueId());
        if (selection != null) {
            selection.reset();
        }
        visualizationService.updateSelectionView(player);
    }

    public void setCorner(Player player, boolean first, Location location) {
        if (location == null) {
            return;
        }
        Selection selection = get(player);
        if (selection.world == null) {
            selection.world = location.getWorld();
        }
        if (selection.world != null && !Objects.equals(selection.world, location.getWorld())) {
            return;
        }
        if (first) {
            selection.pos1 = location.clone();
        } else {
            selection.pos2 = location.clone();
        }
        selection.updateHash();
        visualizationService.updateSelectionView(player);
    }

    public void setMode(Player player, YMode mode) {
        Selection selection = get(player);
        if (mode != null && selection.mode != mode) {
            selection.mode = mode;
            selection.updateHash();
            visualizationService.updateSelectionView(player);
        }
    }

    public static SelectionSnapshot toShapeSnapshot(Selection selection) {
        if (selection == null || !selection.ready()) {
            return null;
        }
        double minX = Math.min(selection.pos1.getBlockX(), selection.pos2.getBlockX());
        double minY = Math.min(selection.pos1.getBlockY(), selection.pos2.getBlockY());
        double minZ = Math.min(selection.pos1.getBlockZ(), selection.pos2.getBlockZ());
        double maxX = Math.max(selection.pos1.getBlockX(), selection.pos2.getBlockX()) + 1;
        double maxY = Math.max(selection.pos1.getBlockY(), selection.pos2.getBlockY()) + 1;
        double maxZ = Math.max(selection.pos1.getBlockZ(), selection.pos2.getBlockZ()) + 1;
        return new SelectionSnapshot(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static final class Selection {
        private World world;
        private Location pos1;
        private Location pos2;
        private YMode mode = YMode.FULL;
        private long hash;

        private Selection() {
        }

        public boolean ready() {
            return world != null && pos1 != null && pos2 != null;
        }

        public void reset() {
            pos1 = null;
            pos2 = null;
            world = null;
            mode = YMode.FULL;
            updateHash();
        }

        public World world() {
            return world;
        }

        public Location pos1() {
            return pos1;
        }

        public Location pos2() {
            return pos2;
        }

        public YMode mode() {
            return mode;
        }

        public long hash() {
            return hash;
        }

        private void updateHash() {
            long result = 17L;
            result = result * 31L + (world == null ? 0L : world.getName().hashCode());
            result = result * 31L + (pos1 == null ? 0L : (pos1.getBlockX() * 31L + pos1.getBlockY() * 17L + pos1.getBlockZ()));
            result = result * 31L + (pos2 == null ? 0L : (pos2.getBlockX() * 31L + pos2.getBlockY() * 17L + pos2.getBlockZ()));
            result = result * 31L + mode.ordinal();
            this.hash = result;
        }
    }
}
