package dev.citysim.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-city metric trends and emits arrow glyphs that summarize the
 * change between consecutive cached snapshots.
 */
public final class TrendUtil {

    private static final int HISTORY_SIZE = 8;

    private final Map<String, EnumMap<Metric, Deque<Double>>> history = new ConcurrentHashMap<>();

    public TrendDirection trendFor(String cityId, Metric metric, double currentValue) {
        if (cityId == null || metric == null || !Double.isFinite(currentValue)) {
            return TrendDirection.FLAT;
        }
        EnumMap<Metric, Deque<Double>> byMetric = history.computeIfAbsent(cityId, id -> new EnumMap<>(Metric.class));
        Deque<Double> samples = byMetric.computeIfAbsent(metric, key -> new ArrayDeque<>(HISTORY_SIZE));
        Double baseline = samples.peekFirst();
        TrendDirection direction = TrendDirection.FLAT;
        if (baseline != null && Double.isFinite(baseline)) {
            double delta = currentValue - baseline;
            boolean changed = metric.requireExactChange ? delta != 0.0 : Math.abs(delta) >= metric.threshold;
            if (changed) {
                direction = delta > 0.0 ? TrendDirection.UP : TrendDirection.DOWN;
            }
        }
        samples.addLast(currentValue);
        if (samples.size() > HISTORY_SIZE) {
            samples.removeFirst();
        }
        return direction;
    }

    public enum Metric {
        PROSPERITY(0.5, false),
        GDP(0.5, false),
        GDP_PER_CAPITA(0.5, false),
        LAND_VALUE(0.5, false),
        JOBS_DELTA(0.01, false),
        HOUSING_DELTA(0.01, false),
        LINKS(0.0, true),
        MIGRATION(0.0, true);

        private final double threshold;
        private final boolean requireExactChange;

        Metric(double threshold, boolean requireExactChange) {
            this.threshold = threshold;
            this.requireExactChange = requireExactChange;
        }
    }

    public enum TrendDirection {
        UP("↑"),
        DOWN("↓"),
        FLAT("");

        private final String glyph;

        TrendDirection(String glyph) {
            this.glyph = glyph;
        }

        public String glyph() {
            return glyph;
        }
    }
}
