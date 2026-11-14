package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.Rejection;
import java.util.List;

/** Optional unified provider; existing separate repository ports remain supported. */
public interface RecoveryStorage extends RejectionRepository, RecoveryRepository {
    StorageCapabilities capabilities();
    /** First page of pending rejections, scoped to the application, with limit 1–1000. */
    List<Rejection> findPending(String applicationScope, int limit);
}
