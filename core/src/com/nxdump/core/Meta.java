package com.nxdump.core;

final class Meta {
    private Meta() {}

    static final int TYPE_APPLICATION = 0x80, TYPE_PATCH = 0x81, TYPE_ADDON = 0x82;

    static String nacpName(byte[] nacp) {
        for (int i = 0; i < 16 && (i + 1) * 0x300 <= nacp.length; i++) {
            String n = Bin.cstr(nacp, i * 0x300, 0x200);
            if (!n.isEmpty()) return n;
        }
        return "";
    }

    static String nacpVersion(byte[] nacp) {
        return nacp.length >= 0x3070 ? Bin.cstr(nacp, 0x3060, 0x10) : "";
    }
}
