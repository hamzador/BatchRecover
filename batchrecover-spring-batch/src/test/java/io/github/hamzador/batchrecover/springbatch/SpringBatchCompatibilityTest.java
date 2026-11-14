package io.github.hamzador.batchrecover.springbatch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringBatchCompatibilityTest {

    @Test
    void shouldExposeJobInstanceProvenance() {
        var jobInstance = new JobInstance(42L, "customerImport");

        assertEquals(42L, jobInstance.getInstanceId());
        assertEquals("customerImport", jobInstance.getJobName());
    }
}
