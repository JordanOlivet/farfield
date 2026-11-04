            (java.util.concurrent.ThreadPoolExecutor) Executors.newFixedThreadPool(2, r -> {
     * Stops building and waits for the running tiles, before the stores
     * close. The threads are not interrupted: one interrupted inside a read
     * of a mod jar (a structure template) closes that jar's channel for the
     * rest of the session (ClosedByInterruptException, seen on Cobblemon's
     * fossils at every stop). Running tiles end at their next stop check.
     */
    void stop() {
        stopped = true;
        pool.getQueue().clear();
        pool.shutdown();