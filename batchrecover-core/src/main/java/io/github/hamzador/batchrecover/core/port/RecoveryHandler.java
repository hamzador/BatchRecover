package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.RecoveryContext;

/** Synchronous application replay. JDBC effects must join the worker's physical transaction.
 * Memory storage cannot roll back effects. Use stable idempotency keys.
 * @param <T> decoded payload type
 */
public interface RecoveryHandler<T> {
    /** Revalidate identity and business rules; throw RecoveryValidationException for correction. */
    void validate(T item, RecoveryContext context);
    /** Apply the intended business effect only; normal return signals success. */
    void replay(T item, RecoveryContext context);
}
