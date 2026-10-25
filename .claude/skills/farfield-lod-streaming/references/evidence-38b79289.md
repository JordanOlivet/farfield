/** Raised when the messages change shape; peers with another version do not stream (2: preview tiles). */
    public static final int VERSION = 2;
    public static final int TILE_FETCH = 4;
    public static final int TILE = 16;
    public static final int TILE_ABSENT = 17;
    public static final int TILE_UNCHANGED = 18;
    /** A tile near the player was built or rebuilt: ask for it again. */
    public static final int TILE_CHANGED = 19;