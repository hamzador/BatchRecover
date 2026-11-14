package io.github.hamzador.batchrecover.springbatch;

import io.github.hamzador.batchrecover.core.domain.ErrorCategory;

@FunctionalInterface
public interface ErrorClassifier {
    ClassifiedError classify(Throwable failure);

    static ErrorClassifier unknown() {
        return failure -> new ClassifiedError("UNCLASSIFIED", ErrorCategory.UNKNOWN);
    }
}
