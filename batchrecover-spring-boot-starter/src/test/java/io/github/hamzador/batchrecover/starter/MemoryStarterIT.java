package io.github.hamzador.batchrecover.starter;

import io.github.hamzador.batchrecover.core.codec.VersionedPayloadCodec;
import io.github.hamzador.batchrecover.core.domain.*;
import io.github.hamzador.batchrecover.core.port.RecoveryHandler;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.service.RecoveryHandlerRegistry;
import io.github.hamzador.batchrecover.core.service.RejectionService;
import io.github.hamzador.batchrecover.springbatch.ClassifiedError;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryStarterIT {
    record Customer(int row, String email) {}
    static class InvalidCustomer extends RuntimeException {}

    @Test
    void shouldCaptureAndRecoverWithoutDatabaseOrJdbcAdapter() {
        var effects = new HashMap<Integer, Customer>();
        var codec = new VersionedPayloadCodec<Customer>("customer", 1,
                c -> c.row() + ":" + c.email(), Map.of(1, s -> {
                    var parts = s.split(":", 2);
                    return new Customer(Integer.parseInt(parts[0]), parts[1]);
                }));
        RecoveryHandler<Customer> handler = new RecoveryHandler<>() {
            @Override public void validate(Customer item, RecoveryContext ctx) {
                if (!item.email().contains("@")) throw new IllegalArgumentException("Invalid customer");
            }
            @Override public void replay(Customer item, RecoveryContext ctx) { effects.put(item.row(), item); }
        };
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("io.github.hamzador.batchrecover.storage.jdbc", "org.springframework.jdbc"))
                .withConfiguration(AutoConfigurations.of(BatchRecoverAutoConfiguration.class))
                .withPropertyValues("batchrecover.enabled=true", "batchrecover.application-scope=one-shot",
                        "batchrecover.storage.type=memory")
                .withBean(RecoveryHandlerRegistry.Registration.class,
                        () -> new RecoveryHandlerRegistry.Registration<>("customer", codec, handler))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(DataSource.class);
                    var storage = context.getBean(RecoveryStorage.class);
                    var service = context.getBean(RejectionService.class);
                    var listener = context.getBean(BatchRecoverListenerFactory.class)
                            .<Customer, Customer>create("customer", c -> "input-1:" + c.row(),
                                    codec, ex -> new ClassifiedError("INVALID_EMAIL", ErrorCategory.BUSINESS));
                    var repository = new ResourcelessJobRepository();
                    var step = new StepBuilder("import", repository)
                            .<Customer,Customer>chunk(10)
                            .transactionManager(new ResourcelessTransactionManager())
                            .reader(new ListItemReader<>(java.util.stream.IntStream.rangeClosed(1, 100)
                                    .mapToObj(i -> new Customer(i, i % 10 == 0 ? "invalid" : "c" + i + "@example.org")).toList()))
                            .processor(c -> {
                                if (!c.email().contains("@")) throw new InvalidCustomer();
                                return c;
                            })
                            .writer(chunk -> { for (var c : chunk) effects.put(c.row(), c); })
                            .faultTolerant().skip(InvalidCustomer.class).skipLimit(10)
                            .skipListener(listener).build();
                    var job = new JobBuilder("memoryImport", repository).start(step).build();
                    var operator = new TaskExecutorJobOperator();
                    operator.setJobRepository(repository);
                    operator.setJobRegistry(new MapJobRegistry());
                    operator.afterPropertiesSet();
                    var execution = operator.start(job, new JobParametersBuilder().toJobParameters());
                    assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                    assertThat(execution.getStepExecutions().iterator().next().getProcessSkipCount()).isEqualTo(10);
                    assertThat(effects).hasSize(90);
                    var originals = Map.copyOf(effects);
                    var pending = storage.findPending("one-shot", 100);
                    assertThat(pending).hasSize(10);
                    for (var reject : pending.subList(0, 3)) {
                        var original = storage.findRevisions(reject.id()).getFirst().payload();
                        var customer = codec.decode(original.encoded(), original.schemaVersion());
                        var fixed = new Customer(customer.row(), "fixed" + customer.row() + "@example.org");
                        var corrected = service.correct(reject.id(), reject.version(),
                                new ReplayPayload(codec.typeId(), 1, codec.encode(fixed)), "operator", "fix");
                        service.queue(reject.id(), corrected.version(), "operator", "replay");
                    }
                    var worker = context.getBean(BatchRecoverWorker.class);
                    for (int i = 0; i < 3; i++) assertThat(worker.runOnce()).isEqualTo(RecoveryResult.RECOVERED);
                    assertThat(worker.runOnce()).isEqualTo(RecoveryResult.NO_WORK);
                    assertThat(effects).hasSize(93).containsAllEntriesOf(originals);
                    assertThat(storage.findPending("one-shot", 100)).hasSize(7);
                });
    }
}
