package com.nxdump.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Extracts ExeFS / RomFS, optionally with an update merged on top. */
public final class Dumper {
    private Dumper() {}

    public interface Sink { OutputStream open(String relativePath) throws IOException; }

    public interface Progress {
        void update(String file, long done, long total);
        boolean cancelled();
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
    private static Nca resolveProgram(Container c, Keys keys, long appId, boolean patch) {
        List<Nca> ncas = c.openNcas(keys, null);
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

    /** update may be null, or be the same Container as base (bundle NSP). */
    public static void dump(Container base, Container update, long appId, boolean exefs, boolean romfs,
                            Keys keys, Sink sink, Progress prog) throws IOException {
        base.importTickets(keys);
        if (update != null) update.importTickets(keys);
        Nca baseProg = resolveProgram(base, keys, appId, false);
        if (baseProg == null) throw new NxException("Base game program data not found in the selected file");
        Nca updProg = null;
        if (update != null) {
            updProg = resolveProgram(update, keys, appId, true);
            if (updProg == null) throw new NxException("The selected update does not belong to this game");
        }

        List<Job> jobs = new ArrayList<>();
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
        if (jobs.isEmpty()) throw new NxException("Nothing to extract");

        long total = 0;
        for (Job j : jobs) total += j.size;
        long done = 0;
        byte[] buf = new byte[1 << 20];
        for (Job j : jobs) {
            prog.update(j.path, done, total);
            try (OutputStream out = sink.open(j.path)) {
                long p = 0;
                while (p < j.size) {
                    if (prog.cancelled()) throw new InterruptedIOException("Cancelled");
                    int n = (int) Math.min(buf.length, j.size - p);
                    j.src.read(j.off + p, buf, 0, n);
                    out.write(buf, 0, n);
                    p += n; done += n;
                    prog.update(j.path, done, total);
                }
            }
        }
        prog.update("", total, total);
    }
}
