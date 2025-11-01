package dev.citysim.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SelectionTracker {
    private final Map<UUID, SelectionSession> selections = new ConcurrentHashMap<>();
    private volatile Listener listener;

    public enum YMode { FULL, SPAN }

    public interface Listener {
        void onSelectionUpdated(Player player);
        void onSelectionCleared(Player player);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Optional<SelectionSnapshot> snapshot(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        SelectionSession session = selections.get(player.getUniqueId());
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.snapshot(player.getUniqueId()));
    }

    public SelectionSnapshot updateCorner(Player player, Location location, boolean first) {
        if (player == null || location == null) {
            return null;
        }
        SelectionSession session = selections.computeIfAbsent(player.getUniqueId(), ignored -> new SelectionSession());
        session.setCorner(location, first);
        SelectionSnapshot snapshot = session.snapshot(player.getUniqueId());
        notifyUpdated(player);
        return snapshot;
    }

    public void setYMode(Player player, YMode mode) {
        if (player == null || mode == null) {
            return;
        }
        SelectionSession session = selections.computeIfAbsent(player.getUniqueId(), ignored -> new SelectionSession());
        if (session.setMode(mode)) {
            notifyUpdated(player);
        }
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        SelectionSession removed = selections.remove(player.getUniqueId());
        if (removed != null) {
            removed.clear();
            notifyCleared(player);
        }
    }

    public void clearAll() {
        selections.clear();
    }

    private void notifyUpdated(Player player) {
        Listener l = listener;
        if (l != null && player != null) {
            l.onSelectionUpdated(player);
        }
    }

    private void notifyCleared(Player player) {
        Listener l = listener;
        if (l != null && player != null) {
            l.onSelectionCleared(player);
        }
    }

    private static long computeHash(World world, BlockPos pos1, BlockPos pos2, YMode mode) {
        if (world == null || pos1 == null || pos2 == null) {
            return 0L;
        }
        long hash = 0xCBF29CE484222325L;
        hash = fnv(hash, world.getName());
        hash = fnv(hash, Math.min(pos1.x(), pos2.x()));
        hash = fnv(hash, Math.min(pos1.y(), pos2.y()));
        hash = fnv(hash, Math.min(pos1.z(), pos2.z()));
        hash = fnv(hash, Math.max(pos1.x(), pos2.x()));
        hash = fnv(hash, Math.max(pos1.y(), pos2.y()));
        hash = fnv(hash, Math.max(pos1.z(), pos2.z()));
        hash = fnv(hash, mode.ordinal());
        return hash;
    }

    private static long fnv(long hash, int value) {
        hash ^= value;
        hash *= 0x100000001B3L;
        return hash;
    }

    private static long fnv(long hash, String value) {
        if (value == null) {
            return hash;
        }
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private final class SelectionSession {
        private World world;
        private BlockPos pos1;
        private BlockPos pos2;
        private YMode mode = YMode.FULL;
        private long hash;
        private boolean dirty = true;
        private SelectionSnapshot cached;

        void setCorner(Location location, boolean first) {
            if (location == null) {
                return;
            }
            World locWorld = location.getWorld();
            if (world == null && locWorld != null) {
                world = locWorld;
            }
            BlockPos pos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (first) {
                pos1 = pos;
            } else {
                pos2 = pos;
            }
            dirty = true;
        }

        boolean setMode(YMode newMode) {
            if (newMode == null) {
                return false;
            }
            if (mode == newMode) {
                return false;
            }
            mode = newMode;
            dirty = true;
            return true;
        }

        SelectionSnapshot snapshot(UUID owner) {
            if (!dirty && cached != null) {
                return cached;
            }
            if (world == null || pos1 == null || pos2 == null) {
                cached = new SelectionSnapshot(owner, world, pos1, pos2, mode,
                        0, 0, 0, 0, 0, 0, 0L);
                dirty = false;
                hash = 0L;
                return cached;
            }
            int minX = Math.min(pos1.x(), pos2.x());
            int minY = Math.min(pos1.y(), pos2.y());
            int minZ = Math.min(pos1.z(), pos2.z());
            int maxX = Math.max(pos1.x(), pos2.x());
            int maxY = Math.max(pos1.y(), pos2.y());
            int maxZ = Math.max(pos1.z(), pos2.z());
            hash = computeHash(world, pos1, pos2, mode);
            cached = new SelectionSnapshot(owner, world, pos1, pos2, mode, minX, minY, minZ, maxX, maxY, maxZ, hash);
            dirty = false;
            return cached;
        }

        void clear() {
            world = null;
            pos1 = null;
            pos2 = null;
            cached = null;
            hash = 0L;
            dirty = true;
        }
    }

    public record SelectionSnapshot(UUID owner,
                                    World world,
                                    BlockPos pos1,
                                    BlockPos pos2,
                                    YMode mode,
                                    int minX,
                                    int minY,
                                    int minZ,
                                    int maxX,
                                    int maxY,
                                    int maxZ,
                                    long hash) {
        public boolean ready() {
            return world != null && pos1 != null && pos2 != null;
        }

        public Location pos1Location() {
            return toLocation(world, pos1);
        }

        public Location pos2Location() {
            return toLocation(world, pos2);
        }

        private Location toLocation(World world, BlockPos pos) {
            if (world == null || pos == null) {
                return null;
            }
            return new Location(world, pos.x(), pos.y(), pos.z());
        }
    }

    public record BlockPos(int x, int y, int z) {
    }
}
