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
            byte[] t = new byte[16];
            byte[] x = new byte[16];
            for (int s = 0; s < len / 0x200; s++) {
                java.util.Arrays.fill(t, (byte) 0);
                Bin.putBe64(t, 8, firstSector + s);
                enc.doFinal(t, 0, 16, t, 0);                         // initial tweak, in place
                for (int j = 0; j < 0x200; j += 16) {
                    int p = off + s * 0x200 + j;
                    for (int k = 0; k < 16; k++) x[k] = (byte) (buf[p + k] ^ t[k]);
                    dec.doFinal(x, 0, 16, x, 0);
                    for (int k = 0; k < 16; k++) buf[p + k] = (byte) (x[k] ^ t[k]);
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

    private static final ThreadLocal<Cipher> CTR = new ThreadLocal<>();
    private static final byte[] ZEROS = new byte[16];

    /**
     * Random-access AES-CTR read. upper16[0..7] is the fixed counter half; the low 8 bytes are
     * (sectionAbsStart + pos) / 16, big-endian.
     * <p>Reads straight into {@code b} and decrypts in place (no temp buffers); a start that isn't
     * 16-byte aligned is handled by advancing the keystream past the skipped lead-in bytes.
     */
    static void ctrRead(Source raw, byte[] key, byte[] upper16, long sectionAbsStart,
                        long pos, byte[] b, int off, int len) throws IOException {
        if (len <= 0) return;
        try {
            long abs = sectionAbsStart + pos;
            int pre = (int) (abs & 15);
            byte[] iv = upper16.clone();
            Bin.putBe64(iv, 8, (abs - pre) >>> 4);
            Cipher c = CTR.get();
            if (c == null) { c = Cipher.getInstance("AES/CTR/NoPadding"); CTR.set(c); }
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            if (pre != 0) c.update(ZEROS, 0, pre);                  // burn the keystream bytes before pos
            raw.read(pos, b, off, len);
            int done = 0;
            while (done < len) {                                      // bounded steps keep provider-side copies small
                int n = Math.min(len - done, CHUNK);
                int r = c.update(b, off + done, n, b, off + done);
                if (r != n) throw new NxException("AES-CTR produced " + r + " of " + n + " bytes");
                done += n;
            }
        } catch (GeneralSecurityException e) {
            throw new NxException("AES-CTR failure: " + e.getMessage(), e);
        }
    }
}
