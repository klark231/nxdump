package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads CNMT + control data from a container for the game library. */
public final class Analyzer {
    private Analyzer() {}

    public static final int BASE = 0, UPDATE = 1, DLC = 2;

    public static final class Info {
        public int kind;
        public long titleId, appId, version;
        public String name = "", displayVersion = "", error;
        public byte[] icon;
    }

    public static long appIdOf(long titleId) { return titleId & ~0x1FFFL; }

    public static List<Info> analyze(Container c, Keys keys) {
        c.importTickets(keys);
        List<String> errs = new ArrayList<>();
        List<Nca> ncas = c.openNcas(keys, errs);
        List<Info> out = new ArrayList<>();
        for (Nca meta : ncas) {
            if (meta.contentType != Nca.TYPE_META) continue;
            Info info = new Info();
            try {
                Source d = meta.openData(meta.sections[0]);
                byte[] cnmt = null;
                for (Pfs.Entry e : Pfs.parse(d))
                    if (e.name.endsWith(".cnmt")) { cnmt = d.read(e.offset, (int) Math.min(e.size, 0x40)); break; }
                if (cnmt == null) continue;
                info.titleId = Bin.le64(cnmt, 0);
                info.version = Bin.le32(cnmt, 8) & 0xFFFFFFFFL;
                int type = cnmt[0xC] & 0xFF;
                if (type == Meta.TYPE_APPLICATION) info.kind = BASE;
                else if (type == Meta.TYPE_PATCH) info.kind = UPDATE;
                else if (type == Meta.TYPE_ADDON) info.kind = DLC;
                else continue;
                info.appId = appIdOf(info.titleId);
                if (info.kind != DLC) readControl(info, ncas);
            } catch (IOException e) {
                info.error = e.getMessage();
            }
            out.add(info);
        }
        if (out.isEmpty()) {
            Info i = new Info();
            i.error = errs.isEmpty() ? "No game metadata found" : errs.get(0);
            out.add(i);
        }
        return out;
    }

    private static void readControl(Info info, List<Nca> ncas) throws IOException {
        for (Nca n : ncas) {
            if (n.contentType != Nca.TYPE_CONTROL || n.titleId != info.titleId) continue;
            Source img = n.openData(n.sections[0]);
            RomFs.File nacp = null, icon = null;
            for (RomFs.File f : RomFs.list(img)) {
                if (f.path.equals("control.nacp")) nacp = f;
                else if (f.path.equals("icon_AmericanEnglish.dat")) icon = f;
                else if (icon == null && f.path.startsWith("icon_") && f.path.endsWith(".dat")) icon = f;
            }
            if (nacp != null) {
                byte[] b = img.read(nacp.offset, (int) Math.min(nacp.size, 0x4000));
                info.name = Meta.nacpName(b);
                info.displayVersion = Meta.nacpVersion(b);
            }
            if (icon != null && icon.size < (4 << 20)) info.icon = img.read(icon.offset, (int) icon.size);
            return;
        }
    }
}
