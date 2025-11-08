package dev.citysim.integration.traincarts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationSignParserTest {

    private final StationSignParser parser = new StationSignParser();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Test
    void detectsStationEntryWithVariousFormatting() {
        TrainCartsReflectionBinder.StationText plain =
                new TrainCartsReflectionBinder.StationText("[Train]", "Station");
        TrainCartsReflectionBinder.StationText colored =
                new TrainCartsReflectionBinder.StationText(
                        colored("[Cart]", NamedTextColor.BLUE),
                        colored("Station: Arrivals", NamedTextColor.GREEN));
        TrainCartsReflectionBinder.StationText prefixed =
                new TrainCartsReflectionBinder.StationText("+-!/Train Express", "!Station express");

        assertTrue(parser.isStationEntry(List.of(plain)));
        assertTrue(parser.isStationEntry(List.of(colored)));
        assertTrue(parser.isStationEntry(List.of(prefixed)));
    }

    @Test
    void rejectsNonStationActionLines() {
        TrainCartsReflectionBinder.StationText redstone =
                new TrainCartsReflectionBinder.StationText("[Train]", "Redstone");
        assertFalse(parser.isStationEntry(List.of(redstone)));
    }

    @Test
    void normalizeLineStripsDecorations() {
        String input = colored(" [Cart-Express]  ", NamedTextColor.DARK_RED);
        assertEquals("cart-express", parser.normalizeLine(input));
    }

    @Test
    void stripFormattingRemovesColorCodesAndWhitespace() {
        String input = colored("  Station  ", NamedTextColor.GOLD);
        assertEquals("Station", parser.stripFormatting(input));
    }

    private static String colored(String text, NamedTextColor color) {
        return LEGACY.serialize(Component.text(text, color));
    }
}
