package io.github.hamzador.batchrecover.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Registers configuration only; does not launch jobs, capture skips or migrate a database. */
@AutoConfiguration
@EnableConfigurationProperties(BatchRecoverProperties.class)
public class BatchRecoverAutoConfiguration {
}
