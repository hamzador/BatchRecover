package io.github.hamzador.batchrecover.examples;

import io.github.hamzador.batchrecover.core.domain.RecoveryContext;
import io.github.hamzador.batchrecover.core.exception.RecoveryValidationException;
import io.github.hamzador.batchrecover.core.port.RecoveryHandler;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Example same-transaction business effect with a stable business uniqueness constraint. */
public final class CustomerRecoveryHandler implements RecoveryHandler<Customer> {
    private final JdbcTemplate jdbc;

    public CustomerRecoveryHandler(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void validate(Customer item, RecoveryContext context) {
        if (!context.identity().sourceOccurrenceId().equals("synthetic-v1:" + item.sourceRow())
                || item.email() == null || !item.email().contains("@")) {
            throw new RecoveryValidationException();
        }
    }

    @Override
    public void replay(Customer item, RecoveryContext context) {
        jdbc.update("""
            INSERT INTO demo_customer(source_row,email) VALUES (?,?)
            ON CONFLICT(source_row) DO NOTHING
            """, item.sourceRow(), item.email());
        String stored = jdbc.queryForObject("SELECT email FROM demo_customer WHERE source_row=?",
                String.class, item.sourceRow());
        if (!item.email().equals(stored)) {
            // Do not overwrite an unrelated successful record.
            throw new RecoveryValidationException();
        }
    }
}
