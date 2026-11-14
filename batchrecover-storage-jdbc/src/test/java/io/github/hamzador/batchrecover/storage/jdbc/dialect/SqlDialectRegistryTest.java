package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlDialectRegistryTest {
    @Mock private DatabaseMetaData metadata;
    @InjectMocks private SqlDialectRegistry registry;

    @Test
    void shouldDetectSupportedPostgreSql() throws SQLException {
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getDatabaseMajorVersion()).thenReturn(16);
        when(metadata.getDatabaseMinorVersion()).thenReturn(0);
        assertSame(StandardSqlDialect.POSTGRESQL, registry.resolve(metadata, "auto"));
    }

    @Test
    void shouldDetectMySqlAndRejectExplicitOracleMismatch() throws SQLException {
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseMajorVersion()).thenReturn(8);
        when(metadata.getDatabaseMinorVersion()).thenReturn(4);
        assertSame(StandardSqlDialect.MYSQL, registry.resolve(metadata, "mysql"));
        assertThrows(IllegalStateException.class, () -> registry.resolve(metadata, "oracle"));
    }

    @Test
    void shouldRejectUnsupportedEngineWithoutFallback() throws SQLException {
        when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        when(metadata.getDatabaseMajorVersion()).thenReturn(11);
        when(metadata.getDatabaseMinorVersion()).thenReturn(8);
        assertThrows(IllegalStateException.class, () -> registry.resolve(metadata, "auto"));
    }

    @Test
    void shouldRejectOldH2WithoutSkipLockedGuarantee() throws SQLException {
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseMajorVersion()).thenReturn(2);
        when(metadata.getDatabaseMinorVersion()).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> registry.resolve(metadata, "auto"));
    }

    @Test
    void shouldResolveExplicitCustomExtension() throws SQLException {
        var extension = mock(SqlDialect.class);
        when(extension.name()).thenReturn("custom");
        when(extension.supports(metadata)).thenReturn(true);
        var custom = new SqlDialectRegistry(List.of(extension));
        assertSame(extension, custom.resolve(metadata, "custom"));
    }

    @Test
    void shouldRejectDuplicateDialectNames() {
        var extension = mock(SqlDialect.class);
        when(extension.name()).thenReturn("postgresql");
        assertThrows(IllegalArgumentException.class, () -> new SqlDialectRegistry(List.of(extension)));
    }

    @Test
    void shouldPreserveNegativeEpochMicrosecondsAndUuidEncoding() {
        var instant = Instant.parse("1969-12-31T23:59:59.999999Z");
        assertEquals(instant, PortableValues.instant(PortableValues.micros(instant)));
        assertEquals(-1L, StandardSqlDialect.MYSQL.bindTime(instant));
        var id = UUID.randomUUID();
        assertEquals(id, StandardSqlDialect.POSTGRESQL.bindUuid(id));
        assertEquals(id.toString(), StandardSqlDialect.ORACLE.bindUuid(id));
        assertNull(StandardSqlDialect.H2.bindTime(null));
    }
}
