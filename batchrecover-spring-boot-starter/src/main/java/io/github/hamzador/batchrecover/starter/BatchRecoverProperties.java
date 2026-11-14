package io.github.hamzador.batchrecover.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feature activation is reserved for subsequent milestones. */
@ConfigurationProperties("batchrecover")
public record BatchRecoverProperties(boolean enabled) {
}
