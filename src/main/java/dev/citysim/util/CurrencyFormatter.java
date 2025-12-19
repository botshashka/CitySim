package dev.citysim.util;

import java.util.Locale;

/**
 * Formats currency values for City Credits (₡) with consistent rules.
 */
public final class CurrencyFormatter {

    public static final String SYMBOL = "₡";
    private static final char MINUS_SIGN = '\u2212';

    private CurrencyFormatter() {
    }

    public static String format(double amount) {
        return format(amount, false);
    }

    public static String format(long amount) {
        return format(amount, false);
    }

    public static String format(double amount, boolean compact) {
        long rounded = Math.round(amount);
        return format(rounded, compact);
    }

    public static String format(long amount, boolean compact) {
        boolean negative = amount < 0;
        long absolute = Math.abs(amount);
        String numeric = compact ? compactValue(absolute) : standardValue(absolute);
        String sign = negative ? String.valueOf(MINUS_SIGN) : "";
        return sign + SYMBOL + numeric;
    }

    private static String standardValue(long absolute) {
        return String.format(Locale.US, "%,d", absolute);
    }

    private static String compactValue(long absolute) {
        if (absolute < 1000L) {
            return standardValue(absolute);
        }
        double value = absolute;
        String[] suffixes = {"", "k", "M", "B", "T"};
        int index = 0;
        while (value >= 1000.0 && index < suffixes.length - 1) {
            value /= 1000.0;
            index++;
        }
        return String.format(Locale.US, "%,.1f%s", value, suffixes[index]);
    }
}
