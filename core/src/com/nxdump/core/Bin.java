package com.nxdump.core;

import java.nio.charset.StandardCharsets;

final class Bin {
    private Bin() {}

    static int le16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }
    static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }
    static long le64(byte[] b, int o) { return (le32(b, o) & 0xFFFFFFFFL) | ((long) le32(b, o + 4) << 32); }

    static void putBe32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }
    static void putBe64(byte[] b, int o, long v) { putBe32(b, o, (int) (v >>> 32)); putBe32(b, o + 4, (int) v); }

    static boolean magic(byte[] b, int o, String m) {
        if (b.length < o + m.length()) return false;
        for (int i = 0; i < m.length(); i++) if (b[o + i] != (byte) m.charAt(i)) return false;
        return true;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        int n = s.length() / 2;
        byte[] r = new byte[n];
        for (int i = 0; i < n; i++) r[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return r;
    }

    static String cstr(byte[] b, int off, int max) {
        int e = off;
        int lim = Math.min(b.length, off + max);
        while (e < lim && b[e] != 0) e++;
        return new String(b, off, e - off, StandardCharsets.UTF_8);
    }
}
