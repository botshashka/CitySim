package dev.citysim.migration;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import dev.citysim.city.Cuboid;
import dev.citysim.links.CityLink;
import dev.citysim.links.LinkService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Villager.Profession;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class MigrationService implements Runnable {

    private static final double PREFERRED_STATION_RADIUS = 12.0;
    private static final double FALLBACK_VERTICAL_RANGE = 6.0;
    private static final double FALLBACK_VERTICAL_RANGE_WIDE = 8.0;
    private static final double MAX_FALLBACK_RADIUS = 64.0;

    private final Plugin plugin;
    private final CityManager cityManager;
    private final LinkService linkService;
    private final NamespacedKey cooldownKey;

    private final Map<String, CityMigrationCounters> counters = new HashMap<>();

    private MigrationSettings settings = MigrationSettings.disabled();
    private BukkitTask task;

    public MigrationService(Plugin plugin, CityManager cityManager, LinkService linkService) {
        this.plugin = plugin;
        this.cityManager = cityManager;
        this.linkService = linkService;
        this.cooldownKey = new NamespacedKey(plugin, "migrate_until");
    }

    public void reload(FileConfiguration config) {
        this.settings = MigrationSettings.fromConfig(plugin, config);
        restart();
    }

    public void start() {
        restart();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void restart() {
        stop();
        if (!settings.enabled || settings.intervalTicks <= 0) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, settings.intervalTicks, settings.intervalTicks);
    }

    @Override
    public void run() {
        tick();
    }

    private void tick() {
        if (!settings.enabled || settings.maxMovesPerTick <= 0) {
            return;
        }
        if (linkService == null || !linkService.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        int attempts = 0;
        Map<String, Integer> movedFromOrigin = new HashMap<>();

        for (City origin : cityManager.all()) {
            if (origin == null || origin.id == null) {
                continue;
            }
            if (!isOriginEligible(origin)) {
                continue;
            }
            List<CityLink> links = new ArrayList<>(linkService.computeLinks(origin));
            links.sort(Comparator
                    .comparingInt((CityLink link) -> Math.max(0, link.neighbor().vacanciesTotal))
                    .reversed()
                    .thenComparingInt(CityLink::strength)
                    .reversed()
                    .thenComparing(link -> link.neighbor().name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(link -> link.neighbor().id, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            if (links.isEmpty()) {
                continue;
            }
            for (CityLink link : links) {
                if (attempts >= settings.maxMovesPerTick) {
                    return;
                }
                City destination = link.neighbor();
                if (!isDestinationEligible(destination)) {
                    continue;
                }
                int movedSoFar = movedFromOrigin.getOrDefault(origin.id, 0);
                if (!hasPopulationBudget(origin, movedSoFar)) {
                    continue;
                }
                attempts++;
                if (attemptMove(origin, destination, now)) {
                    movedFromOrigin.merge(origin.id, 1, Integer::sum);
                }
            }
        }
    }

    private boolean attemptMove(City origin, City destination, long now) {
        Location originAnchor = stationAnchor(origin);
        if (originAnchor == null) {
            return false;
        }
        Villager villager = selectVillager(origin, originAnchor, now);
        if (villager == null) {
            return false;
        }

        Location destinationAnchor = stationAnchor(destination);
        if (destinationAnchor == null) {
            return false;
        }

        Location target = findDestinationSpot(destinationAnchor, settings.teleport);
        if (target == null) {
            return false;
        }

        if (!ensureChunkLoaded(target.getWorld(), target.getBlockX(), target.getBlockZ())) {
            return false;
        }

        if (!villager.teleport(target)) {
            return false;
        }
        villager.setFallDistance(0f);
        applyCooldown(villager, now);

        recordDeparture(origin, destination);
        recordArrival(destination, origin);
        return true;
    }

    private boolean isOriginEligible(City city) {
        if (city == null) {
            return false;
        }
        if (city.population <= settings.minPopulationFloor) {
            return false;
        }
        boolean employmentPressure = city.employmentRate < 0.75d;
        boolean housingPressure = city.housingRatio < 1.0d;
        return (employmentPressure || housingPressure) && city.population > settings.minPopulationFloor;
    }

    private boolean isDestinationEligible(City city) {
        if (city == null) {
            return false;
        }
        return city.employmentRate >= 0.75d && city.housingRatio >= 1.0d;
    }

    private boolean hasPopulationBudget(City origin, int movedSoFar) {
        if (origin == null) {
            return false;
        }
        int population = Math.max(0, origin.population);
        return population - movedSoFar > settings.minPopulationFloor;
    }

    private void applyCooldown(Villager villager, long now) {
        if (settings.cooldownMillis <= 0) {
            return;
        }
        long until = now + settings.cooldownMillis;
        villager.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, until);
    }

    private Villager selectVillager(City city, Location anchor, long now) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        List<Villager> nearby = findVillagers(world.getNearbyEntities(anchor, PREFERRED_STATION_RADIUS, FALLBACK_VERTICAL_RANGE, PREFERRED_STATION_RADIUS), city, anchor, now);
        if (!nearby.isEmpty()) {
            return nearby.get(0);
        }

        CityBounds bounds = computeBounds(city);
        double fallbackRadius = PREFERRED_STATION_RADIUS * 2.0;
        if (bounds != null) {
            fallbackRadius = Math.max(fallbackRadius, bounds.maxDimension() / 2.0 + 4.0);
        }
        fallbackRadius = Math.min(MAX_FALLBACK_RADIUS, fallbackRadius);

        Collection<Entity> fallbackEntities = world.getNearbyEntities(anchor, fallbackRadius, FALLBACK_VERTICAL_RANGE_WIDE, fallbackRadius);
        List<Villager> fallback = findVillagers(fallbackEntities, city, anchor, now);
        if (!fallback.isEmpty()) {
            return fallback.get(0);
        }
        return null;
    }

    private List<Villager> findVillagers(Collection<Entity> entities, City city, Location anchor, long now) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<Villager> results = new ArrayList<>();
        for (Entity entity : entities) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            if (!isVillagerEligible(villager, city, now)) {
                continue;
            }
            results.add(villager);
        }
        results.sort(Comparator.comparingDouble(v -> distanceSquared(anchor, v.getLocation())));
        return results;
    }

    private boolean isVillagerEligible(Villager villager, City city, long now) {
        if (villager == null || !villager.isValid() || villager.isDead()) {
            return false;
        }
        if (villager.isSleeping() || !villager.isAdult() || villager.isInsideVehicle()) {
            return false;
        }
        if (villager.getProfession() == Profession.NITWIT) {
            return false;
        }
        Location location = villager.getLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!city.contains(location)) {
            return false;
        }
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        Long until = pdc.get(cooldownKey, PersistentDataType.LONG);
        return until == null || until <= now;
    }

    private Location findDestinationSpot(Location anchor, TeleportSettings teleportSettings) {
        World world = anchor.getWorld();
        if (world == null) {
            return null;
        }
        int baseX = anchor.getBlockX();
        int baseY = anchor.getBlockY();
        int baseZ = anchor.getBlockZ();

        if (!ensureChunkLoaded(world, baseX, baseZ)) {
            return null;
        }

        int railY = findLocalRailY(world, baseX, baseY, baseZ, teleportSettings);
        int radius = Math.max(0, teleportSettings.radius);
        int maxSamples = Math.max(1, teleportSettings.maxSamples);

        Set<Long> visited = new HashSet<>();
        visited.add(positionKey(baseX, baseZ));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int sample = 0; sample < maxSamples; sample++) {
            int candidateX = baseX;
            int candidateZ = baseZ;
            if (sample > 0 && radius > 0) {
                boolean found = false;
                for (int tries = 0; tries < 6 && !found; tries++) {
                    int dx = random.nextInt(-radius, radius + 1);
                    int dz = random.nextInt(-radius, radius + 1);
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    long key = positionKey(x, z);
                    if (visited.add(key)) {
                        candidateX = x;
                        candidateZ = z;
                        found = true;
                    }
                }
                if (!found) {
                    continue;
                }
            }

            if (!ensureChunkLoaded(world, candidateX, candidateZ)) {
                continue;
            }

            int surfaceY = Math.max(world.getMinHeight(), world.getHighestBlockYAt(candidateX, candidateZ));
            int startY = teleportSettings.requireYAtLeastRail ? Math.max(surfaceY, railY) : surfaceY;
            startY = Math.min(startY, world.getMaxHeight() - 1);

            for (int down = 0; down <= 2; down++) {
                int floorY = startY - down;
                if (floorY < world.getMinHeight()) {
                    break;
                }
                if (!isCandidateValid(world, candidateX, floorY, candidateZ, teleportSettings)) {
                    continue;
                }
                return new Location(world, candidateX + 0.5, floorY + 0.1, candidateZ + 0.5);
            }
        }
        return null;
    }

    private boolean isCandidateValid(World world, int x, int floorY, int z, TeleportSettings teleportSettings) {
        Block floor = world.getBlockAt(x, floorY, z);
        if (!isStandableFloor(floor, teleportSettings)) {
            return false;
        }
        if (!hasHeadroom(world, floorY, x, z)) {
            return false;
        }
        if (teleportSettings.disallowOnRail) {
            if (teleportSettings.railMaterials.contains(floor.getType())) {
                return false;
            }
            Material feet = world.getBlockAt(x, floorY + 1, z).getType();
            if (teleportSettings.railMaterials.contains(feet)) {
                return false;
            }
        }
        if (teleportSettings.disallowBelowRail && isBelowAnyRail(world, teleportSettings, x, floorY, z)) {
            return false;
        }
        if (teleportSettings.railAvoidHorizRadius > 0 && railsNearSameY(world, teleportSettings, x, floorY, z)) {
            return false;
        }
        return true;
    }

    private boolean isStandableFloor(Block block, TeleportSettings teleportSettings) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (teleportSettings.floorAllowlist != null && !teleportSettings.floorAllowlist.isEmpty() && !teleportSettings.floorAllowlist.contains(type)) {
            return false;
        }
        if (teleportSettings.floorBlacklist.contains(type)) {
            return false;
        }
        return type.isSolid() && type.isOccluding();
    }

    private boolean hasHeadroom(World world, int floorY, int x, int z) {
        if (floorY + 2 > world.getMaxHeight()) {
            return false;
        }
        Block feet = world.getBlockAt(x, floorY + 1, z);
        Block head = world.getBlockAt(x, floorY + 2, z);
        return feet.isPassable() && head.isPassable();
    }

    private boolean isBelowAnyRail(World world, TeleportSettings teleportSettings, int x, int floorY, int z) {
        int maxDy = teleportSettings.railAvoidVertAbove;
        for (int dy = 1; dy <= maxDy; dy++) {
            int y = floorY + dy;
            if (y > world.getMaxHeight()) {
                break;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            if (teleportSettings.railMaterials.contains(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean railsNearSameY(World world, TeleportSettings teleportSettings, int x, int floorY, int z) {
        int radius = teleportSettings.railAvoidHorizRadius;
        if (radius <= 0) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int checkX = x + dx;
                int checkZ = z + dz;
                Material type = world.getBlockAt(checkX, floorY + 1, checkZ).getType();
                if (teleportSettings.railMaterials.contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int findLocalRailY(World world, int centerX, int centerY, int centerZ, TeleportSettings teleportSettings) {
        int radius = Math.min(teleportSettings.radius, 5);
        int minY = Math.max(world.getMinHeight(), centerY - 3);
        int maxY = Math.min(world.getMaxHeight(), centerY + 3);
        int highest = Integer.MIN_VALUE;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!ensureChunkLoaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (teleportSettings.railMaterials.contains(type) && y > highest) {
                        highest = y;
                    }
                }
            }
        }
        if (highest != Integer.MIN_VALUE) {
            return highest;
        }
        return centerY;
    }

    private boolean ensureChunkLoaded(World world, int blockX, int blockZ) {
        if (world == null) {
            return false;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        world.loadChunk(chunkX, chunkZ);
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    private Location stationAnchor(City city) {
        if (city == null || city.world == null) {
            return null;
        }
        World world = Bukkit.getWorld(city.world);
        if (world == null) {
            return null;
        }
        CityBounds bounds = computeBounds(city);
        if (bounds == null) {
            return null;
        }
        int x = bounds.centerX();
        int z = bounds.centerZ();
        if (!ensureChunkLoaded(world, x, z)) {
            return null;
        }
        int y = Math.max(world.getMinHeight(), world.getHighestBlockYAt(x, z));
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private CityBounds computeBounds(City city) {
        if (city == null || city.cuboids == null || city.cuboids.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;
        for (Cuboid cuboid : city.cuboids) {
            if (cuboid == null || cuboid.world == null) {
                continue;
            }
            if (city.world != null && !Objects.equals(city.world, cuboid.world)) {
                continue;
            }
            minX = Math.min(minX, cuboid.minX);
            minZ = Math.min(minZ, cuboid.minZ);
            maxX = Math.max(maxX, cuboid.maxX);
            maxZ = Math.max(maxZ, cuboid.maxZ);
            found = true;
        }
        if (!found) {
            return null;
        }
        int centerX = (int) Math.round((minX + maxX) / 2.0);
        int centerZ = (int) Math.round((minZ + maxZ) / 2.0);
        return new CityBounds(minX, maxX, minZ, maxZ, centerX, centerZ);
    }

    private void recordDeparture(City origin, City destination) {
        if (origin == null || origin.id == null || destination == null || destination.id == null) {
            return;
        }
        CityMigrationCounters counter = counters.computeIfAbsent(origin.id, id -> new CityMigrationCounters());
        counter.departures++;
        counter.links.computeIfAbsent(destination.id, id -> new LinkMigrationCounters()).departures++;
    }

    private void recordArrival(City destination, City origin) {
        if (destination == null || destination.id == null || origin == null || origin.id == null) {
            return;
        }
        CityMigrationCounters counter = counters.computeIfAbsent(destination.id, id -> new CityMigrationCounters());
        counter.arrivals++;
        counter.links.computeIfAbsent(origin.id, id -> new LinkMigrationCounters()).arrivals++;
    }

    public CityMigrationSnapshot snapshot(String cityId) {
        if (cityId == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        CityMigrationCounters counter = counters.get(cityId);
        if (counter == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        Map<String, LinkMigrationSnapshot> linkSnapshots = new LinkedHashMap<>();
        for (Map.Entry<String, LinkMigrationCounters> entry : counter.links.entrySet()) {
            LinkMigrationCounters value = entry.getValue();
            linkSnapshots.put(entry.getKey(), new LinkMigrationSnapshot(value.arrivals, value.departures));
        }
        return new CityMigrationSnapshot(counter.arrivals, counter.departures, Collections.unmodifiableMap(linkSnapshots));
    }

    public CityMigrationSnapshot snapshot(City city) {
        if (city == null || city.id == null) {
            return CityMigrationSnapshot.EMPTY;
        }
        return snapshot(city.id);
    }

    private static double distanceSquared(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null || !Objects.equals(a.getWorld().getUID(), b.getWorld().getUID())) {
            return Double.MAX_VALUE;
        }
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private long positionKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private record CityBounds(int minX, int maxX, int minZ, int maxZ, int centerX, int centerZ) {
        double maxDimension() {
            return Math.max(maxX - minX, maxZ - minZ);
        }
    }

    private static class CityMigrationCounters {
        long arrivals;
        long departures;
        final Map<String, LinkMigrationCounters> links = new LinkedHashMap<>();
    }

    private static class LinkMigrationCounters {
        long arrivals;
        long departures;
    }

    public record CityMigrationSnapshot(long arrivals, long departures, Map<String, LinkMigrationSnapshot> links) {
        static final CityMigrationSnapshot EMPTY = new CityMigrationSnapshot(0L, 0L, Map.of());

        public long net() {
            return arrivals - departures;
        }
    }

    public record LinkMigrationSnapshot(long arrivals, long departures) {
        public long net() {
            return arrivals - departures;
        }
    }

    private static class MigrationSettings {
        final boolean enabled;
        final int intervalTicks;
        final int maxMovesPerTick;
        final long cooldownMillis;
        final int minPopulationFloor;
        final TeleportSettings teleport;

        private MigrationSettings(boolean enabled, int intervalTicks, int maxMovesPerTick, long cooldownMillis,
                                  int minPopulationFloor, TeleportSettings teleport) {
            this.enabled = enabled;
            this.intervalTicks = intervalTicks;
            this.maxMovesPerTick = maxMovesPerTick;
            this.cooldownMillis = cooldownMillis;
            this.minPopulationFloor = minPopulationFloor;
            this.teleport = teleport;
        }

        static MigrationSettings disabled() {
            return new MigrationSettings(false, 0, 0, 0L, 0, TeleportSettings.defaults());
        }

        static MigrationSettings fromConfig(Plugin plugin, FileConfiguration config) {
            if (config == null) {
                return disabled();
            }
            boolean enabled = config.getBoolean("migration.enabled", false);
            int interval = Math.max(0, config.getInt("migration.interval_ticks", 100));
            int maxMoves = Math.max(0, config.getInt("migration.max_moves_per_tick", 2));
            int cooldownMinutes = Math.max(0, config.getInt("migration.cooldown_minutes", 10));
            long cooldownMillis = TimeUnit.MINUTES.toMillis(cooldownMinutes);
            int populationFloor = Math.max(0, config.getInt("migration.min_city_population_floor", 10));
            TeleportSettings teleport = TeleportSettings.fromConfig(plugin, config, "migration.teleport");
            return new MigrationSettings(enabled, interval, maxMoves, cooldownMillis, populationFloor, teleport);
        }
    }

    private static class TeleportSettings {
        final int radius;
        final int maxSamples;
        final boolean requireYAtLeastRail;
        final boolean disallowOnRail;
        final boolean disallowBelowRail;
        final int railAvoidHorizRadius;
        final int railAvoidVertAbove;
        final Set<Material> floorAllowlist;
        final Set<Material> floorBlacklist;
        final Set<Material> railMaterials;

        private TeleportSettings(int radius, int maxSamples, boolean requireYAtLeastRail, boolean disallowOnRail,
                                 boolean disallowBelowRail, int railAvoidHorizRadius, int railAvoidVertAbove,
                                 Set<Material> floorAllowlist, Set<Material> floorBlacklist, Set<Material> railMaterials) {
            this.radius = radius;
            this.maxSamples = maxSamples;
            this.requireYAtLeastRail = requireYAtLeastRail;
            this.disallowOnRail = disallowOnRail;
            this.disallowBelowRail = disallowBelowRail;
            this.railAvoidHorizRadius = railAvoidHorizRadius;
            this.railAvoidVertAbove = railAvoidVertAbove;
            this.floorAllowlist = floorAllowlist == null ? Set.of() : floorAllowlist;
            this.floorBlacklist = floorBlacklist == null ? Set.of() : floorBlacklist;
            this.railMaterials = railMaterials == null ? Set.of() : railMaterials;
        }

        static TeleportSettings defaults() {
            return new TeleportSettings(0, 1, true, true, true, 1, 3, Set.of(), Set.of(), Set.of());
        }

        static TeleportSettings fromConfig(Plugin plugin, FileConfiguration config, String path) {
            int radius = Math.max(0, config.getInt(path + ".radius", 8));
            int maxSamples = Math.max(1, config.getInt(path + ".max_samples", 24));
            boolean requireY = config.getBoolean(path + ".require_y_at_least_rail", true);
            boolean disallowOnRail = config.getBoolean(path + ".disallow_on_rail", true);
            boolean disallowBelowRail = config.getBoolean(path + ".disallow_below_rail", true);
            int avoidHoriz = Math.max(0, config.getInt(path + ".rail_avoid_horiz_radius", 1));
            int avoidVert = Math.max(0, config.getInt(path + ".rail_avoid_vert_above", 3));
            Set<Material> allow = materialSet(plugin, config.getStringList(path + ".floor_allowlist"), path + ".floor_allowlist");
            Set<Material> blacklist = materialSet(plugin, config.getStringList(path + ".floor_block_blacklist"), path + ".floor_block_blacklist");
            Set<Material> rails = materialSet(plugin, config.getStringList(path + ".rail_materials"), path + ".rail_materials");
            return new TeleportSettings(radius, maxSamples, requireY, disallowOnRail, disallowBelowRail, avoidHoriz, avoidVert,
                    allow, blacklist, rails);
        }

        private static Set<Material> materialSet(Plugin plugin, List<String> entries, String path) {
            if (entries == null || entries.isEmpty()) {
                return Collections.emptySet();
            }
            EnumSet<Material> set = EnumSet.noneOf(Material.class);
            for (String entry : entries) {
                if (entry == null || entry.isEmpty()) {
                    continue;
                }
                Material material = Material.matchMaterial(entry.toUpperCase(Locale.ROOT));
                if (material != null) {
                    set.add(material);
                } else if (plugin != null) {
                    plugin.getLogger().warning("Unknown material '" + entry + "' in " + path);
                }
            }
            return Collections.unmodifiableSet(set);
        }
    }
}

