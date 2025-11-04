package dev.citysim.migration;

import dev.citysim.city.City;
import dev.citysim.integration.traincarts.TrainCartsStationService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.data.type.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class StationPlatformResolver implements Listener {

    private static final int BLOCK_INVALIDATION_RADIUS = 4;
    private static final int RAIL_INVALIDATION_RADIUS = 5;

    private final Plugin plugin;
    private volatile TrainCartsStationService stationService;
    private TeleportSettings teleportSettings = TeleportSettings.defaults();

    private final Map<StationKey, CachedEntry> cache = new HashMap<>();
    private final Map<StationKey, StationMetadata> metadata = new HashMap<>();
    private final Map<String, Set<StationKey>> cityIndex = new HashMap<>();
    private final Map<ChunkKey, Set<StationKey>> chunkIndex = new HashMap<>();
    private final Set<String> wallSignWarnings = ConcurrentHashMap.newKeySet();

    private long lastRebuildTick = Long.MIN_VALUE;
    private int rebuildsThisTick = 0;

    public StationPlatformResolver(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setStationService(TrainCartsStationService service) {
        this.stationService = service;
        clearAll();
    }

    public void updateTeleportSettings(TeleportSettings teleportSettings) {
        this.teleportSettings = teleportSettings != null ? teleportSettings : TeleportSettings.defaults();
        clearAll();
    }

    public void invalidateCity(City city) {
        if (city == null || city.id == null) {
            return;
        }
        invalidateCityId(city.id);
    }

    public void invalidateCityId(String cityId) {
        if (cityId == null) {
            return;
        }
        wallSignWarnings.remove(cityId);
        Set<StationKey> keys = cityIndex.get(cityId);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (StationKey key : keys) {
            cache.remove(key);
        }
    }

    public List<StationSpots> resolveStations(City city) {
        if (city == null || city.id == null) {
            return List.of();
        }
        TrainCartsStationService service = this.stationService;
        if (service == null) {
            return List.of();
        }
        Optional<List<Block>> resolved = service.resolveStationBlocks(city);
        if (resolved.isEmpty()) {
            return List.of();
        }
        List<Block> blocks = resolved.get();
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<StationSpots> spots = new ArrayList<>(blocks.size());
        Set<StationKey> currentKeys = new HashSet<>();
        boolean hadStationBlocks = false;
        boolean sawWallSignStation = false;

        for (Block block : blocks) {
            if (block == null) {
                continue;
            }
            World world = block.getWorld();
            if (world == null) {
                continue;
            }
            hadStationBlocks = true;
            Material type = block.getType();
            boolean isWallSign = isWallSign(type);
            if (isWallSign) {
                sawWallSignStation = true;
            }
            if (teleportSettings.requireWallSign && !isWallSign) {
                continue;
            }
            StationKey key = StationKey.from(block);
            if (key == null) {
                continue;
            }
            currentKeys.add(key);
            registerStation(city.id, block, key);
            List<Location> cached = resolveCachedSpots(key, block);
            Location signLocation = block.getLocation().toCenterLocation();
            spots.add(new StationSpots(signLocation, cached));
        }

        reconcileCityKeys(city.id, currentKeys);
        handleWallSignWarnings(city, hadStationBlocks, sawWallSignStation);
        return spots;
    }

    private void handleWallSignWarnings(City city, boolean hadStationBlocks, boolean sawWallSignStation) {
        if (city == null || city.id == null) {
            return;
        }
        if (!teleportSettings.requireWallSign) {
            wallSignWarnings.remove(city.id);
            return;
        }
        if (!hadStationBlocks) {
            wallSignWarnings.remove(city.id);
            return;
        }
        if (sawWallSignStation) {
            wallSignWarnings.remove(city.id);
            return;
        }
        if (!wallSignWarnings.add(city.id)) {
            return;
        }
        String cityLabel = city.name != null && !city.name.isBlank() ? city.name : city.id;
        plugin.getLogger().warning("No wall-sign stations found for " + cityLabel + "; set migration.teleport.require_wall_sign=false if you use standing/post signs.");
    }

    public boolean hasOnlyNonWallSignStations(String cityId) {
        if (cityId == null) {
            return false;
        }
        return wallSignWarnings.contains(cityId);
    }

    private void clearAll() {
        cache.clear();
        metadata.clear();
        cityIndex.clear();
        chunkIndex.clear();
        wallSignWarnings.clear();
    }

    private List<Location> resolveCachedSpots(StationKey key, Block signBlock) {
        CachedEntry entry = cache.get(key);
        long tick = Bukkit.getCurrentTick();
        if (entry != null && entry.expiresAtTick > tick) {
            return entry.spots;
        }
        if (!canRebuild(tick)) {
            return entry != null ? entry.spots : List.of();
        }
        List<Location> rebuilt = buildSpots(signBlock);
        cache.put(key, new CachedEntry(List.copyOf(rebuilt), tick + teleportSettings.cacheTtlTicks));
        return rebuilt;
    }

    private boolean canRebuild(long tick) {
        if (teleportSettings.rebuildPerTick <= 0) {
            return true;
        }
        if (tick != lastRebuildTick) {
            lastRebuildTick = tick;
            rebuildsThisTick = 0;
        }
        if (rebuildsThisTick >= teleportSettings.rebuildPerTick) {
            return false;
        }
        rebuildsThisTick++;
        return true;
    }

    private List<Location> buildSpots(Block signBlock) {
        World world = signBlock.getWorld();
        if (world == null) {
            return List.of();
        }
        List<Location> spots = new ArrayList<>();
        int max = teleportSettings.maxCandidatesPerStation;
        for (TeleportSettings.PlatformOffset offset : teleportSettings.platformOffsets) {
            if (spots.size() >= max) {
                break;
            }
            Block base = offsetFromSign(signBlock, offset);
            if (base == null || base.getWorld() == null) {
                continue;
            }
            if (!ensureChunkLoaded(base.getWorld(), base.getX(), base.getZ())) {
                continue;
            }
            Block floor = findPlatformFloor(signBlock, base);
            if (floor == null) {
                continue;
            }
            Location location = new Location(floor.getWorld(), floor.getX() + 0.5, floor.getY() + 1.01, floor.getZ() + 0.5);
            applyPlatformBackoff(signBlock, location);
            spots.add(location);
        }
        if (spots.isEmpty()) {
            collectFallbackSpots(signBlock, spots, max);
        }
        return spots;
    }

    private void collectFallbackSpots(Block signBlock, List<Location> spots, int max) {
        BlockFace facing = resolveSignFacing(signBlock);
        if (!isHorizontal(facing)) {
            return;
        }
        Block anchor = signBlock.getRelative(facing);
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        World world = anchor.getWorld();
        int forwardX = facing.getModX();
        int forwardZ = facing.getModZ();
        int rightX = forwardZ;
        int rightZ = -forwardX;
        int forwardRange = Math.max(1, Math.min(teleportSettings.radius > 0 ? teleportSettings.radius : 3, 4));
        int sidewaysRange = 2;
        for (int dz = 0; dz <= forwardRange && spots.size() < max; dz++) {
            for (int dx = -sidewaysRange; dx <= sidewaysRange && spots.size() < max; dx++) {
                for (int dy = 1; dy <= 2; dy++) {
                    int worldDx = rightX * dx + forwardX * dz;
                    int worldDz = rightZ * dx + forwardZ * dz;
                    Block base = anchor.getRelative(worldDx, dy, worldDz);
                    if (base == null || base.getWorld() == null) {
                        continue;
                    }
                    if (!ensureChunkLoaded(world, base.getX(), base.getZ())) {
                        continue;
                    }
                    Block floor = findPlatformFloor(signBlock, base);
                    if (floor == null) {
                        continue;
                    }
                    Location location = new Location(floor.getWorld(), floor.getX() + 0.5, floor.getY() + 1.01, floor.getZ() + 0.5);
                    applyPlatformBackoff(signBlock, location);
                    spots.add(location);
                    break;
                }
            }
        }
    }

    private void logPlatformDebug(Block signBlock, Block candidate, String reason) {
        if (plugin == null) {
            return;
        }
        String signDesc = describeBlock(signBlock);
        String candidateDesc = describeBlock(candidate);
        plugin.getLogger().info("[StationPlatformResolver] " + reason + " (sign=" + signDesc + ", candidate=" + candidateDesc + ")");
    }

    private String describeBlock(Block block) {
        if (block == null) {
            return "null";
        }
        World world = block.getWorld();
        String worldName = world != null ? world.getName() : "unknown";
        return worldName + "@" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private Block offsetFromSign(Block signBlock, TeleportSettings.PlatformOffset offset) {
        if (signBlock == null || offset == null) {
            return null;
        }
        BlockFace facing = resolveSignFacing(signBlock);
        if (!isHorizontal(facing)) {
            return signBlock.getRelative(offset.dx(), offset.dy(), offset.dz());
        }
        Block anchor = signBlock.getRelative(facing);
        if (anchor == null || anchor.getWorld() == null) {
            return null;
        }
        int forwardX = facing.getModX();
        int forwardZ = facing.getModZ();
        int rightX = forwardZ;
        int rightZ = -forwardX;
        int worldDx = rightX * offset.dx() + forwardX * offset.dz();
        int worldDz = rightZ * offset.dx() + forwardZ * offset.dz();
        return anchor.getRelative(worldDx, offset.dy(), worldDz);
    }

    private boolean isHorizontal(BlockFace face) {
        return face != null && (face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST || face == BlockFace.WEST);
    }

    private Block findPlatformFloor(Block signBlock, Block start) {
        if (start == null || signBlock == null) {
            return null;
        }
        World world = start.getWorld();
        if (world == null) {
            return null;
        }
        int x = start.getX();
        int z = start.getZ();
        int startY = start.getY();
        int signY = signBlock.getY();
        if (startY - 1 >= signY) {
            int downY = startY - 1;
            if (!ensureChunkLoaded(world, x, z)) {
                return null;
            }
            Block below = world.getBlockAt(x, downY, z);
            if (isValidPlatform(signBlock, below)) {
                return below;
            }
        }
        int maxY = Math.max(startY, signY + teleportSettings.platformVerticalSearch);
        for (int y = startY; y <= maxY; y++) {
            if (!ensureChunkLoaded(world, x, z)) {
                return null;
            }
            Block candidate = world.getBlockAt(x, y, z);
            if (isValidPlatform(signBlock, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void applyPlatformBackoff(Block signBlock, Location location) {
        if (signBlock == null || location == null || teleportSettings.platformHorizontalOffset <= 0.0d) {
            return;
        }
        BlockFace facing = resolveSignFacing(signBlock);
        if (facing == null || !isHorizontal(facing)) {
            return;
        }
        double offset = teleportSettings.platformHorizontalOffset;
        int modX = facing.getModX();
        int modZ = facing.getModZ();
        if (modX != 0 || modZ != 0) {
            double length = Math.sqrt(modX * modX + modZ * modZ);
            if (length > 0.0d) {
                location.add(-(modX / length) * offset, 0.0d, -(modZ / length) * offset);
            }
        }
    }

    private BlockFace resolveSignFacing(Block signBlock) {
        if (signBlock == null) {
            return null;
        }
        BlockData data = signBlock.getBlockData();
        if (data instanceof WallSign wall) {
            return wall.getFacing();
        }
        if (data instanceof org.bukkit.block.data.type.Sign standing) {
            return standing.getRotation();
        }
        return null;
    }

    private boolean isValidPlatform(Block signBlock, Block floor) {
        World world = floor.getWorld();
        if (world == null) {
            logPlatformDebug(signBlock, floor, "rejected: world missing");
            return false;
        }
        if (floor.getY() < signBlock.getY()) {
            logPlatformDebug(signBlock, floor, "rejected: below sign Y");
            return false;
        }
        Material type = floor.getType();
        if (!type.isSolid() || !type.isOccluding()) {
            logPlatformDebug(signBlock, floor, "rejected: non-solid/occluding floor (" + type + ")");
            return false;
        }
        if (!teleportSettings.floorAllowlist.isEmpty() && !teleportSettings.floorAllowlist.contains(type)) {
            logPlatformDebug(signBlock, floor, "rejected: not in floor allowlist");
            return false;
        }
        if (teleportSettings.floorBlacklist.contains(type)) {
            logPlatformDebug(signBlock, floor, "rejected: in floor blacklist");
            return false;
        }
        if (teleportSettings.disallowOnRail) {
            if (teleportSettings.railMaterials.contains(type)) {
                logPlatformDebug(signBlock, floor, "rejected: floor is rail block");
                return false;
            }
            Material feetType = world.getBlockAt(floor.getX(), floor.getY() + 1, floor.getZ()).getType();
            if (teleportSettings.railMaterials.contains(feetType)) {
                logPlatformDebug(signBlock, floor, "rejected: landing space is rail");
                return false;
            }
        }
        if (!hasHeadroom(world, floor.getY(), floor.getX(), floor.getZ())) {
            logPlatformDebug(signBlock, floor, "rejected: insufficient headroom");
            return false;
        }
        if (teleportSettings.disallowBelowRail && isBelowAnyRail(world, floor.getX(), floor.getY(), floor.getZ())) {
            logPlatformDebug(signBlock, floor, "rejected: rail above within disallowBelowRail");
            return false;
        }
        if (teleportSettings.railAvoidHorizRadius > 0 && railsNearSameY(world, floor.getX(), floor.getY(), floor.getZ())) {
            logPlatformDebug(signBlock, floor, "rejected: rail nearby on same Y");
            return false;
        }
        return true;
    }

    private boolean hasHeadroom(World world, int floorY, int x, int z) {
        if (floorY + 2 > world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, floorY + 1, z);
        Block head = world.getBlockAt(x, floorY + 2, z);
        return feet.isPassable() && head.isPassable();
    }

    private boolean isBelowAnyRail(World world, int x, int floorY, int z) {
        int maxDy = teleportSettings.railAvoidVertAbove;
        for (int dy = 1; dy <= maxDy; dy++) {
            int y = floorY + dy;
            if (y > world.getMaxHeight()) {
                break;
            }
            Material check = world.getBlockAt(x, y, z).getType();
            if (teleportSettings.railMaterials.contains(check)) {
                return true;
            }
        }
        return false;
    }

    private boolean railsNearSameY(World world, int x, int floorY, int z) {
        int radius = teleportSettings.railAvoidHorizRadius;
        if (radius <= 0) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Material type = world.getBlockAt(x + dx, floorY, z + dz).getType();
                if (teleportSettings.railMaterials.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void registerStation(String cityId, Block block, StationKey key) {
        StationMetadata meta = metadata.get(key);
        ChunkKey chunkKey = ChunkKey.from(block);
        if (chunkKey == null) {
            return;
        }
        if (meta != null) {
            if (!Objects.equals(meta.cityId, cityId)) {
                removeFromCity(meta.cityId, key);
            }
        }
        metadata.put(key, new StationMetadata(cityId, chunkKey));
        cityIndex.computeIfAbsent(cityId, id -> new LinkedHashSet<>()).add(key);
        chunkIndex.computeIfAbsent(chunkKey, ck -> new LinkedHashSet<>()).add(key);
    }

    private void reconcileCityKeys(String cityId, Set<StationKey> currentKeys) {
        Set<StationKey> known = cityIndex.computeIfAbsent(cityId, id -> new LinkedHashSet<>());
        if (known.isEmpty()) {
            known.addAll(currentKeys);
            return;
        }
        Set<StationKey> toRemove = new HashSet<>(known);
        toRemove.removeAll(currentKeys);
        for (StationKey stale : toRemove) {
            known.remove(stale);
            cache.remove(stale);
            StationMetadata meta = metadata.remove(stale);
            if (meta != null) {
                Set<StationKey> chunkKeys = chunkIndex.get(meta.chunkKey);
                if (chunkKeys != null) {
                    chunkKeys.remove(stale);
                    if (chunkKeys.isEmpty()) {
                        chunkIndex.remove(meta.chunkKey);
                    }
                }
            }
        }
        known.addAll(currentKeys);
    }

    private void removeFromCity(String cityId, StationKey key) {
        if (cityId == null) {
            return;
        }
        Set<StationKey> keys = cityIndex.get(cityId);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                cityIndex.remove(cityId);
            }
        }
    }

    private void invalidateNearby(Block block, int radius) {
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        int radiusSq = radius * radius;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkKey chunkKey = new ChunkKey(worldId, chunkX + dx, chunkZ + dz);
                Set<StationKey> keys = chunkIndex.get(chunkKey);
                if (keys == null || keys.isEmpty()) {
                    continue;
                }
                for (StationKey key : keys) {
                    if (distanceSquared(block, key) <= radiusSq) {
                        cache.remove(key);
                    }
                }
            }
        }
    }

    private int distanceSquared(Block block, StationKey key) {
        int dx = block.getX() - key.x;
        int dy = block.getY() - key.y;
        int dz = block.getZ() - key.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void invalidateChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        if (world == null) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getUID(), chunk.getX(), chunk.getZ());
        Set<StationKey> keys = chunkIndex.getOrDefault(key, Set.of());
        if (keys.isEmpty()) {
            return;
        }
        for (StationKey stationKey : keys) {
            cache.remove(stationKey);
        }
    }

    /**
     * Ensures the chunk that backs the provided block coordinates is loaded, synchronously loading if required.
     * Platform rebuilds are throttled, so the occasional sync load should remain lightweight.
     */
    private static boolean ensureChunkLoaded(World world, int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        world.loadChunk(chunkX, chunkZ);
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    private boolean isWallSign(Material type) {
        if (type == null) {
            return false;
        }
        return type.name().endsWith("_WALL_SIGN");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        handleBlockUpdate(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockUpdate(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        if (!teleportSettings.railMaterials.contains(block.getType())) {
            return;
        }
        invalidateNearby(block, RAIL_INVALIDATION_RADIUS);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        invalidateChunk(event.getChunk());
    }

    private void handleBlockUpdate(Block block) {
        if (block == null) {
            return;
        }
        invalidateNearby(block, BLOCK_INVALIDATION_RADIUS);
        if (teleportSettings.railMaterials.contains(block.getType())) {
            invalidateNearby(block, RAIL_INVALIDATION_RADIUS);
        }
    }

    public record StationSpots(Location signLocation, List<Location> spots) {
        public StationSpots {
            signLocation = signLocation != null ? signLocation.clone() : null;
            spots = spots == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(spots));
        }
    }

    private record StationKey(UUID worldId, int x, int y, int z) {
        static StationKey from(Block block) {
            if (block == null) {
                return null;
            }
            World world = block.getWorld();
            if (world == null) {
                return null;
            }
            return new StationKey(world.getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
        static ChunkKey from(Block block) {
            if (block == null || block.getWorld() == null) {
                return null;
            }
            return new ChunkKey(block.getWorld().getUID(), block.getX() >> 4, block.getZ() >> 4);
        }
    }

    private record StationMetadata(String cityId, ChunkKey chunkKey) {
    }

    private record CachedEntry(List<Location> spots, long expiresAtTick) {
    }
}
