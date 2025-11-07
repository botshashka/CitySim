package dev.citysim.stats.scan;

import dev.citysim.city.City;
import dev.citysim.city.CityManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;

public class BedEventListener implements Listener {
    private final CityManager cityManager;

    public BedEventListener(CityManager cityManager) {
        this.cityManager = cityManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidateIfBed(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidateIfBed(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (event == null) {
            return;
        }
        invalidateIfBed(event.getBlock());
        if (isBed(event.getChangedType())) {
            invalidateIfBed(event.getBlock());
        }
    }

    private void invalidateIfBed(Block block) {
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (!isBed(type)) {
            return;
        }
        City city = cityManager.cityAt(block.getLocation());
        if (city == null) {
            return;
        }
        City.ChunkPosition chunkPos = new City.ChunkPosition(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
        city.bedSnapshotMap().remove(chunkPos);
    }

    private static boolean isBed(Material type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED, PINK_BED,
                 GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED, BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> true;
            default -> false;
        };
    }
}
