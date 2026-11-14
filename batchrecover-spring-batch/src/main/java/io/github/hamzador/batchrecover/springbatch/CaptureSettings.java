package io.github.hamzador.batchrecover.springbatch;

public record CaptureSettings(String applicationScope, String handlerId, int maxAttempts, boolean transactional) {
    /** Existing callers keep mandatory capture. */
    public CaptureSettings(String applicationScope, String handlerId, int maxAttempts) {
        this(applicationScope, handlerId, maxAttempts, true);
    }

    public CaptureSettings {
        if (applicationScope == null || applicationScope.isBlank() || applicationScope.length() > 128
                || handlerId == null || handlerId.isBlank() || handlerId.length() > 128
                || maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("Invalid capture settings");
        }
    }
}
