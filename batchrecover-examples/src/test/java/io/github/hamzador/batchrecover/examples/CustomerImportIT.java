package io.github.hamzador.batchrecover.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CustomerImportIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private CustomerImportExample demo;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        CustomerImportExample.initialize(source);
        demo = new CustomerImportExample(source);
    }

    @Test
    void shouldImportNinetyCustomersAndCaptureTenProcessorSkips() throws Exception {
        var execution = demo.run(demo.job(CustomerImportExample.sampleRows(), CustomerImportExample::validate,
                demo.writer(), null, true, 10), "sample");
        var step = execution.getStepExecutions().iterator().next();
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(100, step.getReadCount());
        assertEquals(90, step.getWriteCount());
        assertEquals(10, step.getProcessSkipCount());
        assertEquals(90, count("demo_customer"));
        assertEquals(10, count("br_rejection"));
        assertEquals(10, count("br_payload_revision"));
        assertEquals(10, count("br_audit_event"));
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM br_rejection WHERE status='PENDING' AND job_instance_id=? AND job_execution_id=? AND step_execution_id=?",
                Integer.class, execution.getJobInstanceId(), execution.getId(), step.getId()));
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM br_rejection WHERE error_code='INVALID_EMAIL' AND phase='PROCESS'", Integer.class));
    }

    @Test
    void shouldRollbackRejectedAndWrittenRecordsOnFatalWriterFailureThenRestart() throws Exception {
        var rows = CustomerImportExample.sampleRows().subList(0, 10);
        var failure = demo.run(demo.job(rows, CustomerImportExample::validate, chunk -> {
            demo.writer().write(chunk);
            throw new IllegalStateException("fatal writer");
        }, null, true, 10), "restart");
        assertEquals(BatchStatus.FAILED, failure.getStatus());
        assertEquals(0, count("demo_customer"));
        assertEquals(0, count("br_rejection"));
        assertEquals(0, count("br_payload_revision"));
        assertEquals(0, count("br_audit_event"));

        var restarted = demo.run(demo.job(rows, CustomerImportExample::validate,
                demo.writer(), null, true, 10), "restart");
        assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
        assertEquals(failure.getJobInstanceId(), restarted.getJobInstanceId());
        assertNotEquals(failure.getId(), restarted.getId());
        assertEquals(9, count("demo_customer"));
        assertEquals(1, count("br_rejection"));
    }

    @Test
    void shouldNotCaptureFilteredOrSuccessfullyRetriedRecords() throws Exception {
        var attempts = new AtomicInteger();
        var rows = List.of(new Customer(1, "one@example.org"), new Customer(2, "two@example.org"));
        var execution = demo.run(demo.job(rows, customer -> {
            if (customer.sourceRow() == 2) return null;
            if (attempts.getAndIncrement() == 0) throw new TransientFailure();
            return customer;
        }, demo.writer(), TransientFailure.class, true, 10), "retry");
        var step = execution.getStepExecutions().iterator().next();
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(2, attempts.get());
        assertEquals(1, step.getFilterCount());
        assertEquals(1, count("demo_customer"));
        assertEquals(0, count("br_rejection"));
    }

    @Test
    void shouldFailStepWhenCaptureStorageIsUnavailable() throws Exception {
        jdbc.execute("ALTER TABLE br_audit_event ADD CONSTRAINT reject_capture CHECK (action <> 'CAPTURED')");
        var execution = demo.run(demo.job(CustomerImportExample.sampleRows().subList(0, 10),
                CustomerImportExample::validate, demo.writer(), null, true, 10), "storage-failure");
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        assertEquals(0, count("demo_customer"));
        assertEquals(0, count("br_rejection"));
        assertEquals(0, count("br_payload_revision"));
    }

    @Test
    void shouldLeaveOriginalSkipBehaviorUnchangedWhenListenerNotRegistered() throws Exception {
        var execution = demo.run(demo.job(CustomerImportExample.sampleRows(), CustomerImportExample::validate,
                demo.writer(), null, false, 10), "disabled");
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(10, execution.getStepExecutions().iterator().next().getProcessSkipCount());
        assertEquals(90, count("demo_customer"));
        assertEquals(0, count("br_rejection"));
    }

    @Test
    void shouldPreserveCommittedSkipsWhenLaterChunkFailsAndJobRestarts() throws Exception {
        var rows = CustomerImportExample.sampleRows().subList(0, 20);
        var execution = demo.run(demo.job(rows, CustomerImportExample::validate, chunk -> {
            demo.writer().write(chunk);
            if (chunk.getItems().stream().anyMatch(c -> c.sourceRow() > 10)) {
                throw new IllegalStateException("fatal second chunk");
            }
        }, null, true, 10), "checkpoint");
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        assertEquals(9, count("demo_customer"));
        assertEquals(1, count("br_rejection"));
        var restarted = demo.run(demo.job(rows, CustomerImportExample::validate,
                demo.writer(), null, true, 10), "checkpoint");
        assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
        assertEquals(execution.getJobInstanceId(), restarted.getJobInstanceId());
        assertEquals(18, count("demo_customer"));
        assertEquals(2, count("br_rejection"));
        assertEquals(2, count("br_payload_revision"));
        assertEquals(2, count("br_audit_event"));
    }

    @Test
    void shouldRecoverThreeRejectedCustomersWithoutReprocessingSuccessfulCustomers() throws Exception {
        var execution = demo.run(demo.job(CustomerImportExample.sampleRows(), CustomerImportExample::validate,
                demo.writer(), null, true, 10), "recovery");
        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        var originalCustomers = jdbc.queryForList("SELECT source_row,email FROM demo_customer ORDER BY source_row");
        jdbc.execute("""
                CREATE FUNCTION reject_original_mutation() RETURNS trigger LANGUAGE plpgsql AS $
                BEGIN RAISE EXCEPTION 'original successful record mutated'; END;
                $
                """);
        jdbc.execute("""
                CREATE TRIGGER guard_original_records BEFORE UPDATE OR DELETE ON demo_customer
                FOR EACH ROW EXECUTE FUNCTION reject_original_mutation()
                """);
        assertEquals(3, demo.recoverThreeCustomers());
        assertEquals(93, count("demo_customer"));
        assertEquals(originalCustomers, jdbc.queryForList(
                "SELECT source_row,email FROM demo_customer WHERE source_row % 10 <> 0 ORDER BY source_row"));
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM br_rejection WHERE status='PENDING'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM br_rejection WHERE status='RECOVERED'", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM br_recovery_attempt WHERE outcome='RECOVERED'", Integer.class));
        assertEquals(13, count("br_payload_revision"));
        assertEquals(22, count("br_audit_event"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Integer.class));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static class TransientFailure extends RuntimeException {}
}
