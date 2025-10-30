package dev.citysim.stats;

/**
 * Represents the result of counting stations, including the derived station total
 * and the raw sign count reported by the integration.
 */
public record StationCountResult(int stations, int signs) {

    public StationCountResult {
        stations = Math.max(0, stations);
        signs = Math.max(0, signs);
    }
}
