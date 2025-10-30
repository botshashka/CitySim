package dev.citysim.integration.traincarts;

import org.bukkit.ChatColor;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/**
 * Helper responsible for classifying TrainCarts sign text. The logic previously lived inside the
 * station service but is extracted so it can be unit-tested independently.
 */
public class StationSignParser {

    public boolean isStationEntry(Collection<TrainCartsReflectionBinder.StationText> texts) {
        if (texts == null || texts.isEmpty()) {
            return false;
        }
        for (TrainCartsReflectionBinder.StationText text : texts) {
            if (text == null) {
                continue;
            }
            if (isStationLines(text.header(), text.action())) {
                return true;
            }
        }
        return false;
    }

    public boolean isStationLines(String header, String action) {
        if (header == null || action == null) {
            return false;
        }
        if (!isTrainOrCartHeader(header)) {
            return false;
        }
        return isStationActionLine(action);
    }

    public String normalizeLine(String line) {
        String stripped = stripFormatting(line);
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.charAt(0) == '[' && stripped.length() > 1) {
            stripped = stripped.substring(1);
        }
        if (!stripped.isEmpty() && stripped.charAt(stripped.length() - 1) == ']') {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        stripped = stripped.trim();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    public String stripFormatting(String line) {
        if (line == null) {
            return "";
        }
        String stripped = ChatColor.stripColor(line);
        if (stripped == null) {
            stripped = line;
        }
        return stripped.trim();
    }

    private boolean isTrainOrCartHeader(String line) {
        String cleaned = normalizeLine(line);
        if (cleaned == null) {
            return false;
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }
        cleaned = stripPrefixCharacters(cleaned, "+-!/\\");
        if (cleaned.isEmpty()) {
            return false;
        }
        return Objects.equals(cleaned, "train") || Objects.equals(cleaned, "cart");
    }

    private boolean isStationActionLine(String line) {
        String cleaned = normalizeLine(line);
        if (cleaned == null) {
            return false;
        }
        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            cleaned = cleaned.substring(0, colon);
        }
        cleaned = stripPrefixCharacters(cleaned, "+-!/\\");
        return Objects.equals(cleaned, "station");
    }

    private String stripPrefixCharacters(String input, String characters) {
        int index = 0;
        while (index < input.length() && characters.indexOf(input.charAt(index)) >= 0) {
            index++;
        }
        return index >= input.length() ? "" : input.substring(index);
    }
}
