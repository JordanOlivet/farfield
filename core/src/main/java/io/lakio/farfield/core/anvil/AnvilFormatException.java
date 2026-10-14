package io.lakio.farfield.core.anvil;

/**
 * Corrupt or unsupported data in a region file or a compressed chunk stream.
 */
public final class AnvilFormatException extends RuntimeException {

    public AnvilFormatException(String message) {
        super(message);
    }

    public AnvilFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
