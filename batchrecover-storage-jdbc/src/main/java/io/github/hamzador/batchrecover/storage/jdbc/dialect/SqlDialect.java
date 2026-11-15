package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Extension point for transactional SQL stores. SQL fragments are trusted application code, not user input. */
public interface SqlDialect {
    String name();
    boolean supports(DatabaseMetaData metadata) throws SQLException;
    Object bindUuid(UUID value);
    UUID readUuid(ResultSet rs, String column) throws SQLException;
    Object bindTime(Instant value);
    Instant readTime(ResultSet rs, String column) throws SQLException;
    String databaseTimeSql();
    Instant readDatabaseTime(ResultSet rs) throws SQLException;
    String limitClause();
    /** Whether the dialect permits row limiting and SKIP LOCKED in one SELECT. */
    default boolean supportsLockingLimit() { return false; }
    default String lockClause() { return " FOR UPDATE SKIP LOCKED"; }
    default boolean hashedIdentity() { return true; }
    default String insertSuffix() { return ""; }
    default List<String> executionSettings() { return List.of(); }
    /** Ordered, versioned DDL scripts. Never executed automatically. */
    List<String> migrationScripts();
}
