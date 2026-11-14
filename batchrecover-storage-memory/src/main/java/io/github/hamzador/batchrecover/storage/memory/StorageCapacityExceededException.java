package io.github.hamzador.batchrecover.storage.memory;

/** No records, revisions, or audit history are silently dropped to make room. */
public final class StorageCapacityExceededException extends RuntimeException {
    public StorageCapacityExceededException(String limit) {
        super("Memory storage capacity exceeded: " + limit);
    }
}
