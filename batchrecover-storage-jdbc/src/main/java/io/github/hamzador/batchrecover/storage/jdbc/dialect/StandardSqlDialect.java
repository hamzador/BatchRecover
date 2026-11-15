package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import io.github.hamzador.batchrecover.storage.jdbc.schema.SchemaGenerator;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Built-in dialect strategies; no vendor driver dependency. */
public enum StandardSqlDialect implements SqlDialect {
    POSTGRESQL("PostgreSQL", 16, 0, "SELECT clock_timestamp()", " LIMIT ?"),
    MYSQL("MySQL", 8, 4, "SELECT UTC_TIMESTAMP(6)", " LIMIT ?"),
    ORACLE("Oracle", 23, 0, "SELECT SYS_EXTRACT_UTC(SYSTIMESTAMP) FROM dual", " FETCH FIRST ? ROWS ONLY"),
    H2("H2", 2, 4, "SELECT CURRENT_TIMESTAMP", " FETCH FIRST ? ROWS ONLY");

    private final String product;
    private final int major;
    private final int minor;
    private final String clockSql;
    private final String limit;

    StandardSqlDialect(String product, int major, int minor, String clockSql, String limit) {
        this.product = product;
        this.major = major;
        this.minor = minor;
        this.clockSql = clockSql;
        this.limit = limit;
    }

    @Override public boolean supports(DatabaseMetaData metadata) throws SQLException {
        if (!product.equalsIgnoreCase(metadata.getDatabaseProductName())) return false;
        int actual = metadata.getDatabaseMajorVersion();
        return actual > major || (actual == major && metadata.getDatabaseMinorVersion() >= minor);
    }

    @Override public Object bindUuid(UUID value) {
        return this == POSTGRESQL || value == null ? value : value.toString();
    }

    @Override public UUID readUuid(ResultSet rs, String column) throws SQLException {
        return this == POSTGRESQL ? rs.getObject(column, UUID.class) : PortableValues.readUuid(rs, column);
    }

    @Override public Object bindTime(Instant value) {
        if (value == null) return null;
        return this == POSTGRESQL ? value.atOffset(ZoneOffset.UTC) : PortableValues.micros(value);
    }

    @Override public Instant readTime(ResultSet rs, String column) throws SQLException {
        if (this != POSTGRESQL) return PortableValues.readTime(rs, column);
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @Override public String databaseTimeSql() { return clockSql; }

    @Override public Instant readDatabaseTime(ResultSet rs) throws SQLException {
        Instant instant = this == POSTGRESQL || this == H2
                ? rs.getObject(1, OffsetDateTime.class).toInstant()
                : rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)).toInstant();
        return PortableValues.truncate(instant);
    }

    @Override public String limitClause() { return limit; }
    @Override public boolean supportsLockingLimit() { return this != ORACLE; }
    @Override public boolean hashedIdentity() { return this != POSTGRESQL; }
    @Override public String insertSuffix() {
        return this == POSTGRESQL ? " ON CONFLICT ON CONSTRAINT br_rejection_identity DO NOTHING" : "";
    }
    @Override public List<String> executionSettings() {
        return this == POSTGRESQL ? List.of("SET LOCAL statement_timeout = '30s'", "SET LOCAL lock_timeout = '5s'") : List.of();
    }

    @Override public List<String> migrationScripts() { return SchemaGenerator.generate(this); }
}
