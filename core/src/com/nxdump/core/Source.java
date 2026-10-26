package com.nxdump.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Random-access, read-only byte source. */
public interface Source {
    long size();

    /** Reads exactly len bytes at pos or throws. */
    void read(long pos, byte[] b, int off, int len) throws IOException;

    default byte[] read(long pos, int len) throws IOException {
        byte[] b = new byte[len];
        read(pos, b, 0, len);
        return b;
    }

    default Source slice(long off, long len) { return new Slice(this, off, len); }

    final class Slice implements Source {
        private final Source parent;
        private final long off, len;
        public Slice(Source parent, long off, long len) { this.parent = parent; this.off = off; this.len = len; }
        @Override public long size() { return len; }
        @Override public void read(long pos, byte[] b, int o, int n) throws IOException {
            if (pos < 0 || n < 0 || pos + n > len) throw new EOFException("Read past end of region");
            parent.read(off + pos, b, o, n);
        }
    }

    final class Bytes implements Source {
        private final byte[] data;
        public Bytes(byte[] data) { this.data = data; }
        @Override public long size() { return data.length; }
        @Override public void read(long pos, byte[] b, int o, int n) throws IOException {
            if (pos < 0 || n < 0 || pos + n > data.length) throw new EOFException("Read past end of buffer");
            System.arraycopy(data, (int) pos, b, o, n);
        }
    }

    final class OfChannel implements Source, Closeable {
        private final FileChannel ch;
        private final long size;
        private final Closeable extra;
        public OfChannel(FileChannel ch, Closeable extra) throws IOException {
            this.ch = ch; this.size = ch.size(); this.extra = extra;
        }
        @Override public long size() { return size; }
        @Override public void read(long pos, byte[] b, int o, int n) throws IOException {
            if (pos < 0 || n < 0 || pos + n > size) throw new EOFException("Read past end of file");
            ByteBuffer bb = ByteBuffer.wrap(b, o, n);
            long p = pos;
            while (bb.hasRemaining()) {
                int r = ch.read(bb, p);
                if (r < 0) throw new EOFException("Unexpected end of file");
                p += r;
            }
        }
        @Override public void close() throws IOException {
            ch.close();
            if (extra != null) extra.close();
        }
    }
}
