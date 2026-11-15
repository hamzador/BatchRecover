package io.github.hamzador.batchrecover.core.port;

import io.github.hamzador.batchrecover.core.domain.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Worker operations provide exclusive claims and fenced execution. Only transactional storage can roll back handler effects. */
public interface RecoveryRepository {
    /** Reserve at most one eligible record and issue a fresh token. */
    Optional<RecoveryClaim> claimNext(String applicationScope, Duration leaseDuration);
    /** Execute a still-live claim; false means no callback ran. */
    boolean execute(RecoveryClaim claim, Consumer<RecoveryWork> callback);
    /** Record a classified failure; empty means the claim lost its fence. */
    Optional<RejectionStatus> fail(RecoveryClaim claim, RecoveryFailure failure, Duration delay);
    /** Reap at most one expired nonexecuting claim. */
    boolean reapExpired(String applicationScope, IntFunction<Duration> delayForAttempt);
    /** Return ordered attempt history. */
    List<RecoveryAttempt> findAttempts(UUID rejectionId);
}
