package io.github.hamzador.batchrecover.core.port;

/** Optional unified provider; existing separate repository ports remain supported. */
import io.github.hamzador.batchrecover.core.domain.Rejection;
import java.util.List;

/** Query pending rejects in a bounded, application-scoped page. */
public interface RecoveryStorage extends RejectionRepository, RecoveryRepository {
    StorageCapabilities capabilities();
    List<Rejection> findPending(String applicationScope, int limit);
}
