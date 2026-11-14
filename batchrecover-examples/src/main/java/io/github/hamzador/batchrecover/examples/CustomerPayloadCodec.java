package io.github.hamzador.batchrecover.examples;

import io.github.hamzador.batchrecover.core.codec.VersionedPayloadCodec;
import io.github.hamzador.batchrecover.core.port.PayloadCodec;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Explicit DTO-only JSON encoding. No default typing or arbitrary class names. */
public final class CustomerPayloadCodec implements PayloadCodec<Customer> {
    private final VersionedPayloadCodec<Customer> delegate;

    public CustomerPayloadCodec() {
        var mapper = JsonMapper.builder().build();
        delegate = new VersionedPayloadCodec<>("customer", 1, mapper::writeValueAsString,
                Map.of(1, json -> mapper.readValue(json, Customer.class)));
    }

    @Override public String typeId() { return delegate.typeId(); }
    @Override public int schemaVersion() { return delegate.schemaVersion(); }
    @Override public String encode(Customer item) { return delegate.encode(item); }
    @Override public Customer decode(String payload, int version) { return delegate.decode(payload, version); }
}
