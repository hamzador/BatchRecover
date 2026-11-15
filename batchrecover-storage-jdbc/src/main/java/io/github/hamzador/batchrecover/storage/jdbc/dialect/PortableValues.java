package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

final class PortableValues {
    private PortableValues() {}
    static long micros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
    }
    static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                Math.floorMod(micros, 1_000_000L) * 1000);
    }
    static Instant readTime(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : instant(value);
    }
    static UUID readUuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }
    static Instant truncate(Instant time) { return time.truncatedTo(ChronoUnit.MICROS); }
}
