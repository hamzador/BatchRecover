package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.port.RejectionRepository;
import io.github.hamzador.batchrecover.core.port.RecoveryRepository;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.core.service.RecoveryEngine;
import io.github.hamzador.batchrecover.core.service.RecoveryHandlerRegistry;
import io.github.hamzador.batchrecover.springbatch.BatchCaptureContextResolver;
import io.github.hamzador.batchrecover.storage.memory.InMemoryRecoveryStorage;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.util.List;

/** Local API only: no jobs, schema initialization, or background threads are created. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
})
@EnableConfigurationProperties(BatchRecoverProperties.class)
public class BatchRecoverAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "batchrecover", name = "enabled", havingValue = "true")
    @Import({JdbcStorageConfiguration.class, MemoryStorageConfiguration.class})
    static class EnabledConfiguration {


        @Bean
        @ConditionalOnMissingBean
        RejectionService batchRecoverRejectionService(RejectionRepository repository) {
            return new RejectionService(repository, Clock.systemUTC());
        }

        @Bean
        @ConditionalOnMissingBean
        RecoveryHandlerRegistry batchRecoverHandlerRegistry(
                ObjectProvider<RecoveryHandlerRegistry.Registration<?>> registrations) {
            List<RecoveryHandlerRegistry.Registration<?>> handlers = registrations.orderedStream().toList();
            return new RecoveryHandlerRegistry(handlers);
        }

        @Bean
        @ConditionalOnMissingBean
        RecoveryEngine batchRecoverEngine(RecoveryRepository repository, RecoveryHandlerRegistry registry,
                                         BatchRecoverProperties properties) {
            return new RecoveryEngine(repository, registry, properties.workerSettings());
        }

        @Bean
        @ConditionalOnMissingBean
        BatchCaptureContextResolver batchRecoverContextResolver() {
            return new BatchCaptureContextResolver();
        }

        @Bean
        @ConditionalOnMissingBean
        BatchRecoverListenerFactory batchRecoverListenerFactory(RejectionService service,
                BatchCaptureContextResolver resolver, BatchRecoverProperties properties,
                RejectionRepository rejectionRepository, RecoveryRepository recoveryRepository) {
            boolean memory = properties.storage().type() == BatchRecoverProperties.StorageType.MEMORY;
            if (memory && (!(rejectionRepository instanceof RecoveryStorage storage)
                    || rejectionRepository != recoveryRepository
                    || storage.capabilities().transactionalCapture()
                    || storage.capabilities().durable())) {
                throw new IllegalStateException("Memory mode requires one non-durable, non-transactional RecoveryStorage");
            }
            return new BatchRecoverListenerFactory(service, resolver, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        BatchRecoverWorker batchRecoverWorker(RecoveryEngine engine, BatchRecoverProperties properties,
                                              ObjectProvider<MeterRegistry> meterRegistries) {
            return new BatchRecoverWorker(engine,
                    () -> properties.metricsEnabled() ? meterRegistries.getIfUnique() : null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "batchrecover", name = "enabled", havingValue = "true")
    static class MemoryStorageConfiguration {
        @Bean
        @ConditionalOnProperty(prefix = "batchrecover.storage", name = "type", havingValue = "memory")
        @ConditionalOnMissingBean({RejectionRepository.class, RecoveryRepository.class})
        InMemoryRecoveryStorage batchRecoverMemoryStorage(BatchRecoverProperties properties) {
            return new InMemoryRecoveryStorage(Clock.systemUTC(), properties.storage().memoryLimits());
        }
    }
}
