package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RomFs {
    private RomFs() {}

    public static final class File {
        public final String path;
        public final long offset, size;   // inside the RomFS image
        File(String path, long offset, long size) { this.path = path; this.offset = offset; this.size = size; }
    }

    public static List<File> list(Source img) throws IOException {
        byte[] h = img.read(0, 0x50);
        if (Bin.le64(h, 0) != 0x50) throw new NxException("Not a RomFS image (bad header)");
        long dirOff = Bin.le64(h, 0x18), dirSize = Bin.le64(h, 0x20);
        long fileOff = Bin.le64(h, 0x38), fileSize = Bin.le64(h, 0x40), dataOff = Bin.le64(h, 0x48);
        if (dirSize < 0 || dirSize > (256 << 20) || fileSize < 0 || fileSize > (512 << 20))
            throw new NxException("Corrupt RomFS tables");
        byte[] dirs = img.read(dirOff, (int) dirSize);
        byte[] files = img.read(fileOff, (int) fileSize);
        List<File> out = new ArrayList<>();
        walk(dirs, files, 0, "", dataOff, out, 0);
        return out;
    }

    private static void walk(byte[] dirs, byte[] files, int dir, String path, long dataOff, List<File> out, int depth)
            throws IOException {
        if (depth > 256) throw new NxException("RomFS directory nesting too deep");
        try {
            for (int f = Bin.le32(dirs, dir + 12); f != -1; f = Bin.le32(files, f + 4)) {
                int nl = Bin.le32(files, f + 28);
                out.add(new File(path + Bin.cstr(files, f + 32, nl), dataOff + Bin.le64(files, f + 8), Bin.le64(files, f + 16)));
            }
            for (int d = Bin.le32(dirs, dir + 8); d != -1; d = Bin.le32(dirs, d + 4)) {
                int nl = Bin.le32(dirs, d + 20);
                walk(dirs, files, d, path + Bin.cstr(dirs, d + 24, nl) + "/", dataOff, out, depth + 1);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new NxException("Corrupt RomFS directory tree");
        }
    }
}
