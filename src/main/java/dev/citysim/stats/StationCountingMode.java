package dev.citysim.stats;

import java.util.Locale;

public enum StationCountingMode {
    MANUAL,
    TRAIN_CARTS,
    DISABLED;

    public static StationCountingMode fromConfig(String value) {
        if (value == null) {
            return MANUAL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "traincarts", "train_carts", "train-carts" -> TRAIN_CARTS;
            case "disabled", "off" -> DISABLED;
            default -> MANUAL;
        };
    }
}
