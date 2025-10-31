package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SelectionOutline {
    public static final String MAX_OUTLINE_PARTICLES_PATH = "selection.max_outline_particles";
    public static final String SIMPLE_OUTLINE_MIDPOINTS_PATH = "selection.simple_outline_midpoints";
    public static final int DEFAULT_MAX_OUTLINE_PARTICLES = 1500;
    public static final boolean DEFAULT_SIMPLE_OUTLINE_MIDPOINTS = true;

    private SelectionOutline() {
    }

    public static int resolveMaxOutlineParticles(Plugin plugin) {
        if (plugin == null) {
            return DEFAULT_MAX_OUTLINE_PARTICLES;
        }
        FileConfiguration config = plugin.getConfig();
        int configured = config.getInt(MAX_OUTLINE_PARTICLES_PATH, DEFAULT_MAX_OUTLINE_PARTICLES);
        if (configured < 0) {
            return 0;
        }
        return configured;
    }

    public static boolean resolveSimpleMidpoints(Plugin plugin) {
        if (plugin == null) {
            return DEFAULT_SIMPLE_OUTLINE_MIDPOINTS;
        }
        return plugin.getConfig().getBoolean(SIMPLE_OUTLINE_MIDPOINTS_PATH, DEFAULT_SIMPLE_OUTLINE_MIDPOINTS);
    }

    public static List<Location> planOutline(World world,
                                             int minX,
                                             int minY,
                                             int minZ,
                                             int maxX,
                                             int maxY,
                                             int maxZ,
                                             int maxParticles,
                                             boolean includeMidpoints) {
        if (world == null) {
            return Collections.emptyList();
        }
        long estimate = estimateFullOutlineParticles(minX, minY, minZ, maxX, maxY, maxZ);
        if (maxParticles > 0 && estimate > maxParticles) {
            return generateSimplifiedOutline(world, minX, minY, minZ, maxX, maxY, maxZ, includeMidpoints);
        }
        return generateFullOutline(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static long estimateFullOutlineParticles(int minX,
                                                     int minY,
                                                     int minZ,
                                                     int maxX,
                                                     int maxY,
                                                     int maxZ) {
        int dx = Math.max(0, maxX - minX);
        int dy = Math.max(0, maxY - minY);
        int dz = Math.max(0, maxZ - minZ);

        int xCorners = dx > 0 ? 2 : 1;
        int yCorners = dy > 0 ? 2 : 1;
        int zCorners = dz > 0 ? 2 : 1;

        long total = (long) xCorners * yCorners * zCorners;
        if (dx > 1) {
            total += (long) (dx - 1) * yCorners * zCorners;
        }
        if (dz > 1) {
            total += (long) (dz - 1) * xCorners * yCorners;
        }
        if (dy > 1) {
            total += (long) (dy - 1) * xCorners * zCorners;
        }
        return total;
    }

    private static List<Location> generateFullOutline(World world,
                                                      int minX,
                                                      int minY,
                                                      int minZ,
                                                      int maxX,
                                                      int maxY,
                                                      int maxZ) {
        List<Location> points = new ArrayList<>();
        int[] xCorners = axisValues(minX, maxX);
        int[] yCorners = axisValues(minY, maxY);
        int[] zCorners = axisValues(minZ, maxZ);

        for (int x : xCorners) {
            for (int y : yCorners) {
                for (int z : zCorners) {
                    points.add(center(world, x, y, z));
                }
            }
        }

        if (maxX > minX + 1) {
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int y : yCorners) {
                    for (int z : zCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        if (maxZ > minZ + 1) {
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                for (int x : xCorners) {
                    for (int y : yCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        if (maxY > minY + 1) {
            for (int y = minY + 1; y <= maxY - 1; y++) {
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        points.add(center(world, x, y, z));
                    }
                }
            }
        }

        return points;
    }

    private static List<Location> generateSimplifiedOutline(World world,
                                                            int minX,
                                                            int minY,
                                                            int minZ,
                                                            int maxX,
                                                            int maxY,
                                                            int maxZ,
                                                            boolean includeMidpoints) {
        List<Location> points = new ArrayList<>();
        int[] xCorners = axisValues(minX, maxX);
        int[] zCorners = axisValues(minZ, maxZ);
        int height = Math.max(1, maxY - minY + 1);
        int columnHeight = Math.min(4, height);
        int highestFromColumn = Math.min(maxY, minY + columnHeight - 1);

        for (int x : xCorners) {
            for (int z : zCorners) {
                for (int y = minY; y <= highestFromColumn; y++) {
                    points.add(center(world, x, y, z));
                }
                if (highestFromColumn < maxY) {
                    points.add(center(world, x, maxY, z));
                }
            }
        }

        if (includeMidpoints) {
            if (maxX > minX) {
                int midX = minX + (maxX - minX) / 2;
                for (int z : zCorners) {
                    points.add(center(world, midX, minY, z));
                    if (maxY > minY) {
                        points.add(center(world, midX, maxY, z));
                    }
                }
            }
            if (maxZ > minZ) {
                int midZ = minZ + (maxZ - minZ) / 2;
                for (int x : xCorners) {
                    points.add(center(world, x, minY, midZ));
                    if (maxY > minY) {
                        points.add(center(world, x, maxY, midZ));
                    }
                }
            }
            if (maxY > minY) {
                int midY = minY + (maxY - minY) / 2;
                for (int x : xCorners) {
                    for (int z : zCorners) {
                        points.add(center(world, x, midY, z));
                    }
                }
            }
        }

        return points;
    }

    private static int[] axisValues(int min, int max) {
        if (min == max) {
            return new int[]{min};
        }
        return new int[]{min, max};
    }

    private static Location center(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }
}
