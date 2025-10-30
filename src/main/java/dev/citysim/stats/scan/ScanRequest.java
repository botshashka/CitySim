package dev.citysim.stats.scan;

public record ScanRequest(boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {

    public ScanRequest(boolean forceRefresh, String reason, ScanContext context) {
        this(forceRefresh, false, reason, context);
    }

    public ScanRequest merge(ScanRequest other) {
        if (other == null) {
            return this;
        }
        boolean mergedForce = this.forceRefresh || other.forceRefresh;
        boolean mergedForceChunk = this.forceChunkLoad || other.forceChunkLoad;
        String mergedReason = other.reason != null ? other.reason : this.reason;
        ScanContext mergedContext = other.context != null ? other.context : this.context;
        return new ScanRequest(mergedForce, mergedForceChunk, mergedReason, mergedContext);
    }
}
