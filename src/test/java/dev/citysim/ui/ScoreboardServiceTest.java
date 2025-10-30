package dev.citysim.ui;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardServiceTest {

    @Test
    void trimObjectiveTitleTruncatesLongNamesWithinLimit() throws Exception {
        ScoreboardService service = new ScoreboardService(null, null, null, null);
        Method method = ScoreboardService.class.getDeclaredMethod("trimObjectiveTitle", String.class);
        method.setAccessible(true);

        String prefix = ChatColor.YELLOW + "" + ChatColor.BOLD;
        String longName = prefix + "This is a very very long city name that should be trimmed";

        String trimmed = (String) method.invoke(service, longName);

        assertTrue(trimmed.length() <= 32, "Trimmed title should not exceed 32 characters");
        assertTrue(trimmed.startsWith(prefix), "Prefix formatting should be preserved");
        assertTrue(trimmed.endsWith("…") || trimmed.length() < 32,
                "Long names should be shortened with an ellipsis when possible");
    }

    @Test
    void trimObjectiveTitleLeavesShortNamesUnchanged() throws Exception {
        ScoreboardService service = new ScoreboardService(null, null, null, null);
        Method method = ScoreboardService.class.getDeclaredMethod("trimObjectiveTitle", String.class);
        method.setAccessible(true);

        String shortName = ChatColor.YELLOW + "" + ChatColor.BOLD + "Short City";

        String result = (String) method.invoke(service, shortName);

        assertEquals(shortName, result, "Short titles should remain untouched");
    }
}
