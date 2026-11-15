package io.github.hamzador.batchrecover.storage.jdbc.schema;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import io.github.hamzador.batchrecover.storage.jdbc.dialect.IdentityDigest;
import io.github.hamzador.batchrecover.core.domain.RejectionIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SchemaGeneratorTest {
    @TempDir Path directory;

    @Test
    void shouldRenderMigrationScriptsForEveryDialectWithoutUnresolvedTokens() {
        for (var dialect : StandardSqlDialect.values()) {
            var scripts = SchemaGenerator.generate(dialect);
            assertEquals(2, scripts.size());
            assertFalse(scripts.getFirst().contains("@"));
            assertFalse(scripts.getLast().contains("@"));
            for (String table : java.util.List.of("br_rejection", "br_payload_revision", "br_audit_event", "br_recovery_attempt"))
                assertTrue(scripts.getFirst().contains("CREATE TABLE " + table));
            assertTrue(scripts.getLast().contains("br_rejection_scope_due_idx"));
        }
        assertTrue(SchemaGenerator.generate(StandardSqlDialect.POSTGRESQL).getFirst().contains("DEFERRABLE INITIALLY DEFERRED"));
        assertTrue(SchemaGenerator.generate(StandardSqlDialect.MYSQL).getFirst().contains("utf8mb4_0900_bin"));
        assertTrue(SchemaGenerator.generate(StandardSqlDialect.ORACLE).getFirst().contains("VARCHAR2(128 CHAR)"));
    }

    @Test
    void shouldGenerateFilesWithoutOverwritingExistingMigrations() throws Exception {
        SchemaCli.main(new String[]{"h2", directory.toString()});
        var file = directory.resolve("V1__create_batchrecover_tables.sql");
        assertEquals(SchemaGenerator.generate(StandardSqlDialect.H2).getFirst(), Files.readString(file));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> SchemaCli.main(new String[]{"mysql", directory.toString()}));
    }

    @Test
    void shouldHashLengthDelimitedIdentityWithoutAmbiguousConcatenation() {
        var a = new RejectionIdentity("app", 1, "a:b", "c");
        var b = new RejectionIdentity("app", 1, "a", "b:c");
        assertNotEquals(IdentityDigest.of(a), IdentityDigest.of(b));
        assertEquals(IdentityDigest.of(a), IdentityDigest.of(new RejectionIdentity("app", 1, "a:b", "c")));
        assertEquals(64, IdentityDigest.of(a).length());
    }
}
