  p = '<repo>/core/src/main/java/io/lakio/farfield/core/stream/SurfaceIndex.java' ... public static List<Long> hidden(long[] keys, long[] versions, Sections sections, MaterialClassifier classes,
                                    IntPredicate seeThrough, Map<Long, long[]> kept) throws IOException {
  ... private final Map<LodWorld, Map<Long, long[]>> regionFloors = new ConcurrentHashMap<>();
  ... if (kept.size() > 1 << 18) {
            kept.clear(); // a long flight: read again on demand
  ... assertTrue(reads[0] > 0 && reads[0] <= 3, "the changed column only: " + reads[0] + " of " + firstReads);