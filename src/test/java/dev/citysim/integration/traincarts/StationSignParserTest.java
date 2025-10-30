package dev.citysim.integration.traincarts;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationSignParserTest {

    private final StationSignParser parser = new StationSignParser();

    @Test
    void detectsStationEntryWithVariousFormatting() {
        TrainCartsReflectionBinder.StationText plain =
                new TrainCartsReflectionBinder.StationText("[Train]", "Station");
        TrainCartsReflectionBinder.StationText colored =
                new TrainCartsReflectionBinder.StationText(ChatColor.BLUE + "[Cart]", ChatColor.GREEN + "Station: Arrivals");
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
        String input = ChatColor.DARK_RED + " [Cart-Express]  ";
        assertEquals("cart-express", parser.normalizeLine(input));
    }

    @Test
    void stripFormattingRemovesColorCodesAndWhitespace() {
        String input = ChatColor.GOLD + "  Station  ";
        assertEquals("Station", parser.stripFormatting(input));
    }
}
