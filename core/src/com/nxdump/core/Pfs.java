package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** PFS0 (NSP, ExeFS) and HFS0 (XCI) partition parser. */
public final class Pfs {
    private Pfs() {}

    public static final class Entry {
        public final String name;
        /** Offset relative to the source passed to parse(). */
        public final long offset, size;
        public Entry(String name, long offset, long size) { this.name = name; this.offset = offset; this.size = size; }
    }

    public static List<Entry> parse(Source s) throws IOException {
        byte[] h = s.read(0, 16);
        boolean hfs = Bin.magic(h, 0, "HFS0");
        if (!hfs && !Bin.magic(h, 0, "PFS0")) throw new NxException("Not a PFS0/HFS0 partition");
        int count = Bin.le32(h, 4), strSize = Bin.le32(h, 8);
        int es = hfs ? 0x40 : 0x18;
        if (count < 0 || count > 200000 || strSize < 0 || strSize > (1 << 24))
            throw new NxException("Corrupt partition header");
        byte[] tab = s.read(16, count * es + strSize);
        long dataStart = 16L + (long) count * es + strSize;
        List<Entry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int o = i * es;
            long off = Bin.le64(tab, o), size = Bin.le64(tab, o + 8);
            int nameOff = Bin.le32(tab, o + 16);
            if (nameOff < 0 || nameOff >= strSize) throw new NxException("Corrupt partition string table");
            out.add(new Entry(Bin.cstr(tab, count * es + nameOff, strSize - nameOff), dataStart + off, size));
        }
        return out;
    }
}
