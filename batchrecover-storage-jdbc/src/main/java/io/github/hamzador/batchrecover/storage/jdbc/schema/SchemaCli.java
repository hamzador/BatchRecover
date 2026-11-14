package io.github.hamzador.batchrecover.storage.jdbc.schema;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Offline migration generator. Usage: SchemaCli DIALECT OUTPUT_DIRECTORY. */
public final class SchemaCli {
    private SchemaCli() {}
    public static void main(String[] args) throws java.io.IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: SchemaCli DIALECT OUTPUT_DIRECTORY");
        var dialect = StandardSqlDialect.valueOf(args[0].toUpperCase(Locale.ROOT));
        var scripts = SchemaGenerator.generate(dialect);
        var dir = Files.createDirectories(Path.of(args[1]));
        Files.writeString(dir.resolve("V1__create_batchrecover_tables.sql"), scripts.get(0),
                java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(dir.resolve("V2__scope_worker_indexes.sql"), scripts.get(1),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
