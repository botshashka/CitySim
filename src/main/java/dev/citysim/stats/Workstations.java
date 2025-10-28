package dev.citysim.stats;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

public final class Workstations {
    private Workstations() {}
    public static final Set<Material> JOB_BLOCKS = EnumSet.of(
        Material.BARREL,
        Material.COMPOSTER,
        Material.BLAST_FURNACE,
        Material.SMOKER,
        Material.CARTOGRAPHY_TABLE,
        Material.FLETCHING_TABLE,
        Material.LECTERN,
        Material.LOOM,
        Material.BREWING_STAND,
        Material.STONECUTTER,
        Material.GRINDSTONE,
        Material.SMITHING_TABLE,
        Material.CAULDRON
    );
}
