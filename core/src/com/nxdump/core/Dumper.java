package com.nxdump.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Extracts ExeFS / RomFS, optionally with an update merged on top. */
public final class Dumper {
    private Dumper() {}

    public interface Sink {
        OutputStream open(String relativePath) throws IOException;

        /** Creates an empty file. Sinks where opening a stream is expensive (SAF) can skip the stream. */
        default void createEmpty(String relativePath) throws IOException { open(relativePath).close(); }

        /**
         * Called once with every file path before any copying starts, so a sink can create its directory tree
         * up front instead of under contention. May report through prog.stage() from several threads.
         */
        default void prepare(List<String> relativePaths, Progress prog) throws IOException { }
    }

    public interface Progress {
        void update(String file, long done, long total);
        boolean cancelled();

        /**
         * A setup phase before/while copying, e.g. "Reading game data" or "Creating folders". total == 0 means
         * unknown (indeterminate). An empty name means copying has started.
         */
        default void stage(String name, long done, long total) { }

        /** Number of files finished so far (called from worker threads, serialised). */
        default void files(long done, long total) { }
    }

    private static final class Job {
        final String path; final Source src; final long off, size;
        Job(String path, Source src, long off, long size) { this.path = path; this.src = src; this.off = off; this.size = size; }
    }

    /** Header-based lookup (fallback). */
    private static Nca findProgram(List<Nca> ncas, long appId, boolean patch) {
        Nca best = null;
        for (Nca n : ncas) {
            if (n.contentType != Nca.TYPE_PROGRAM || Analyzer.appIdOf(n.titleId) != appId) continue;
            int low = (int) (n.titleId & 0x1FFF);
            boolean isPatch = low >= 0x800 && low < 0x1000;
            if (isPatch != patch) continue;
            if (best == null || n.titleId < best.titleId) best = n;
        }
        return best;
    }

    /** Finds the program NCA via the CNMT content list; falls back to NCA header heuristics. */
    private static Nca resolveProgram(Container c, Keys keys, long appId, boolean patch, List<String> errs) {
        List<Nca> ncas = c.openNcas(keys, errs);
        int kind = patch ? Analyzer.UPDATE : Analyzer.BASE;
        for (Analyzer.Info i : Analyzer.analyze(c, keys))
            if (i.kind == kind && i.appId == appId && i.programNca != null)
                for (Nca n : ncas) if (n.name.equalsIgnoreCase(i.programNca)) return n;
        Nca n = findProgram(ncas, appId, patch);
        if (n != null) return n;
        Nca any = null;
        for (Nca x : ncas) {
            if (x.contentType != Nca.TYPE_PROGRAM) continue;
            if (patch ? x.isPatchRomFs() : !x.isPatchRomFs() && Analyzer.appIdOf(x.titleId) == appId) { any = x; break; }
        }
        return any;
    }

    /** A DLC (add-on content) title to extract, found in {@code container}. */
    public static final class Dlc {
        public final Container container;
        public final long titleId;
        public Dlc(Container container, long titleId) { this.container = container; this.titleId = titleId; }
    }

    /** Finds the Data NCAs of a DLC title via its CNMT; falls back to NCA header matching. */
    private static List<Nca> resolveDlc(Container c, Keys keys, long titleId, List<String> errs) {
        List<Nca> ncas = c.openNcas(keys, errs);
        List<Nca> out = new ArrayList<>();
        Analyzer.Info best = null;
        for (Analyzer.Info i : Analyzer.analyze(c, keys))
            if (i.kind == Analyzer.DLC && i.error == null && i.titleId == titleId && (best == null || i.version > best.version)) best = i;
        if (best != null)
            for (String id : best.dataNcas)
                for (Nca n : ncas) if (n.name.equalsIgnoreCase(id)) { out.add(n); break; }
        if (out.isEmpty())
            for (Nca n : ncas) if (n.contentType == Nca.TYPE_DATA && n.titleId == titleId) out.add(n);
        return out;
    }

    /** Adds one job per file of every Data NCA of the DLC, under dlc/<TITLEID>/romfs[_n]/. */
    private static void addDlcJobs(Dlc d, Keys keys, List<Job> jobs) throws IOException {
        d.container.importTickets(keys);
        List<String> errs = new ArrayList<>();
        List<Nca> ncas = resolveDlc(d.container, keys, d.titleId, errs);
        String tid = String.format("%016X", d.titleId);
        if (ncas.isEmpty())
            throw new NxException("DLC " + tid + " not found in \"" + d.container.fileName + "\"" + (errs.isEmpty() ? "" : " (" + errs.get(0) + ")"));
        int idx = 0;
        for (Nca n : ncas) {
            idx++;
            String suffix = idx > 1 ? "_" + idx : "";
            Nca.Section rs = n.section(Nca.FS_ROMFS);
            if (rs != null) {
                Source img = n.openData(rs);
                for (RomFs.File f : RomFs.list(img)) jobs.add(new Job("dlc/" + tid + "/romfs" + suffix + "/" + f.path, img, f.offset, f.size));
                continue;
            }
            Nca.Section ps = n.section(Nca.FS_PFS);
            if (ps == null) throw new NxException("DLC " + tid + ": " + n.name + " has no readable data section");
            Source img = n.openData(ps);
            for (Pfs.Entry e : Pfs.parse(img)) jobs.add(new Job("dlc/" + tid + "/pfs" + suffix + "/" + e.name, img, e.offset, e.size));
        }
    }

    /** update may be null, or be the same Container as base (bundle NSP). */
    public static void dump(Container base, Container update, long appId, boolean exefs, boolean romfs,
                            Keys keys, Sink sink, Progress prog) throws IOException {
        dump(base, update, appId, exefs, romfs, null, keys, sink, prog, 1);
    }

    public static void dump(Container base, Container update, long appId, boolean exefs, boolean romfs,
                            List<Dlc> dlc, Keys keys, Sink sink, Progress prog) throws IOException {
        dump(base, update, appId, exefs, romfs, dlc, keys, sink, prog, 1);
    }

    /**
     * Extracts any mix of ExeFS / RomFS (base, optionally patched by update) and DLC titles.
     * base may be null when only DLC is requested (exefs and romfs both false).
     * <p>threads = number of files copied in parallel (1 = sequential). With jobs &gt; 1 big files also get a
     * read-ahead pipeline (decrypt on one thread while the previous chunk is being written on another).
     * Sink.open() may be called from several threads at once and must be thread-safe when threads &gt; 1.
     */
    public static void dump(Container base, Container update, long appId, boolean exefs, boolean romfs,
                            List<Dlc> dlc, Keys keys, Sink sink, Progress prog, int threads) throws IOException {
        prog.stage("Reading game data", 0, 0);
        // Tickets first, sequentially: cheap, and every task below may rely on any container's titlekeys.
        if (base != null) base.importTickets(keys);
        if (update != null) update.importTickets(keys);
        if (dlc != null) for (Dlc d : dlc) d.container.importTickets(keys);

        // The game and each DLC are independent and mostly wait on reads, so build their file lists side by side.
        List<Callable<List<Job>>> tasks = new ArrayList<>();
        if (exefs || romfs) tasks.add(() -> {
            List<Job> l = new ArrayList<>();
            addGameJobs(base, update, appId, exefs, romfs, keys, l);
            return l;
        });
        if (dlc != null) for (Dlc d : dlc) tasks.add(() -> {
            List<Job> l = new ArrayList<>();
            addDlcJobs(d, keys, l);
            return l;
        });
        List<Job> jobs = new ArrayList<>();
        if (threads > 1 && tasks.size() > 1) {
            ExecutorService ex = Executors.newFixedThreadPool(Math.min(tasks.size(), 6), daemons("nxdump-scan"));
            try {
                List<Future<List<Job>>> fs = new ArrayList<>();
                for (Callable<List<Job>> t : tasks) fs.add(ex.submit(t));
                for (Future<List<Job>> f : fs) jobs.addAll(f.get());       // task order = old order; first failure wins
            } catch (ExecutionException e) {
                rethrow(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            } finally {
                ex.shutdownNow();
            }
        } else {
            for (Callable<List<Job>> t : tasks) {
                try { jobs.addAll(t.call()); } catch (IOException | RuntimeException e) { throw e; } catch (Exception e) { throw new IOException(e); }
            }
        }
        copy(jobs, sink, prog, threads);
    }

    private static void addGameJobs(Container base, Container update, long appId, boolean exefs, boolean romfs,
                                    Keys keys, List<Job> jobs) throws IOException {
        if (base == null) throw new NxException("Base game file is required to extract ExeFS / RomFS");
        base.importTickets(keys);
        List<String> errs = new ArrayList<>();
        if (update != null) update.importTickets(keys);
        Nca baseProg = resolveProgram(base, keys, appId, false, errs);
        if (baseProg == null) throw new NxException("Base game program data not found in the selected file");
        Nca updProg = null;
        if (update != null) {
            updProg = resolveProgram(update, keys, appId, true, errs);
            if (updProg == null) throw new NxException("Could not find this game's program data in the update file \"" + update.fileName + "\"" + (errs.isEmpty() ? "" : " (" + errs.get(0) + ")") + ". Check the update is for this exact game and your keys are new enough.");
        }

        if (exefs) {
            Nca n = updProg != null ? updProg : baseProg;
            Nca.Section s = n.section(Nca.FS_PFS);
            if (s == null) throw new NxException("ExeFS section not found");
            Source d = n.openData(s);
            for (Pfs.Entry e : Pfs.parse(d)) jobs.add(new Job("exefs/" + e.name, d, e.offset, e.size));
        }
        if (romfs) {
            Source img;
            Nca.Section bs = baseProg.section(Nca.FS_ROMFS);
            Nca.Section us = updProg != null ? updProg.section(Nca.FS_ROMFS) : null;
            if (us != null && us.encType == Nca.ENC_CTR_EX) {
                if (bs == null) throw new NxException("Base game has no RomFS to patch");
                img = updProg.openPatchedRomFs(us, baseProg.openSection(bs));
            } else if (us != null) {
                img = updProg.openData(us);
            } else if (bs != null) {
                img = baseProg.openData(bs);
            } else {
                throw new NxException("RomFS section not found");
            }
            for (RomFs.File f : RomFs.list(img)) jobs.add(new Job("romfs/" + f.path, img, f.offset, f.size));
        }
    }

    // ---- copying ------------------------------------------------------------------------------

    private static final int CHUNK = 1 << 20;
    /** With threads > 1, files at least this big get a read-ahead pipeline. */
    private static final int PIPE_MIN = 4 * CHUNK;
    private static final int PIPE_DEPTH = 4;
    private static final int MAX_THREADS = 16;
    /** Files below this size go to the "small" lane: their cost is per-file latency (create/open), not bytes. */
    private static final long SMALL_MAX = 256 * 1024;
    private static final int SMALL_MAX_THREADS = 16;

    private static final class Chunk {
        final byte[] buf; final int len;
        Chunk(byte[] buf, int len) { this.buf = buf; this.len = len; }
    }
    private static final Chunk END = new Chunk(null, 0);

    /** Per-worker buffer set for the read-ahead pipeline. */
    private static final class Pipe {
        final byte[][] bufs = new byte[PIPE_DEPTH][CHUNK];
        final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(PIPE_DEPTH);
    }

    private static ThreadFactory daemons(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> { Thread t = new Thread(r, name + "-" + n.incrementAndGet()); t.setDaemon(true); return t; };
    }

    private static void rethrow(Throwable t) throws IOException {
        if (t instanceof IOException) throw (IOException) t;
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        throw new IOException(t);
    }

    private static void copy(List<Job> jobs, Sink sink, Progress prog, int threads) throws IOException {
        if (jobs.isEmpty()) throw new NxException("Nothing to extract");
        long total = 0;
        List<String> paths = new ArrayList<>(jobs.size());
        for (Job j : jobs) { total += j.size; paths.add(j.path); }
        sink.prepare(paths, prog);
        prog.files(0, jobs.size());
        prog.stage("", 0, 0);
        threads = Math.max(1, Math.min(threads, MAX_THREADS));
        boolean parallel = threads > 1;
        if (!parallel) {
            Copier c = new Copier(sink, prog, total, null);
            c.filesTotal = jobs.size();
            c.runWorker(new Lane(jobs), CHUNK);
            finish(c, total, prog);
            return;
        }
        // Two lanes: big files (biggest first, so the tail isn't one giant file) use `threads` workers with
        // the read-ahead pipeline; small files are latency-bound so they get their own, wider set of workers
        // that run alongside.
        List<Job> big = new ArrayList<>(), small = new ArrayList<>();
        for (Job j : jobs) (j.size < SMALL_MAX ? small : big).add(j);
        big.sort((x, y) -> Long.compare(y.size, x.size));
        ExecutorService readers = Executors.newCachedThreadPool(daemons("nxdump-read"));
        Copier c = new Copier(sink, prog, total, readers);
        c.filesTotal = jobs.size();
        int bigWorkers = Math.min(threads, big.size());
        int smallWorkers = Math.min(small.size(), Math.min(SMALL_MAX_THREADS, Math.max(8, threads * 3)));
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, bigWorkers + smallWorkers), daemons("nxdump-copy"));
        try {
            Lane bigLane = new Lane(big), smallLane = new Lane(small);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < bigWorkers; i++) fs.add(pool.submit(() -> c.runWorker(bigLane, CHUNK)));
            for (int i = 0; i < smallWorkers; i++) fs.add(pool.submit(() -> c.runWorker(smallLane, (int) SMALL_MAX)));
            for (Future<?> f : fs) f.get();
        } catch (ExecutionException e) {
            c.fail(e.getCause());
        } catch (InterruptedException e) {
            c.fail(new InterruptedIOException("Interrupted"));
            Thread.currentThread().interrupt();
        } finally {
            c.stop = true;
            pool.shutdownNow();
            readers.shutdownNow();
        }
        finish(c, total, prog);
    }

    private static void finish(Copier c, long total, Progress prog) throws IOException {
        Throwable t = c.failure.get();
        if (t != null) rethrow(t);
        prog.update("", total, total);
    }

    /** A queue of jobs that several workers pull from. */
    private static final class Lane {
        final List<Job> jobs; final AtomicInteger next = new AtomicInteger();
        Lane(List<Job> jobs) { this.jobs = jobs; }
    }

    private static final class Copier {
        final Sink sink; final Progress prog; final long total;
        final ExecutorService readers;                 // null = no read-ahead pipelines
        final AtomicLong done = new AtomicLong();
        final AtomicInteger filesDone = new AtomicInteger();
        int filesTotal;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Object progLock = new Object();
        volatile boolean stop;

        Copier(Sink sink, Progress prog, long total, ExecutorService readers) {
            this.sink = sink; this.prog = prog; this.total = total; this.readers = readers;
        }

        void fileDone() {
            int n = filesDone.incrementAndGet();
            synchronized (progLock) { prog.files(n, filesTotal); }
        }

        void fail(Throwable t) { failure.compareAndSet(null, t); stop = true; }

        /** Progress callbacks are serialised and always see a non-decreasing byte count. */
        void report(String file, long delta) {
            if (delta != 0) done.addAndGet(delta);
            synchronized (progLock) { prog.update(file, done.get(), total); }
        }

        void runWorker(Lane lane, int bufSize) {
            byte[] buf = new byte[bufSize];
            Pipe pipe = readers != null && bufSize == CHUNK ? new Pipe() : null;
            try {
                for (;;) {
                    if (stop) return;
                    int i = lane.next.getAndIncrement();
                    if (i >= lane.jobs.size()) return;
                    Job j = lane.jobs.get(i);
                    if (prog.cancelled()) throw new InterruptedIOException("Cancelled");
                    if (j.size == 0) { report(j.path, 0); sink.createEmpty(j.path); fileDone(); continue; }
                    if (pipe != null && j.size >= PIPE_MIN) copyPipelined(j, pipe); else copyPlain(j, buf);
                    if (!stop) fileDone();
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        void copyPlain(Job j, byte[] buf) throws IOException {
            report(j.path, 0);
            try (OutputStream out = sink.open(j.path)) {
                long p = 0;
                while (p < j.size) {
                    if (stop) return;
                    if (prog.cancelled()) throw new InterruptedIOException("Cancelled");
                    int n = (int) Math.min(buf.length, j.size - p);
                    j.src.read(j.off + p, buf, 0, n);
                    out.write(buf, 0, n);
                    p += n;
                    report(j.path, n);
                }
            }
        }

        /** A reader thread decrypts ahead into a small ring of buffers while this thread writes them out. */
        void copyPipelined(Job j, Pipe pipe) throws IOException {
            report(j.path, 0);
            pipe.free.clear();
            for (byte[] b : pipe.bufs) pipe.free.add(b);
            final BlockingQueue<Chunk> full = new ArrayBlockingQueue<>(PIPE_DEPTH + 1);   // chunks + END never block
            final AtomicReference<Throwable> readErr = new AtomicReference<>();
            final AtomicBoolean abort = new AtomicBoolean();
            Future<?> reader = readers.submit(() -> {
                try {
                    long p = 0;
                    while (p < j.size) {
                        byte[] b = null;
                        while (b == null) {
                            if (abort.get() || stop) return;
                            b = pipe.free.poll(50, TimeUnit.MILLISECONDS);
                        }
                        int n = (int) Math.min(b.length, j.size - p);
                        j.src.read(j.off + p, b, 0, n);
                        full.add(new Chunk(b, n));
                        p += n;
                    }
                } catch (Throwable t) {
                    readErr.set(t);
                } finally {
                    full.add(END);
                }
            });
            try (OutputStream out = sink.open(j.path)) {
                for (;;) {
                    Chunk ch = full.take();
                    if (ch == END) break;
                    if (stop) return;
                    if (prog.cancelled()) throw new InterruptedIOException("Cancelled");
                    out.write(ch.buf, 0, ch.len);
                    pipe.free.add(ch.buf);
                    report(j.path, ch.len);
                }
                Throwable t = readErr.get();
                if (t != null) rethrow(t);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            } finally {
                abort.set(true);                       // unblock the reader, then wait until it has let go of the buffers
                try { reader.get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch (ExecutionException ignored) { }
            }
        }
    }
}
