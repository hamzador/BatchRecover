package io.github.hamzador.batchrecover.storage.memory;

import io.github.hamzador.batchrecover.core.contract.RecoveryStorageContract;
import io.github.hamzador.batchrecover.core.port.RecoveryStorage;
import io.github.hamzador.batchrecover.core.port.StorageCapabilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryStorageContractTest extends RecoveryStorageContract {
    @Override protected RecoveryStorage createStorage() {
        return new InMemoryRecoveryStorage(clock(), MemoryStorageLimits.DEFAULT);
    }

    @Test
    void shouldAdvertiseNonTransactionalVolatileCapabilities() {
        assertEquals(StorageCapabilities.MEMORY, storage.capabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> service.captureInExistingTransaction(request("app", "row:1", "invalid", 3)));
        assertTrue(storage.findPending("app", 10).isEmpty());
    }
}
