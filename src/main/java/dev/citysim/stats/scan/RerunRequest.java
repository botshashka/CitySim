package dev.citysim.stats.scan;

public record RerunRequest(boolean requested, boolean forceRefresh, boolean forceChunkLoad, String reason, ScanContext context) {
}
