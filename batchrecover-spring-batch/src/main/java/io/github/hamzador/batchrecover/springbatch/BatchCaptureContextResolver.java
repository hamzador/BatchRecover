package io.github.hamzador.batchrecover.springbatch;

import org.springframework.batch.core.scope.context.StepSynchronizationManager;

/** Resolve on each callback, not from mutable state in a singleton listener. */
public class BatchCaptureContextResolver {
    public BatchCaptureContext current() {
        var context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No active Spring Batch step context");
        }
        var step = context.getStepExecution();
        var job = step.getJobExecution();
        if (step.getId() < 0 || job.getId() < 0 || job.getJobInstanceId() < 0) {
            throw new IllegalStateException("Nonnegative Spring Batch execution IDs are required");
        }
        return new BatchCaptureContext(job.getJobInstanceId(), job.getId(), step.getId(), step.getStepName());
    }
}
