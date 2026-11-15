package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit extension registration. No generic fallback for an untested database. */
public final class SqlDialectRegistry {
    private final List<SqlDialect> dialects;

    public SqlDialectRegistry() { this(List.of()); }

    public SqlDialectRegistry(List<? extends SqlDialect> extensions) {
        var all = new ArrayList<SqlDialect>(List.of(StandardSqlDialect.values()));
        all.addAll(extensions);
        var names = new java.util.HashSet<String>();
        for (var dialect : all) {
            if (!names.add(dialect.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate SQL dialect name " + dialect.name());
            }
        }
        dialects = List.copyOf(all);
    }

    public SqlDialect resolve(DatabaseMetaData metadata, String requested) throws SQLException {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(requested);
        var matches = new ArrayList<SqlDialect>();
        for (var dialect : dialects) {
            if ((requested.equalsIgnoreCase("auto") || requested.equalsIgnoreCase(dialect.name()))
                    && dialect.supports(metadata)) matches.add(dialect);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("No unambiguous supported SQL dialect for "
                    + metadata.getDatabaseProductName() + " " + metadata.getDatabaseMajorVersion()
                    + "." + metadata.getDatabaseMinorVersion() + " (requested " + requested + ")");
        }
        return matches.getFirst();
    }
}
