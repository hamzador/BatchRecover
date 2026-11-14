package io.github.hamzador.batchrecover.springbatch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;

import static org.junit.jupiter.api.Assertions.*;

class BatchCaptureContextResolverTest {
    private final BatchCaptureContextResolver resolver = new BatchCaptureContextResolver();

    @Test
    void shouldResolveActualStepProvenance() {
        var job = new JobExecution(22, new JobInstance(11, "import"),
                new JobParametersBuilder().toJobParameters());
        var step = new StepExecution(33, "customerStep", job);
        StepSynchronizationManager.register(step);
        try {
            assertEquals(new BatchCaptureContext(11, 22, 33, "customerStep"), resolver.current());
        } finally {
            StepSynchronizationManager.close();
        }
    }

    @Test
    void shouldRejectMissingStepContext() {
        assertThrows(IllegalStateException.class, resolver::current);
    }
}
