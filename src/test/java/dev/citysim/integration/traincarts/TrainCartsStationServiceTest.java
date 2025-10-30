package dev.citysim.integration.traincarts;

import dev.citysim.city.City;
import dev.citysim.city.Cuboid;
import dev.citysim.stats.StationCountResult;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainCartsStationServiceTest {

    @Test
    void countsStationsUsingTrackedSigns() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        Plugin trainCarts = mock(Plugin.class);
        Logger logger = Logger.getLogger("TrainCartsStationServiceTest");
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(server);

        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(server.getWorld("world")).thenReturn(world);

        City city = createCityWithCuboid();

        Block blockA = mock(Block.class);
        when(blockA.getWorld()).thenReturn(world);
        when(blockA.getX()).thenReturn(10);
        when(blockA.getY()).thenReturn(64);
        when(blockA.getZ()).thenReturn(10);

        Block blockB = mock(Block.class);
        when(blockB.getWorld()).thenReturn(world);
        when(blockB.getX()).thenReturn(12);
        when(blockB.getY()).thenReturn(64);
        when(blockB.getZ()).thenReturn(10);

        Object[] entries = { new Object(), new Object() };
        List<List<TrainCartsReflectionBinder.StationText>> texts = List.of(
                List.of(new TrainCartsReflectionBinder.StationText("[Train]", "Station")),
                List.of(new TrainCartsReflectionBinder.StationText("[Cart]", "Station"))
        );
        boolean[] events = { true, true };
        Block[] blocks = { blockA, blockB };

        TrainCartsReflectionBinder.TrainCartsBinding binding =
                new StubBinding(entries, blocks, texts, events);

        TrainCartsStationService service =
                new TrainCartsStationService(plugin, server, trainCarts, binding, new StationSignParser());

        Optional<StationCountResult> result = service.countStations(city);
        assertTrue(result.isPresent());
        StationCountResult counts = result.get();
        assertEquals(1, counts.stations());
        assertEquals(2, counts.signs());
    }

    @Test
    void countsStationsUsingBlockFallback() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        Plugin trainCarts = mock(Plugin.class);
        Logger logger = Logger.getLogger("TrainCartsStationServiceTest");
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getServer()).thenReturn(server);

        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("legacy_world");
        when(server.getWorld("legacy_world")).thenReturn(world);

        City city = createCityWithCuboid();
        city.cuboids.getFirst().world = "legacy_world";

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(5);
        when(block.getY()).thenReturn(70);
        when(block.getZ()).thenReturn(5);

        Object[] entries = { new Object() };
        List<List<TrainCartsReflectionBinder.StationText>> texts = List.of(
                List.of(new TrainCartsReflectionBinder.StationText("[Train]", "Station express"))
        );
        boolean[] events = { true };
        Block[] blocks = { block };

        TrainCartsReflectionBinder.TrainCartsBinding binding =
                new StubBinding(entries, blocks, texts, events);

        TrainCartsStationService service =
                new TrainCartsStationService(plugin, server, trainCarts, binding, new StationSignParser());

        Optional<StationCountResult> result = service.countStations(city);
        assertTrue(result.isPresent());
        StationCountResult counts = result.get();
        assertEquals(1, counts.stations());
        assertEquals(1, counts.signs());
    }

    private City createCityWithCuboid() {
        City city = new City();
        Cuboid cuboid = new Cuboid();
        cuboid.world = "world";
        cuboid.minX = 0;
        cuboid.maxX = 20;
        cuboid.minY = 0;
        cuboid.maxY = 255;
        cuboid.minZ = 0;
        cuboid.maxZ = 20;
        city.cuboids = new ArrayList<>();
        city.cuboids.add(cuboid);
        return city;
    }

    private static final class StubBinding implements TrainCartsReflectionBinder.TrainCartsBinding {
        private final Object signController = new Object();
        private final Object worldController = new Object();
        private final Object chunk = new Object();
        private final Object[] entries;
        private final Block[] blocks;
        private final List<List<TrainCartsReflectionBinder.StationText>> texts;
        private final boolean[] events;
        private final Map<Object, Integer> indexByEntry = new IdentityHashMap<>();

        private StubBinding(Object[] entries,
                             Block[] blocks,
                             List<List<TrainCartsReflectionBinder.StationText>> texts,
                             boolean[] events) {
            this.entries = entries;
            this.blocks = blocks;
            this.texts = texts;
            this.events = events;
            for (int i = 0; i < entries.length; i++) {
                indexByEntry.put(entries[i], i);
            }
        }

        @Override
        public Object getSignController() {
            return signController;
        }

        @Override
        public Object getWorldController(Object signController, World world) {
            return worldController;
        }

        @Override
        public boolean isWorldEnabled(Object worldController) {
            return true;
        }

        @Override
        public Collection<?> loadSignChunks(Object worldController) {
            return List.of(chunk);
        }

        @Override
        public Object[] resolveEntries(Object chunk) {
            return this.chunk.equals(chunk) ? entries : new Object[0];
        }

        @Override
        public boolean hasSignActionEvents(Object entry) {
            Integer index = indexByEntry.get(entry);
            return index == null || events == null || events[index];
        }

        @Override
        public Block getBlock(Object entry) {
            Integer index = indexByEntry.get(entry);
            return index != null ? blocks[index] : null;
        }

        @Override
        public List<TrainCartsReflectionBinder.StationText> resolveStationTexts(Object entry, Block block) {
            Integer index = indexByEntry.get(entry);
            return index != null ? texts.get(index) : List.of();
        }
    }
}
