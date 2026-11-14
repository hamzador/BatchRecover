package io.github.hamzador.batchrecover.storage.jdbc;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import javax.sql.DataSource;


class H2StorageContractIT extends PortableJdbcStorageContract {

    @Override protected DataSource dataSource() {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:batchrecover_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Override protected StandardSqlDialect expectedDialect() { return StandardSqlDialect.H2; }
}
