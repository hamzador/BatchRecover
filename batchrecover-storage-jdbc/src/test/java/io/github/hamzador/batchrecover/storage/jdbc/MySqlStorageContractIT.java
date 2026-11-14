package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import javax.sql.DataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MySqlStorageContractIT extends PortableJdbcStorageContract {
    @Container static final MySQLContainer DATABASE = new MySQLContainer("mysql:8.4")
            .withCommand("--transaction-isolation=READ-COMMITTED");
    @Override protected DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }

    @Override protected StandardSqlDialect expectedDialect() { return StandardSqlDialect.MYSQL; }
}
