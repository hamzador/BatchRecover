package io.github.hamzador.batchrecover.core.port;

/** Optional unified provider; existing separate repository ports remain supported. */
public interface RecoveryStorage extends RejectionRepository, RecoveryRepository {
    StorageCapabilities capabilities();
}
