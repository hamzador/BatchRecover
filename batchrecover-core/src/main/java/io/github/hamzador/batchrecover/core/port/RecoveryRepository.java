package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Worker operations own separate transactions. Execution invokes the callback under a row lock. */
public interface RecoveryRepository {
    Optional<RecoveryClaim> claimNext(String applicationScope, Duration leaseDuration);
    boolean execute(RecoveryClaim claim, Consumer<RecoveryWork> callback);
    Optional<RejectionStatus> fail(RecoveryClaim claim, RecoveryFailure failure, Duration delay);
    boolean reapExpired(String applicationScope, IntFunction<Duration> delayForAttempt);
    List<RecoveryAttempt> findAttempts(UUID rejectionId);
}
