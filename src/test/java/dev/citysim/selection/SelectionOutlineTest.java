package dev.citysim.selection;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionOutlineTest {

    @Test
    void fullHeightUsesSimplifiedVerticalOutline() {
        World world = createWorldStub("world", -64, 320);

        List<Location> outline = SelectionOutline.planOutline(
                world,
                0,
                -64,
                0,
                128,
                319,
                128,
                1500,
                false,
                true,
                100
        );

        assertFalse(outline.isEmpty(), "Expected outline points to be generated");
        int totalPoints = outline.size();
        assertTrue(totalPoints < 500, "Expected simplified outline to stay small for full-height cuboids, but got " + totalPoints);

        Set<Double> uniqueHeights = outline.stream()
                .map(Location::getY)
                .collect(Collectors.toSet());
        assertTrue(uniqueHeights.size() > 1, "Expected outline to include vertical variation, not a flat slice");
    }

    @Test
    void fullHeightRespectsParticleLimit() {
        World world = createWorldStub("world", -64, 320);

        List<Location> outline = SelectionOutline.planOutline(
                world,
                0,
                -64,
                0,
                128,
                319,
                128,
                10,
                false,
                true,
                100
        );

        assertFalse(outline.isEmpty(), "Expected outline points to be generated");
        int limitedPoints = outline.size();
        assertTrue(limitedPoints <= 10, "Expected outline to respect max particle limit, but got " + limitedPoints);
    }

    @Test
    void fullHeightPreviewSpansWorldVerticalRange() {
        World world = createWorldStub("world", -64, 320);

        List<Location> outline = SelectionOutline.planOutline(
                world,
                5,
                70,
                5,
                10,
                80,
                10,
                1500,
                false,
                true,
                75
        );

        assertFalse(outline.isEmpty(), "Expected outline points to be generated");
        double minOutlineY = outline.stream()
                .mapToDouble(Location::getY)
                .min()
                .orElse(Double.NaN);
        double maxOutlineY = outline.stream()
                .mapToDouble(Location::getY)
                .max()
                .orElse(Double.NaN);

        assertTrue(minOutlineY < 70, "Expected outline to dip below clicked min height, but got " + minOutlineY);
        assertTrue(maxOutlineY > 80, "Expected outline to exceed clicked max height, but got " + maxOutlineY);
    }

    private static World createWorldStub(String name, int minHeight, int maxHeight) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class[]{World.class},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    return switch (methodName) {
                        case "getName" -> name;
                        case "getMinHeight" -> minHeight;
                        case "getMaxHeight" -> maxHeight;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "WorldStub{" + name + "}";
                        default -> defaultValue(method.getReturnType());
                    };
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
