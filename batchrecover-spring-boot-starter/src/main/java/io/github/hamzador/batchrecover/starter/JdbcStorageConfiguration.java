package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.port.RejectionRepository;
import io.github.hamzador.batchrecover.core.port.RecoveryRepository;
import io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository;
import org.springframework.beans.factory.ObjectProvider;
import io.github.hamzador.batchrecover.storage.jdbc.dialect.SqlDialect;
import io.github.hamzador.batchrecover.storage.jdbc.dialect.SqlDialectRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Isolate optional JDBC types so the core starter loads without their classes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {"io.github.hamzador.batchrecover.storage.jdbc.JdbcRejectionRepository",
        "org.springframework.jdbc.support.JdbcTransactionManager"})
@ConditionalOnProperty(prefix = "batchrecover", name = "enabled", havingValue = "true")
class JdbcStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "batchrecover.storage", name = "type", havingValue = "jdbc", matchIfMissing = true)
    @ConditionalOnMissingBean({RejectionRepository.class, RecoveryRepository.class})
    JdbcRejectionRepository batchRecoverRepository(ObjectProvider<JdbcTransactionManager> managers,
            ObjectProvider<SqlDialect> extensions, BatchRecoverProperties properties) {
        JdbcTransactionManager manager = managers.getIfUnique();
        if (manager == null || manager.getDataSource() == null) {
            throw new IllegalStateException("JDBC mode requires one unambiguous JdbcTransactionManager with a DataSource");
        }
        return new JdbcRejectionRepository(manager.getDataSource(), manager,
                new SqlDialectRegistry(extensions.orderedStream().toList()), properties.storage().dialect());
    }
}
