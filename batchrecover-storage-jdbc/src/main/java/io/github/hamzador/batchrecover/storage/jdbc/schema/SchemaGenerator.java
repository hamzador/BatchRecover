package io.github.hamzador.batchrecover.storage.jdbc.schema;

import io.github.hamzador.batchrecover.storage.jdbc.dialect.StandardSqlDialect;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Render two versioned schema migrations from trusted, immutable templates. No DDL side effect. */
public final class SchemaGenerator {
    private SchemaGenerator() {}

    public static List<String> generate(StandardSqlDialect dialect) {
        Objects.requireNonNull(dialect);
        if (dialect == StandardSqlDialect.POSTGRESQL) {
            return List.of(resource("V1__create_batchrecover_tables.sql"),
                    resource("V2__scope_worker_indexes.sql"));
        }
        String template = resource("templates/portable-v1.sql");
        String indexes = resource("templates/portable-v2.sql");
        return List.of(render(template, dialect), render(indexes, dialect));
    }

    private static String render(String template, StandardSqlDialect dialect) {
        String text = dialect == StandardSqlDialect.ORACLE ? "CLOB" : dialect == StandardSqlDialect.MYSQL ? "LONGTEXT" : "CLOB";
        String varchar = dialect == StandardSqlDialect.ORACLE ? "VARCHAR2" : "VARCHAR";
        String sql = template.replace("@UUID@", varchar + "(36" + (dialect == StandardSqlDialect.ORACLE ? " CHAR" : "") + ")")
                .replace("@LONG@", dialect == StandardSqlDialect.ORACLE ? "NUMBER(19)" : "BIGINT")
                .replace("@INTEGER@", dialect == StandardSqlDialect.ORACLE ? "NUMBER(10)" : "INTEGER")
                .replace("@PAYLOAD@", text)
                .replace("@TABLE_OPTIONS@", dialect == StandardSqlDialect.MYSQL
                        ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin" : "")
                .replace("@PAYLOAD_CHECK@", dialect == StandardSqlDialect.ORACLE ? ""
                        : " CHECK (OCTET_LENGTH(encoded_payload) BETWEEN 1 AND 65536)");
        return sql.replaceAll("@VARCHAR@\\((\\d+)\\)", varchar + "($1"
                + (dialect == StandardSqlDialect.ORACLE ? " CHAR" : "") + ")");
    }

    private static String resource(String name) {
        try (var stream = SchemaGenerator.class.getResourceAsStream("/db/batchrecover/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing schema resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read schema resource", ex);
        }
    }
}
