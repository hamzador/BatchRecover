package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import javax.sql.DataSource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class OracleStorageContractIT extends PortableJdbcStorageContract {
    @Container static final OracleContainer DATABASE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(java.time.Duration.ofMinutes(5));
    @Override protected DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    @Override protected StandardSqlDialect expectedDialect() { return StandardSqlDialect.ORACLE; }
}
