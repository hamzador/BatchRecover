package io.github.hamzador.batchrecover.core.port;

/** Guarantees of a storage implementation, conditional on the documented host contract. */
public record StorageCapabilities(boolean durable, boolean multiInstance,
                                  boolean transactionalCapture, boolean transactionalReplay) {
    public static final StorageCapabilities MEMORY = new StorageCapabilities(false, false, false, false);
    public static final StorageCapabilities JDBC = new StorageCapabilities(true, true, true, true);
}
