package io.lakio.farfield.core.nbt;

/**
 * NBT tag type ids, as written in the binary format.
 */
public final class NbtTag {

    public static final int END = 0;
    public static final int BYTE = 1;
    public static final int SHORT = 2;
    public static final int INT = 3;
    public static final int LONG = 4;
    public static final int FLOAT = 5;
    public static final int DOUBLE = 6;
    public static final int BYTE_ARRAY = 7;
    public static final int STRING = 8;
    public static final int LIST = 9;
    public static final int COMPOUND = 10;
    public static final int INT_ARRAY = 11;
    public static final int LONG_ARRAY = 12;

    private NbtTag() {
    }

    /** Payload size in bytes of a fixed-size tag type, or -1 for variable-size types. */
    public static int fixedSize(int type) {
        return switch (type) {
            case END -> 0;
            case BYTE -> 1;
            case SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
            default -> -1;
        };
    }
}
