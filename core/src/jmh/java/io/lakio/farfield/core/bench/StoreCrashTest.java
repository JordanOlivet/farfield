package io.lakio.farfield.core.bench;

import io.lakio.farfield.core.storage.LogStore;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.Txn;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kills a writer process in the middle of its writes, then checks the store:
 * every record the writer reported as durable must read back intact, and no
 * record may read back wrong. Run through
 * {@code gradlew :core:storageBench --args="<world> --crash-test N"}.
 *
 * <p>The kill is a process kill (TerminateProcess on Windows): writes already
 * handed to the OS survive it. It does not simulate a power loss.
 */
public final class StoreCrashTest {

    private static final String[] JVM_OPENS = {
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED"};

    static byte[] payload(long seed, long key) {
        SplittableRandom random = new SplittableRandom(seed * 31 + key);
        byte[] b = new byte[random.nextInt(100, 4000)];
        random.nextBytes(b);
        return b;
    }

    /** Child process: writes records 0, 1, 2... forever, reporting each durable point on stdout. */
    public static void main(String[] args) throws Exception {
        String kind = args[0];
        Path dir = Path.of(args[1]);
        long seed = Long.parseLong(args[2]);
        if (kind.equals("logstore")) {
            try (LogStore store = LogStore.open(dir.resolve("crash.lod"))) {
                for (long key = 0; ; key++) {
                    byte[] b = payload(seed, key);
                    store.put(key, b, 0, b.length);
                    if (key % 50 == 49) {
                        store.sync();
                        System.out.println("durable " + (key + 1));
                        System.out.flush();
                    }
                }
            }
        } else {
            try (Env<ByteBuffer> env = Env.create().setMapSize(1L << 30).setMaxDbs(1).open(dir.toFile())) {
                Dbi<ByteBuffer> db = env.openDbi("crash", DbiFlags.MDB_CREATE);
                ByteBuffer k = ByteBuffer.allocateDirect(8).order(ByteOrder.BIG_ENDIAN);
                ByteBuffer v = ByteBuffer.allocateDirect(8192);
                Txn<ByteBuffer> txn = env.txnWrite();
                for (long key = 0; ; key++) {
                    k.clear();
                    k.putLong(key).flip();
                    v.clear();
                    v.put(payload(seed, key)).flip();
                    db.put(txn, k, v);
                    if (key % 50 == 49) {
                        txn.commit();
                        txn.close();
                        System.out.println("durable " + (key + 1));
                        System.out.flush();
                        txn = env.txnWrite();
                    }
                }
            }
        }
    }

    static void runAll(Path root, int runs) throws Exception {
        for (String kind : new String[]{"logstore", "lmdb"}) {
            int ok = 0;
            long minDurable = Long.MAX_VALUE;
            long maxDurable = 0;
            long extra = 0;
            long truncated = 0;
            for (int run = 0; run < runs; run++) {
                Path dir = root.resolve(kind + "-" + run);
                StorageBenchmark.deleteTree(dir);
                Files.createDirectories(dir);
                long seed = 1000 + run;
                long durable = killWriter(kind, dir, seed, 300 + (run * 137) % 1500);
                long[] result = kind.equals("logstore") ? checkLogStore(dir, seed, durable) : checkLmdb(dir, seed, durable);
                ok++;
                minDurable = Math.min(minDurable, durable);
                maxDurable = Math.max(maxDurable, durable);
                extra += result[0];
                truncated += result[1];
                StorageBenchmark.deleteTree(dir);
            }
            System.out.printf(Locale.ROOT, "Crash test %s: %d/%d kills recovered with every durable record intact"
                            + " (durable at kill: %,d to %,d records; %,d non-durable records also survived; %,d torn-tail bytes cut)%n",
                    kind, ok, runs, minDurable, maxDurable, extra, truncated);
        }
    }

    private static long killWriter(String kind, Path dir, long seed, long millis) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(Arrays.asList(JVM_OPENS));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(StoreCrashTest.class.getName());
        command.add(kind);
        command.add(dir.toString());
        command.add(Long.toString(seed));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        AtomicLong durable = new AtomicLong();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("durable ")) {
                        durable.set(Long.parseLong(line.substring(8)));
                    } else {
                        System.out.println("  child: " + line);
                    }
                }
            } catch (Exception e) {
                // The pipe breaks when the child dies.
            }
        });
        reader.start();
        // Wait for the first durable point, then a bit more, then kill mid-write.
        long deadline = System.currentTimeMillis() + 30_000;
        while (durable.get() == 0 && System.currentTimeMillis() < deadline && process.isAlive()) {
            Thread.sleep(10);
        }
        Thread.sleep(millis);
        long reported = durable.get();
        process.destroyForcibly();
        process.waitFor();
        reader.join(2000);
        // A line may have been printed after we sampled: take the final value, it was durable too.
        return Math.max(reported, durable.get());
    }

    /** Returns {non-durable records found, torn-tail bytes cut}. */
    private static long[] checkLogStore(Path dir, long seed, long durable) throws Exception {
        try (LogStore store = LogStore.open(dir.resolve("crash.lod"))) {
            for (long key = 0; key < durable; key++) {
                byte[] b = store.get(key);
                if (b == null || !Arrays.equals(b, payload(seed, key))) {
                    throw new IllegalStateException("LogStore lost or damaged durable record " + key);
                }
            }
            long extra = 0;
            for (Long key : store.keys()) {
                if (key >= durable) {
                    if (!Arrays.equals(store.get(key), payload(seed, key))) {
                        throw new IllegalStateException("LogStore returned a damaged record " + key);
                    }
                    extra++;
                }
            }
            if (store.corruptRecords() != 0) {
                throw new IllegalStateException(store.corruptRecords() + " corrupt records");
            }
            return new long[]{extra, store.truncatedBytes()};
        }
    }

    private static long[] checkLmdb(Path dir, long seed, long durable) throws Exception {
        try (Env<ByteBuffer> env = Env.create().setMapSize(1L << 30).setMaxDbs(1).open(dir.toFile())) {
            Dbi<ByteBuffer> db = env.openDbi("crash", DbiFlags.MDB_CREATE);
            ByteBuffer k = ByteBuffer.allocateDirect(8).order(ByteOrder.BIG_ENDIAN);
            long extra = 0;
            try (Txn<ByteBuffer> txn = env.txnRead()) {
                for (long key = 0; ; key++) {
                    k.clear();
                    k.putLong(key).flip();
                    ByteBuffer v = db.get(txn, k);
                    if (v == null) {
                        if (key < durable) {
                            throw new IllegalStateException("LMDB lost durable record " + key);
                        }
                        break;
                    }
                    byte[] b = new byte[v.remaining()];
                    v.get(b);
                    if (!Arrays.equals(b, payload(seed, key))) {
                        throw new IllegalStateException("LMDB returned a damaged record " + key);
                    }
                    if (key >= durable) {
                        extra++;
                    }
                }
            }
            return new long[]{extra, 0};
        }
    }
}
