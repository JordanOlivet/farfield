package io.lakio.farfield.core.nbt;

/**
 * Malformed or truncated NBT data.
 */
public final class NbtFormatException extends RuntimeException {

    public NbtFormatException(String message) {
        super(message);
    }
}
