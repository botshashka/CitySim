package dev.citysim.migration;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class TeleportSettings {
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
    final List<PlatformOffset> platformOffsets;
    final boolean requireWallSign;
    final int maxCandidatesPerStation;
    final int cacheTtlTicks;
    final int rebuildPerTick;
    final int platformVerticalSearch;
    final double platformHorizontalOffset;

    private TeleportSettings(int radius,
                             int maxSamples,
                             boolean requireYAtLeastRail,
                             boolean disallowOnRail,
                             boolean disallowBelowRail,
                             int railAvoidHorizRadius,
                             int railAvoidVertAbove,
                             Set<Material> floorAllowlist,
                             Set<Material> floorBlacklist,
                             Set<Material> railMaterials,
                             List<PlatformOffset> platformOffsets,
                             boolean requireWallSign,
                             int maxCandidatesPerStation,
                             int cacheTtlTicks,
                             int rebuildPerTick,
                             int platformVerticalSearch,
                             double platformHorizontalOffset) {
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
        this.platformOffsets = platformOffsets == null ? List.of() : platformOffsets;
        this.requireWallSign = requireWallSign;
        this.maxCandidatesPerStation = maxCandidatesPerStation;
        this.cacheTtlTicks = cacheTtlTicks;
        this.rebuildPerTick = rebuildPerTick;
        this.platformVerticalSearch = platformVerticalSearch;
        this.platformHorizontalOffset = platformHorizontalOffset;
    }

    static TeleportSettings defaults() {
        return new TeleportSettings(
                0,
                1,
                true,
                true,
                true,
                1,
                6,
                Set.of(),
                Set.of(),
                Set.of(),
                List.of(new PlatformOffset(0, 1, 0)),
                true,
                8,
                200,
                8,
                3,
                0.4d
        );
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
        List<PlatformOffset> platformOffsets = parseOffsets(config.getList(path + ".platform_offsets"));
        boolean requireWallSign = config.getBoolean(path + ".require_wall_sign", true);
        int maxCandidates = Math.max(1, config.getInt(path + ".max_candidates_per_station", 8));
        int cacheTtlTicks = Math.max(20, config.getInt(path + ".cache_ttl_ticks", 200));
        int rebuildPerTick = Math.max(1, config.getInt(path + ".rebuild_per_tick", 8));
        int platformVerticalSearch = Math.max(0, config.getInt(path + ".platform_vertical_search", 6));
        double platformHorizontalOffset = Math.max(0.0d, config.getDouble(path + ".platform_horizontal_offset", 0.4d));
        return new TeleportSettings(radius, maxSamples, requireY, disallowOnRail, disallowBelowRail,
                avoidHoriz, avoidVert, allow, blacklist, rails, platformOffsets,
                requireWallSign, maxCandidates, cacheTtlTicks, rebuildPerTick, platformVerticalSearch, platformHorizontalOffset);
    }

    private static List<PlatformOffset> parseOffsets(List<?> rawOffsets) {
        if (rawOffsets == null || rawOffsets.isEmpty()) {
            return List.of(
                    new PlatformOffset(0, 1, 0),
                    new PlatformOffset(1, 1, 0),
                    new PlatformOffset(-1, 1, 0),
                    new PlatformOffset(0, 1, 1),
                    new PlatformOffset(0, 1, -1),
                    new PlatformOffset(1, 1, 1),
                    new PlatformOffset(1, 1, -1),
                    new PlatformOffset(-1, 1, 1),
                    new PlatformOffset(-1, 1, -1)
            );
        }
        List<PlatformOffset> parsed = new ArrayList<>(rawOffsets.size());
        for (Object entry : rawOffsets) {
            if (entry instanceof List<?> list && list.size() >= 3) {
                int dx = parseComponent(list.get(0));
                int dy = parseComponent(list.get(1));
                int dz = parseComponent(list.get(2));
                parsed.add(new PlatformOffset(dx, dy, dz));
                continue;
            }
            if (entry instanceof PlatformOffset offset) {
                parsed.add(offset);
            }
        }
        return parsed.isEmpty() ? List.of(new PlatformOffset(0, 1, 0)) : List.copyOf(parsed);
    }

    private static int parseComponent(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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
            boolean tagEntry = isTagEntry(entry);
            Tag<Material> tag = resolveMaterialTag(entry);
            if (tag != null) {
                addTagMaterials(set, tag);
                continue;
            }
            if (tagEntry) {
                if (plugin != null) {
                    plugin.getLogger().warning("Unknown material tag '" + entry + "' in " + path);
                }
                continue;
            }
            Material material = Material.matchMaterial(entry.toUpperCase(Locale.ROOT));
            if (material != null) {
                set.add(material);
                continue;
            }
            if (plugin != null) {
                plugin.getLogger().warning("Unknown material '" + entry + "' in " + path);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    private static boolean isTagEntry(String entry) {
        if (entry == null) {
            return false;
        }
        return entry.trim().regionMatches(true, 0, "tag:", 0, 4);
    }

    private static Tag<Material> resolveMaterialTag(String entry) {
        String value = entry.trim();
        if (!value.regionMatches(true, 0, "tag:", 0, 4)) {
            return null;
        }
        String tagName = value.substring(4).trim().toUpperCase(Locale.ROOT);
        return switch (tagName) {
            case "CARPETS", "WOOL_CARPETS" -> Tag.WOOL_CARPETS;
            case "TRAPDOORS" -> Tag.TRAPDOORS;
            case "FENCES" -> Tag.FENCES;
            case "WOODEN_FENCES" -> Tag.WOODEN_FENCES;
            case "FENCE_GATES" -> Tag.FENCE_GATES;
            default -> null;
        };
    }

    private static void addTagMaterials(EnumSet<Material> set, Tag<Material> tag) {
        if (tag == null) {
            return;
        }
        for (Material material : tag.getValues()) {
            if (material != null) {
                set.add(material);
            }
        }
    }

    static final class PlatformOffset {
        final int dx;
        final int dy;
        final int dz;

        PlatformOffset(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        int dx() {
            return dx;
        }

        int dy() {
            return dy;
        }

        int dz() {
            return dz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PlatformOffset that)) {
                return false;
            }
            return dx == that.dx && dy == that.dy && dz == that.dz;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dx, dy, dz);
        }
    }
}
