package io.github.hamzador.batchrecover.examples;

import org.springframework.batch.infrastructure.item.support.AbstractItemCountingItemStreamItemReader;
import java.util.List;

/** Checkpointed sequential reader for a deterministic immutable synthetic input. */
public final class CustomerReader extends AbstractItemCountingItemStreamItemReader<Customer> {
    private final List<Customer> rows;
    private int index;

    public CustomerReader(List<Customer> rows) {
        this.rows = List.copyOf(rows);
        setName("syntheticCustomerReader");
    }

    @Override protected Customer doRead() {
        return index < rows.size() ? rows.get(index++) : null;
    }
    @Override protected void doOpen() { index = 0; }
    @Override protected void doClose() { index = 0; }
    @Override protected void jumpToItem(int itemIndex) { index = itemIndex; }
}
