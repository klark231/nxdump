package com.nxdump.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** An NSP, XCI or bare NCA file. */
public final class Container implements Closeable {
    public static final class Item {
        public final String name;
        public final long offset, size;
        Item(String name, long offset, long size) { this.name = name; this.offset = offset; this.size = size; }
    }

    public final String fileName;
    public final List<Item> items = new ArrayList<>();
    private final Source src;
    private final Closeable closer;

    private Container(Source src, String fileName, Closeable closer) {
        this.src = src; this.fileName = fileName; this.closer = closer;
    }

    public static Container open(Source src, String fileName, Closeable closer) throws IOException {
        Container c = new Container(src, fileName, closer);
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".nsz") || lower.endsWith(".xcz") || lower.endsWith(".ncz"))
            throw new NxException("Compressed " + lower.substring(lower.length() - 3).toUpperCase() + " files are not supported; decompress to NSP/XCI first");
        if (src.size() >= 4 && Bin.magic(src.read(0, 4), 0, "PFS0")) {
            for (Pfs.Entry e : Pfs.parse(src)) c.items.add(new Item(e.name, e.offset, e.size));
        } else if (src.size() > 0x200 && Bin.magic(src.read(0x100, 4), 0, "HEAD")) {
            c.parseXci();
        } else if (lower.endsWith(".nca")) {
            c.items.add(new Item(fileName, 0, src.size()));
        } else {
            throw new NxException("Unsupported file: expected NSP, XCI or NCA");
        }
        return c;
    }

    private void parseXci() throws IOException {
        long root = -1;
        for (int cand : new int[]{0x130, 0x138}) {
            long o = Bin.le64(src.read(cand, 8), 0);
            if (o > 0 && o + 4 <= src.size() && Bin.magic(src.read(o, 4), 0, "HFS0")) { root = o; break; }
        }
        if (root < 0) root = 0xF000;
        Source rootSrc = src.slice(root, src.size() - root);
        for (Pfs.Entry part : Pfs.parse(rootSrc)) {
            if (!part.name.equals("secure")) continue;
            long base = root + part.offset;
            for (Pfs.Entry e : Pfs.parse(src.slice(base, part.size))) items.add(new Item(e.name, base + e.offset, e.size));
        }
        if (items.isEmpty()) throw new NxException("XCI has no secure partition content");
    }

    public Source itemSource(Item i) { return src.slice(i.offset, i.size); }

    /** Registers common-type titlekeys from every .tik in this file. */
    public void importTickets(Keys keys) {
        for (Item it : items) {
            if (!it.name.toLowerCase().endsWith(".tik") || it.size < 0x2A0 + 16) continue;
            try {
                byte[] t = itemSource(it).read(0, (int) Math.min(it.size, 0x2C0));
                long sig = Bin.le32(t, 0) & 0xFFFFFFFFL;
                int body;
                if (sig == 0x10000 || sig == 0x10003) body = 4 + 0x200 + 0x3C;
                else if (sig == 0x10001 || sig == 0x10004) body = 4 + 0x100 + 0x3C;
                else if (sig == 0x10002 || sig == 0x10005) body = 4 + 0x3C + 0x40;
                else continue;
                if (body + 0x170 > t.length || t[body + 0x141] != 0) continue;   // 0 = common key type
                byte[] rid = java.util.Arrays.copyOfRange(t, body + 0x160, body + 0x170);
                keys.addTitleKey(Bin.hex(rid), java.util.Arrays.copyOfRange(t, body + 0x40, body + 0x50));
            } catch (IOException ignored) { }
        }
    }

    /** Opens every NCA; ones that fail are reported in errors (may be null). */
    public List<Nca> openNcas(Keys keys, List<String> errors) {
        List<Nca> out = new ArrayList<>();
        for (Item it : items) {
            if (!it.name.toLowerCase().endsWith(".nca")) continue;
            try {
                out.add(new Nca(itemSource(it), keys, it.name));
            } catch (IOException e) {
                if (errors != null) errors.add(e.getMessage());
            }
        }
        return out;
    }

    @Override public void close() throws IOException { if (closer != null) closer.close(); }
}
