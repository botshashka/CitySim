package dev.citysim.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SelectionTracker {
    public enum Corner { POS1, POS2 }

    private final Map<UUID, SelectionState> selections = new ConcurrentHashMap<>();
    private final VisualizationService visualizationService;

    public SelectionTracker(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    public SelectionState stateFor(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), ignored -> new SelectionState(player.getUniqueId()));
    }

    public Optional<SelectionSnapshot> snapshot(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        SelectionState state = selections.get(player.getUniqueId());
        if (state == null || !state.ready()) {
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        SelectionState removed = selections.remove(player.getUniqueId());
        if (removed != null) {
            removed.reset();
        }
        visualizationService.updateSelectionView(player);
    }

    public void handleCorner(Player player, Location location, Corner corner) {
        if (player == null || location == null) {
            return;
        }
        SelectionState state = stateFor(player);
        state.updateWorld(location.getWorld());
        if (corner == Corner.POS1) {
            state.setPos1(location);
        } else {
            state.setPos2(location);
        }
        visualizationService.updateSelectionView(player);
    }

    public void setMode(Player player, YMode mode) {
        if (player == null || mode == null) {
            return;
        }
        SelectionState state = stateFor(player);
        if (state.setMode(mode)) {
            visualizationService.updateSelectionView(player);
        }
    }

    public void handlePlayerQuit(Player player) {
        clear(player);
        visualizationService.handlePlayerQuit(player);
    }

    public static final class SelectionState {
        private final UUID playerId;
        private World world;
        private Location pos1;
        private Location pos2;
        private YMode mode = YMode.FULL;
        private long hash;

        private SelectionState(UUID playerId) {
            this.playerId = playerId;
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

        public boolean ready() {
            return world != null && pos1 != null && pos2 != null;
        }

        public long hash() {
            return hash;
        }

        private void updateWorld(World newWorld) {
            if (newWorld == null) {
                return;
            }
            if (world == null) {
                world = newWorld;
                return;
            }
            if (!Objects.equals(world.getUID(), newWorld.getUID())) {
                world = newWorld;
                pos1 = null;
                pos2 = null;
            }
        }

        private void setPos1(Location loc) {
            pos1 = loc.clone();
            recomputeHash();
        }

        private void setPos2(Location loc) {
            pos2 = loc.clone();
            recomputeHash();
        }

        private boolean setMode(YMode newMode) {
            if (newMode == null || newMode == mode) {
                return false;
            }
            mode = newMode;
            recomputeHash();
            return true;
        }

        private void recomputeHash() {
            hash = computeHash();
        }

        private long computeHash() {
            int pos1X = pos1 != null ? pos1.getBlockX() : 0;
            int pos1Y = pos1 != null ? pos1.getBlockY() : 0;
            int pos1Z = pos1 != null ? pos1.getBlockZ() : 0;
            int pos2X = pos2 != null ? pos2.getBlockX() : 0;
            int pos2Y = pos2 != null ? pos2.getBlockY() : 0;
            int pos2Z = pos2 != null ? pos2.getBlockZ() : 0;
            String worldName = world != null ? world.getName() : "";
            return Objects.hash(worldName, pos1X, pos1Y, pos1Z, pos2X, pos2Y, pos2Z, mode);
        }

        public SelectionSnapshot snapshot() {
            if (!ready()) {
                return null;
            }
            int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
            int minY;
            int maxY;
            if (mode == YMode.FULL) {
                minY = world.getMinHeight();
                maxY = world.getMaxHeight() - 1;
            } else {
                minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
                maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
            }
            return new SelectionSnapshot(playerId,
                    world,
                    pos1.clone(),
                    pos2.clone(),
                    mode,
                    hash,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ);
        }

        private void reset() {
            world = null;
            pos1 = null;
            pos2 = null;
            mode = YMode.FULL;
            hash = 0L;
        }
    }

    public record SelectionSnapshot(UUID playerId,
                                    World world,
                                    Location pos1,
                                    Location pos2,
                                    YMode mode,
                                    long hash,
                                    int minX,
                                    int minY,
                                    int minZ,
                                    int maxX,
                                    int maxY,
                                    int maxZ) {
        public boolean ready() {
            return world != null && pos1 != null && pos2 != null;
        }

        public SelectionBounds bounds() {
            if (!ready()) {
                return null;
            }
            return new SelectionBounds(world.getName(), minX, minY, minZ, maxX, maxY, maxZ, mode, hash);
        }
    }
}
