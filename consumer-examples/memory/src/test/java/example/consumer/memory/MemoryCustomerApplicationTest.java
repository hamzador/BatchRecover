package example.consumer.memory;

import org.junit.jupiter.api.Test;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;

class MemoryCustomerApplicationTest {

    @Test
    void shouldRunRealBatchAndRecoverOnlySelectedRowUsingInstalledArtifacts() throws Exception {
        try (var context = MemoryCustomerApplication.start()) {
            org.junit.jupiter.api.Assertions.assertEquals(StorageCapabilities.MEMORY,
                    context.getBean(RecoveryStorage.class).capabilities());
            org.junit.jupiter.api.Assertions.assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class, () ->
                    Class.forName("io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository"));
            org.junit.jupiter.api.Assertions.assertEquals(new MemoryCustomerApplication.Outcome(8, 2, 1, 1, 9),
                    MemoryCustomerApplication.run(context));
        }
    }
}
