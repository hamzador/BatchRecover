package io.github.hamzador.batchrecover.examples;

public class InvalidCustomerException extends RuntimeException {
    public InvalidCustomerException() {
        super("Invalid sample customer email");
    }
}
