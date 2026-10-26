package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Nintendo Content Archive: header decryption, key derivation, section access (incl. BKTR patches). */
public final class Nca {
    public static final int TYPE_PROGRAM = 0, TYPE_META = 1, TYPE_CONTROL = 2, TYPE_MANUAL = 3, TYPE_DATA = 4;
    public static final int FS_ROMFS = 0, FS_PFS = 1;
    public static final int ENC_NONE = 1, ENC_CTR = 3, ENC_CTR_EX = 4;

    public static final class Section {
        public final int index;
        public final long start, end;
        public final int fsType, hashType, encType;
        final byte[] hdr;

        Section(int index, long start, long end, byte[] hdr) {
            this.index = index; this.start = start; this.end = end; this.hdr = hdr;
            this.fsType = hdr[2] & 0xFF; this.hashType = hdr[3] & 0xFF; this.encType = hdr[4] & 0xFF;
        }
        public boolean exists() { return end > start; }
        public long size() { return end - start; }
    }

    public final String name;
    public final int contentType;
    public final long titleId;
    public final long size;
    public final Section[] sections = new Section[4];

    private final Source src;
    private final Keys keys;
    private final byte[] rightsId = new byte[16];
    private final byte[] keyArea = new byte[0x40];
    private final int keyGen, kakIndex;
    private byte[] contentKey;

    public Nca(Source src, Keys keys, String name) throws IOException {
        this.src = src; this.keys = keys; this.name = name;
        if (src.size() < 0xC00) throw new NxException(name + ": too small to be an NCA");
        byte[] h = src.read(0, 0xC00);
        byte[] hk = keys.headerKey();
        Aes.xtsDecrypt(hk, h, 0, 0x400, 0);
        if (Bin.magic(h, 0x200, "NCA3")) {
            Aes.xtsDecrypt(hk, h, 0x400, 0x800, 2);
        } else if (Bin.magic(h, 0x200, "NCA2")) {
            for (int i = 0; i < 4; i++) Aes.xtsDecrypt(hk, h, 0x400 + i * 0x200, 0x200, 0);
        } else {
            throw new NxException(name + ": cannot decrypt NCA header (wrong header_key, or NCA0/unsupported)");
        }
        contentType = h[0x205] & 0xFF;
        kakIndex = h[0x207] & 0xFF;
        size = Bin.le64(h, 0x208);
        titleId = Bin.le64(h, 0x210);
        int g = Math.max(h[0x206] & 0xFF, h[0x220] & 0xFF);
        keyGen = g > 0 ? g - 1 : 0;
        System.arraycopy(h, 0x230, rightsId, 0, 16);
        System.arraycopy(h, 0x300, keyArea, 0, 0x40);
        for (int i = 0; i < 4; i++) {
            long s = (Bin.le32(h, 0x240 + i * 16) & 0xFFFFFFFFL) * 0x200;
            long e = (Bin.le32(h, 0x244 + i * 16) & 0xFFFFFFFFL) * 0x200;
            sections[i] = new Section(i, s, e, Arrays.copyOfRange(h, 0x400 + i * 0x200, 0x600 + i * 0x200));
        }
    }

    public boolean hasRightsId() { for (byte b : rightsId) if (b != 0) return true; return false; }
    public String rightsIdHex() { return Bin.hex(rightsId); }

    /** True if this is an update NCA (RomFS stored as a BKTR patch). */
    public boolean isPatchRomFs() {
        Section s = section(FS_ROMFS);
        return s != null && s.encType == ENC_CTR_EX;
    }

    /** First existing section with the given fs type, or null. */
    public Section section(int fsType) {
        for (Section s : sections) if (s.exists() && s.fsType == fsType) return s;
        return null;
    }

    private byte[] contentKey() throws IOException {
        if (contentKey != null) return contentKey;
        if (hasRightsId()) {
            byte[] enc = keys.titleKey(rightsIdHex());
            if (enc == null)
                throw new NxException(name + ": no title key for rights id " + rightsIdHex()
                        + " (add title.keys or place the .tik in the same NSP)");
            contentKey = Aes.ecbDecrypt(keys.titlekek(keyGen), enc);
        } else {
            byte[] area = Aes.ecbDecrypt(keys.kak(kakIndex, keyGen), keyArea);
            contentKey = Arrays.copyOfRange(area, 0x20, 0x30);
        }
        return contentKey;
    }

    private static byte[] upper16(Section s) {
        byte[] u = new byte[16];
        for (int i = 0; i < 8; i++) u[i] = s.hdr[0x140 + 7 - i];
        return u;
    }

    /** Decrypted section contents (offset 0 = start of section). */
    public Source openSection(Section s) throws IOException {
        if (s == null || !s.exists()) throw new NxException(name + ": section missing");
        Source raw = src.slice(s.start, s.size());
        switch (s.encType) {
            case ENC_NONE: return raw;
            case 0:
            case ENC_CTR: return new Ctr(raw, contentKey(), upper16(s), s.start);
            case ENC_CTR_EX: throw new NxException(name + ": patch section needs a base game");
            default: throw new NxException(name + ": unsupported section encryption " + s.encType);
        }
    }

    /** The filesystem payload of a section: PFS0 image, or RomFS image inside the IVFC layout. */
    public Source openData(Section s) throws IOException { return fsData(s, openSection(s)); }

    Source fsData(Section s, Source decrypted) throws IOException {
        long off, len;
        if (s.hashType == 2 || s.hashType == 5) { off = Bin.le64(s.hdr, 0x40); len = Bin.le64(s.hdr, 0x48); }
        else if (s.hashType == 3 || s.hashType == 6) { off = Bin.le64(s.hdr, 0x90); len = Bin.le64(s.hdr, 0x98); }
        else throw new NxException(name + ": unsupported hash layout " + s.hashType);
        if (off < 0 || len <= 0) throw new NxException(name + ": corrupt section layout");
        return decrypted.slice(off, len);
    }

    /** RomFS image of an update NCA merged on top of the base game's decrypted RomFS section. */
    public Source openPatchedRomFs(Section patchSec, Source baseSection) throws IOException {
        byte[] h = patchSec.hdr;
        long relOff = Bin.le64(h, 0x100), relSize = Bin.le64(h, 0x108);
        long subOff, subSize;
        int relCnt, subCnt;
        if (Bin.magic(h, 0x110, "BKTR")) {          // interleaved layout
            relCnt = Bin.le32(h, 0x118);
            subOff = Bin.le64(h, 0x120); subSize = Bin.le64(h, 0x128); subCnt = Bin.le32(h, 0x138);
        } else {                                     // grouped layout
            subOff = Bin.le64(h, 0x110); subSize = Bin.le64(h, 0x118);
            relCnt = Bin.le32(h, 0x128); subCnt = Bin.le32(h, 0x138);
        }
        if (relCnt <= 0 || subCnt <= 0) throw new NxException(name + ": patch has no BKTR tables");

        Source raw = src.slice(patchSec.start, patchSec.size());
        byte[] key = contentKey();
        byte[] up = upper16(patchSec);
        Source plain = new Ctr(raw, key, up, patchSec.start);   // tables use the plain section counter

        List<byte[]> rel = readTree(plain, relOff, relSize, 20, relCnt);
        List<byte[]> sub = readTree(plain, subOff, subSize, 16, subCnt);

        long[] subOffs = new long[sub.size()];
        int[] subCtr = new int[sub.size()];
        for (int i = 0; i < subOffs.length; i++) {
            subOffs[i] = Bin.le64(sub.get(i), 0);
            subCtr[i] = Bin.le32(sub.get(i), 12);
        }
        Source patchData = new CtrEx(raw, key, up, patchSec.start, subOffs, subCtr);

        long[] virt = new long[rel.size()], phys = new long[rel.size()];
        int[] stor = new int[rel.size()];
        for (int i = 0; i < virt.length; i++) {
            virt[i] = Bin.le64(rel.get(i), 0);
            phys[i] = Bin.le64(rel.get(i), 8);
            stor[i] = Bin.le32(rel.get(i), 16);
        }
        return fsData(patchSec, new Patched(virt, phys, stor, baseSection, patchData));
    }

    private static List<byte[]> readTree(Source s, long off, long size, int entrySize, int count) throws IOException {
        if (size <= 0 || size > (64 << 20)) throw new NxException("Corrupt BKTR table");
        byte[] t = s.read(off, (int) size);
        final int nodeSize = 0x4000;
        int offsetCount = (nodeSize - 16) / 8;
        int perSet = (nodeSize - 16) / entrySize;
        int sets = (count + perSet - 1) / perSet;
        long nodeStorage = sets <= offsetCount ? nodeSize : (long) ((sets + offsetCount - 1) / offsetCount + 1) * nodeSize;
        List<byte[]> out = new ArrayList<>(count);
        for (int si = 0; si < sets && out.size() < count; si++) {
            int base = (int) (nodeStorage + (long) si * nodeSize);
            if (base + 16 > t.length) throw new NxException("Corrupt BKTR table");
            int cnt = Bin.le32(t, base + 4);
            for (int k = 0; k < cnt && out.size() < count; k++) {
                int p = base + 16 + k * entrySize;
                if (p + entrySize > t.length) throw new NxException("Corrupt BKTR table");
                out.add(Arrays.copyOfRange(t, p, p + entrySize));
            }
        }
        if (out.size() != count) throw new NxException("BKTR entry count mismatch");
        return out;
    }

    private static int floorIdx(long[] a, long v) {
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (a[mid] <= v) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    // ---- storages ---------------------------------------------------------------------------

    private static final class Ctr implements Source {
        private final Source raw; private final byte[] key, upper; private final long absStart;
        Ctr(Source raw, byte[] key, byte[] upper, long absStart) { this.raw = raw; this.key = key; this.upper = upper; this.absStart = absStart; }
        @Override public long size() { return raw.size(); }
        @Override public void read(long pos, byte[] b, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > raw.size()) throw new java.io.EOFException("Read past end of section");
            Aes.ctrRead(raw, key, upper, absStart, pos, b, off, len);
        }
    }

    /** AES-CTR where bytes 4..7 of the counter come from the subsection table. */
    private static final class CtrEx implements Source {
        private final Source raw; private final byte[] key, upper; private final long absStart;
        private final long[] offs; private final int[] ctrs;
        CtrEx(Source raw, byte[] key, byte[] upper, long absStart, long[] offs, int[] ctrs) {
            this.raw = raw; this.key = key; this.upper = upper; this.absStart = absStart; this.offs = offs; this.ctrs = ctrs;
        }
        @Override public long size() { return raw.size(); }
        @Override public void read(long pos, byte[] b, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > raw.size()) throw new java.io.EOFException("Read past end of patch data");
            while (len > 0) {
                int i = floorIdx(offs, pos);
                long segEnd = i + 1 < offs.length ? offs[i + 1] : raw.size();
                int n = (int) Math.min(len, segEnd - pos);
                if (n <= 0) throw new NxException("Corrupt BKTR subsection table");
                byte[] up = upper.clone();
                Bin.putBe32(up, 4, ctrs[i]);
                Aes.ctrRead(raw, key, up, absStart, pos, b, off, n);
                pos += n; off += n; len -= n;
            }
        }
    }

    private static final class Patched implements Source {
        private final long[] virt, phys; private final int[] stor; private final Source base, patch;
        Patched(long[] virt, long[] phys, int[] stor, Source base, Source patch) {
            this.virt = virt; this.phys = phys; this.stor = stor; this.base = base; this.patch = patch;
        }
        @Override public long size() { return Long.MAX_VALUE / 2; }
        @Override public void read(long pos, byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int i = floorIdx(virt, pos);
                long end = i + 1 < virt.length ? virt[i + 1] : Long.MAX_VALUE;
                int n = (int) Math.min(len, end - pos);
                long p = phys[i] + (pos - virt[i]);
                (stor[i] == 0 ? base : patch).read(p, b, off, n);
                pos += n; off += n; len -= n;
            }
        }
    }
}
