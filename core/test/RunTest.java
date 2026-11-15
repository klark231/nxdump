import com.nxdump.core.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

public class RunTest {
    static Container open(String p) throws IOException {
        FileChannel ch = FileChannel.open(Paths.get(p));
        return Container.open(new Source.OfChannel(ch, null), Paths.get(p).getFileName().toString(), ch);
    }
    static Dumper.Sink sink(Path root) {
        return rel -> { Path f = root.resolve(rel); Files.createDirectories(f.getParent()); return Files.newOutputStream(f); };
    }
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        int jobs = a.length > 1 ? Integer.parseInt(a[1]) : 1;
        System.out.println("jobs=" + jobs);
        Keys keys = Keys.load(new FileInputStream(dir + "/prod.keys"), null);
        System.out.println("keys=" + keys.count() + " master=" + keys.maxMasterKey() + " header=" + keys.hasHeaderKey());
        for (String f : new String[]{"base.nsp", "update.nsp", "game.xci", "dlc.nsp"}) {
            try (Container c = open(dir + "/" + f)) {
                for (Analyzer.Info i : Analyzer.analyze(c, keys))
                    System.out.printf("%s: kind=%d tid=%016x app=%016x ver=%d name='%s' dispVer=%s icon=%s err=%s%n",
                            f, i.kind, i.titleId, i.appId, i.version, i.name, i.displayVersion, i.icon == null ? "-" : i.icon.length, i.error);
            }
        }
        Dumper.Progress pr = new Dumper.Progress() {
            long last; public void update(String f, long d, long t) { last = d; }
            public boolean cancelled() { return false; } };
        long app = 0x0100ABCD00012000L;
        try (Container b = open(dir + "/base.nsp")) {
            Dumper.dump(b, null, app, true, true, null, keys, sink(Paths.get(dir, "out_base")), pr, jobs);
        }
        try (Container b = open(dir + "/base.nsp"); Container u = open(dir + "/update.nsp")) {
            Dumper.dump(b, u, app, true, true, null, keys, sink(Paths.get(dir, "out_merged")), pr, jobs);
        }
        try (Container x = open(dir + "/game.xci")) {
            Dumper.dump(x, null, app, true, true, null, keys, sink(Paths.get(dir, "out_xci")), pr, jobs);
        }
        long dlc1 = app + 0x1001, dlc2 = app + 0x1002;
        try (Container d = open(dir + "/dlc.nsp")) {   // DLC only, no base game needed
            Dumper.dump(null, null, app, false, false,
                    List.of(new Dumper.Dlc(d, dlc1), new Dumper.Dlc(d, dlc2)), keys, sink(Paths.get(dir, "out_dlc")), pr, jobs);
        }
        try (Container b = open(dir + "/base.nsp"); Container u = open(dir + "/update.nsp"); Container d = open(dir + "/dlc.nsp")) {
            Dumper.dump(b, u, app, true, true, List.of(new Dumper.Dlc(d, dlc1), new Dumper.Dlc(d, dlc2)),
                    keys, sink(Paths.get(dir, "out_all")), pr, jobs);
        }
        if (jobs > 1) failureTests(dir, keys, app, jobs);
        System.out.println("done");
    }

    /** Errors and cancellation must propagate out of the worker threads and must never hang. */
    static void failureTests(String dir, Keys keys, long app, int jobs) throws Exception {
        long dlc1 = app + 0x1001;
        Dumper.Progress quiet = new Dumper.Progress() {
            public void update(String f, long d, long t) { }
            public boolean cancelled() { return false; } };
        // 1) a write error on a small file in the middle of a parallel run
        expectFail("write error (plain file)", IOException.class, "boom", () -> {
            try (Container b = open(dir + "/base.nsp")) {
                Dumper.Sink s = rel -> {
                    if (rel.endsWith("big2.bin")) throw new IOException("boom");
                    return OutputStream.nullOutputStream();
                };
                Dumper.dump(b, null, app, true, true, null, keys, s, quiet, jobs);
            }
        });
        // 2) a write error on the >4 MiB file that goes through the read-ahead pipeline
        expectFail("write error (pipelined file)", IOException.class, "pipe-boom", () -> {
            try (Container d = open(dir + "/dlc.nsp")) {
                Dumper.Sink s = rel -> rel.endsWith("pack.bin") ? new OutputStream() {
                    public void write(int b) throws IOException { throw new IOException("pipe-boom"); }
                    public void write(byte[] b, int o, int n) throws IOException { throw new IOException("pipe-boom"); }
                } : OutputStream.nullOutputStream();
                Dumper.dump(null, null, app, false, false, List.of(new Dumper.Dlc(d, dlc1)), keys, s, quiet, jobs);
            }
        });
        // 3) user cancel
        expectFail("cancel", InterruptedIOException.class, "Cancelled", () -> {
            try (Container d = open(dir + "/dlc.nsp")) {
                Dumper.Progress cancelling = new Dumper.Progress() {
                    volatile int calls;
                    public void update(String f, long dn, long t) { calls++; }
                    public boolean cancelled() { return calls > 3; } };
                Dumper.dump(null, null, app, false, false, List.of(new Dumper.Dlc(d, dlc1), new Dumper.Dlc(d, app + 0x1002)),
                        keys, rel -> OutputStream.nullOutputStream(), cancelling, jobs);
            }
        });
    }

    interface Body { void run() throws Exception; }

    static void expectFail(String what, Class<? extends IOException> type, String msg, Body body) throws Exception {
        Throwable[] got = new Throwable[1];
        Thread t = new Thread(() -> { try { body.run(); } catch (Throwable e) { got[0] = e; } });
        t.setDaemon(true); t.start(); t.join(30_000);
        if (t.isAlive()) throw new AssertionError(what + ": HUNG");
        if (got[0] == null) throw new AssertionError(what + ": expected an exception");
        if (!type.isInstance(got[0]) || !String.valueOf(got[0].getMessage()).contains(msg))
            throw new AssertionError(what + ": wrong exception " + got[0]);
        System.out.println("ok: " + what + " -> " + got[0]);
    }
}
