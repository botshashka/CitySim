package dev.citysim.ui;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreboardServiceTest {

    private static final char SECTION = LegacyComponentSerializer.SECTION_CHAR;
    private static final String YELLOW_BOLD_PREFIX = "" + SECTION + 'e' + SECTION + 'l';

    @Test
    void trimObjectiveTitleTruncatesLongNamesWithinLimit() throws Exception {
        ScoreboardService service = new ScoreboardService(null, null, null, null, null);
        Method method = ScoreboardService.class.getDeclaredMethod("trimObjectiveTitle", String.class);
        assertTrue(method.trySetAccessible(), "trimObjectiveTitle should be accessible for testing");

        String prefix = YELLOW_BOLD_PREFIX;
        String longName = prefix + "This is a very very long city name that should be trimmed";

        String trimmed = (String) method.invoke(service, longName);

        assertTrue(trimmed.length() <= 32, "Trimmed title should not exceed 32 characters");
        assertTrue(trimmed.startsWith(prefix), "Prefix formatting should be preserved");
        assertTrue(trimmed.endsWith("…") || trimmed.length() < 32,
                "Long names should be shortened with an ellipsis when possible");
    }

    @Test
    void trimObjectiveTitleLeavesShortNamesUnchanged() throws Exception {
        ScoreboardService service = new ScoreboardService(null, null, null, null, null);
        Method method = ScoreboardService.class.getDeclaredMethod("trimObjectiveTitle", String.class);
        assertTrue(method.trySetAccessible(), "trimObjectiveTitle should be accessible for testing");

        String shortName = YELLOW_BOLD_PREFIX + "Short City";

        String result = (String) method.invoke(service, shortName);

        assertEquals(shortName, result, "Short titles should remain untouched");
    }
}
