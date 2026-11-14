package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.core.contract.RecoveryStorageContract;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcStorageContractIT extends RecoveryStorageContract {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Override protected RecoveryStorage createStorage() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS br_recovery_attempt, br_audit_event, br_payload_revision, br_rejection CASCADE");
        new ResourceDatabasePopulator(new ClassPathResource("db/batchrecover/V1__create_batchrecover_tables.sql"),
                new ClassPathResource("db/batchrecover/V2__scope_worker_indexes.sql")).execute(source);
        return new JdbcRejectionRepository(source, new JdbcTransactionManager(source));
    }

    @Test
    void shouldAdvertiseJdbcCapabilities() {
        assertEquals(StorageCapabilities.JDBC, storage.capabilities());
    }
}
