package io.github.hamzador.batchrecover.storage.jdbc.dialect;

import io.github.hamzador.batchrecover.core.domain.RejectionIdentity;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Length-delimited canonical tuple encoding, independent of SQL collation and index byte limits. */
public final class IdentityDigest {
    private IdentityDigest() {}
    public static String of(RejectionIdentity identity) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(buffer)) {
                data.writeUTF(identity.applicationScope());
                data.writeLong(identity.jobInstanceId());
                data.writeUTF(identity.stepName());
                data.writeUTF(identity.sourceOccurrenceId());
                data.writeUTF(identity.phase().name());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot encode identity", ex);
        }
    }
}
