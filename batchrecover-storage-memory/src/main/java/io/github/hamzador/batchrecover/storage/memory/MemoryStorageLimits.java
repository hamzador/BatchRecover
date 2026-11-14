package io.github.hamzador.batchrecover.storage.memory;

/** Hard bounds include terminal records and all retained revisions; no silent eviction. */
public record MemoryStorageLimits(int maxRecords, int maxRevisionsPerRecord, long maxPayloadBytes) {
    public static final MemoryStorageLimits DEFAULT = new MemoryStorageLimits(1000, 8, 16L * 1024 * 1024);

    public MemoryStorageLimits {
        if (maxRecords < 1 || maxRecords > 100_000 || maxRevisionsPerRecord < 1
                || maxRevisionsPerRecord > 100 || maxPayloadBytes < 1 || maxPayloadBytes > 256L * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid memory storage limits");
        }
    }
}
