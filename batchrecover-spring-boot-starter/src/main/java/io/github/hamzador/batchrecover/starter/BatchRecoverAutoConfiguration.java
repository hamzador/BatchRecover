package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.port.RejectionRepository;
import io.github.hamzador.batchrecover.core.port.RecoveryRepository;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.core.service.RecoveryEngine;
import io.github.hamzador.batchrecover.core.service.RecoveryHandlerRegistry;
import io.github.hamzador.batchrecover.springbatch.BatchCaptureContextResolver;
import io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

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
    static class EnabledConfiguration {

        @Bean
        @ConditionalOnMissingBean({RejectionRepository.class, RecoveryRepository.class})
        JdbcRejectionRepository batchRecoverRepository(ObjectProvider<JdbcTransactionManager> managers) {
            JdbcTransactionManager manager = managers.getIfUnique();
            if (manager == null || manager.getDataSource() == null) {
                throw new IllegalStateException("BatchRecover requires one unambiguous JdbcTransactionManager with a DataSource; "
                        + "configure the same manager as the chunk or provide both repository ports");
            }
            return new JdbcRejectionRepository(manager.getDataSource(), manager);
        }

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
                BatchCaptureContextResolver resolver, BatchRecoverProperties properties) {
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
}
