package com.nxdump.core;

import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Aes {
    private Aes() {}

    private static final int CHUNK = 1 << 20;

    static byte[] ecbDecrypt(byte[] key, byte[] data) throws NxException {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new NxException("AES failure: " + e.getMessage(), e);
        }
    }

    /** AES-128-XTS decrypt in place, 0x200-byte sectors, Nintendo (big-endian) tweak. key = 32 bytes. */
    static void xtsDecrypt(byte[] key, byte[] buf, int off, int len, long firstSector) throws NxException {
        try {
            Cipher dec = Cipher.getInstance("AES/ECB/NoPadding");
            dec.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, 0, 16, "AES"));
            Cipher enc = Cipher.getInstance("AES/ECB/NoPadding");
            enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 16, 16, "AES"));
            byte[] tweak = new byte[16];
            byte[] x = new byte[16];
            for (int s = 0; s < len / 0x200; s++) {
                java.util.Arrays.fill(tweak, (byte) 0);
                Bin.putBe64(tweak, 8, firstSector + s);
                byte[] t = enc.doFinal(tweak);
                for (int j = 0; j < 0x200; j += 16) {
                    int p = off + s * 0x200 + j;
                    for (int k = 0; k < 16; k++) x[k] = (byte) (buf[p + k] ^ t[k]);
                    byte[] y = dec.doFinal(x);
                    for (int k = 0; k < 16; k++) buf[p + k] = (byte) (y[k] ^ t[k]);
                    int carry = 0;
                    for (int k = 0; k < 16; k++) {
                        int nc = (t[k] & 0xFF) >>> 7;
                        t[k] = (byte) ((t[k] << 1) | carry);
                        carry = nc;
                    }
                    if (carry != 0) t[0] ^= (byte) 0x87;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new NxException("AES-XTS failure: " + e.getMessage(), e);
        }
    }

    /**
     * Random-access AES-CTR read. upper16[0..7] is the fixed counter half; the low 8 bytes are
     * (sectionAbsStart + pos) / 16, big-endian.
     */
    static void ctrRead(Source raw, byte[] key, byte[] upper16, long sectionAbsStart,
                        long pos, byte[] b, int off, int len) throws IOException {
        try {
            SecretKeySpec ks = new SecretKeySpec(key, "AES");
            while (len > 0) {
                long abs = sectionAbsStart + pos;
                int pre = (int) (abs & 15);
                int n = Math.min(len, CHUNK - pre);
                byte[] tmp = new byte[pre + n];
                raw.read(pos - pre, tmp, 0, pre + n);
                byte[] iv = upper16.clone();
                Bin.putBe64(iv, 8, (abs - pre) >>> 4);
                Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
                byte[] out = c.doFinal(tmp);
                System.arraycopy(out, pre, b, off, n);
                pos += n; off += n; len -= n;
            }
        } catch (GeneralSecurityException e) {
            throw new NxException("AES-CTR failure: " + e.getMessage(), e);
        }
    }
}
