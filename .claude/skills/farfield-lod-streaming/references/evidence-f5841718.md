    private final Long2LongOpenHashMap askedRegions = new Long2LongOpenHashMap();
...
            long before = remote.put(key, index.versions()[i]);
...
            knownRegions.add(region);
...
            remote.remove(answer.key());
// ---------------------------------------------------------------- receiving (client thread)
    /** A message from the server. */
    public static void receive(byte[] message) {