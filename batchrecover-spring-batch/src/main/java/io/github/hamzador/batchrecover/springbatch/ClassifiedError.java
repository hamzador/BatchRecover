package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.ErrorCategory;
import java.util.Objects;

/** Safe code only; never copy exception messages or stack traces. */
public record ClassifiedError(String code, ErrorCategory category) {
    public ClassifiedError {
        if (code == null || !code.matches("[A-Z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("Invalid sanitized error code");
        }
        Objects.requireNonNull(category);
    }
}
