package dev.citysim.util;

import java.util.Locale;

public final class FormatUtil {
    private FormatUtil() {
    }

    public static String formatShort(double value) {
        if (!Double.isFinite(value) || value == 0.0) {
            return "0";
        }
        double abs = Math.abs(value);
        String[] suffixes = {"", "K", "M", "B", "T"};
        int index = 0;
        while (abs >= 1000.0 && index < suffixes.length - 1) {
            value /= 1000.0;
            abs /= 1000.0;
            index++;
        }
        if (index == 0) {
            return String.format(Locale.US, "% ,.0f", value).trim();
        }
        return String.format(Locale.US, "% ,.1f%s", value, suffixes[index]).trim();
    }

    public static String formatNumber(long value) {
        return String.format(Locale.US, "% ,d", value).trim();
    }

    public static String formatPercent(double value) {
        return String.format(Locale.US, "%.2f%%", value * 100.0);
    }
}
