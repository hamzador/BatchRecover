package example.consumer.jdbc;

import org.junit.jupiter.api.Test;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class JdbcCustomerApplicationTest {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:16-alpine");
    @Test
    void shouldRunRealBatchAndRecoverOnlySelectedRowUsingInstalledArtifacts() throws Exception {
        try (var context = JdbcCustomerApplication.start("--demo.jdbc-url=" + DATABASE.getJdbcUrl(),
                "--demo.db-user=" + DATABASE.getUsername(),
                "--demo.db-password=" + DATABASE.getPassword(), "--demo.initialize=true")) {
            org.junit.jupiter.api.Assertions.assertEquals(StorageCapabilities.JDBC,
                    context.getBean(RecoveryStorage.class).capabilities());
            org.junit.jupiter.api.Assertions.assertEquals("POSTGRESQL",
                    context.getBean(io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository.class).dialect().name());
            org.junit.jupiter.api.Assertions.assertEquals(new JdbcCustomerApplication.Outcome(8, 2, 1, 1, 9),
                    JdbcCustomerApplication.run(context));
        }
    }
}
