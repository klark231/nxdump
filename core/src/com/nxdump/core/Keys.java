package com.nxdump.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** prod.keys / title.keys store plus ticket-imported title keys. */
public final class Keys {
    private final Map<String, byte[]> keys = new HashMap<>();
    private final Map<String, byte[]> titleKeys = new ConcurrentHashMap<>();

    public static Keys load(InputStream prod, InputStream titleOrNull) throws IOException {
        Keys k = new Keys();
        k.parse(prod, false);
        if (titleOrNull != null) k.parse(titleOrNull, true);
        return k;
    }

    public void parse(InputStream in, boolean title) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq).trim().toLowerCase();
            String val = line.substring(eq + 1).trim();
            int sp = val.indexOf(' ');
            if (sp > 0) val = val.substring(0, sp);
            if (val.isEmpty() || val.length() % 2 != 0 || !val.matches("[0-9a-fA-F]+")) continue;
            byte[] v = Bin.unhex(val);
            if (title) {
                if (name.length() == 32) titleKeys.put(name, v);
            } else {
                keys.put(name, v);
            }
        }
    }

    public int count() { return keys.size(); }
    public int titleKeyCount() { return titleKeys.size(); }
    public boolean hasHeaderKey() { byte[] k = keys.get("header_key"); return k != null && k.length == 32; }

    public int maxMasterKey() {
        int m = -1;
        for (int i = 0; i < 0x40; i++) if (keys.containsKey("master_key_" + hex2(i))) m = i;
        return m;
    }

    static String hex2(int i) { return String.format("%02x", i); }

    byte[] headerKey() throws NxException {
        byte[] k = keys.get("header_key");
        if (k == null || k.length != 32) throw new NxException("header_key is missing from prod.keys");
        return k;
    }

    private static final String[] KAK_NAMES = {"application", "ocean", "system"};

    byte[] kak(int type, int gen) throws NxException {
        if (type < 0 || type > 2) throw new NxException("Unknown key area key index " + type);
        String t = KAK_NAMES[type];
        String name = "key_area_key_" + t + "_" + hex2(gen);
        byte[] k = keys.get(name);
        if (k != null) return k;
        byte[] mk = keys.get("master_key_" + hex2(gen));
        byte[] kekSeed = keys.get("aes_kek_generation_source");
        byte[] keySeed = keys.get("aes_key_generation_source");
        byte[] src = keys.get("key_area_key_" + t + "_source");
        if (mk == null || kekSeed == null || keySeed == null || src == null)
            throw new NxException("Missing " + name + " (this game needs a newer prod.keys, master key " + hex2(gen) + ")");
        byte[] kek = Aes.ecbDecrypt(mk, kekSeed);
        byte[] srcKek = Aes.ecbDecrypt(kek, src);
        return Aes.ecbDecrypt(srcKek, keySeed);
    }

    byte[] titlekek(int gen) throws NxException {
        String name = "titlekek_" + hex2(gen);
        byte[] k = keys.get(name);
        if (k != null) return k;
        byte[] mk = keys.get("master_key_" + hex2(gen));
        byte[] src = keys.get("titlekek_source");
        if (mk == null || src == null)
            throw new NxException("Missing " + name + " (this game needs a newer prod.keys, master key " + hex2(gen) + ")");
        return Aes.ecbDecrypt(mk, src);
    }

    /** Stores an (encrypted, common) title key keyed by lowercase 32-char rights id. */
    public void addTitleKey(String rightsHex, byte[] enc) { titleKeys.put(rightsHex.toLowerCase(), enc); }
    byte[] titleKey(String rightsHex) { return titleKeys.get(rightsHex.toLowerCase()); }
}
