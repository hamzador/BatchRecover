package io.github.hamzador.batchrecover.examples;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerPayloadCodecTest {
    @Test
    void shouldRoundTripExplicitCustomerDto() {
        var codec = new CustomerPayloadCodec();
        var customer = new Customer(42, "test@example.org");
        assertEquals(customer, codec.decode(codec.encode(customer), codec.schemaVersion()));
        assertEquals(codec.encode(customer), codec.encode(customer));
    }
}
