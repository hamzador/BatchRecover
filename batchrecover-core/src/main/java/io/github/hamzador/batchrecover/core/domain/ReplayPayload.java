package io.github.hamzador.batchrecover.core.domain;

import java.nio.charset.StandardCharsets;

/** Caller-approved, encoded payload. Never use raw secrets or arbitrary Java serialization. */
public record ReplayPayload(String typeId, int schemaVersion, String encoded) {
    public static final int MAX_BYTES = 65_536;

    public ReplayPayload {
        DomainChecks.text(typeId, "typeId", 128);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        DomainChecks.text(encoded, "encoded", MAX_BYTES);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !StandardCharsets.UTF_8.newEncoder().canEncode(encoded)) {
            throw new IllegalArgumentException("Invalid payload encoding or size");
        }
    }

    @Override
    public String toString() {
        return "ReplayPayload[typeId=" + typeId + ", schemaVersion=" + schemaVersion + ", encoded=<redacted>]";
    }
}
