package com.server_optimize.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

/**
 * In-memory {@link FileChannel} backed by a growable byte[].
 * <p>
 * Used to redirect a vanilla {@code RegionFile}'s FileChannel into a memory
 * mirror: every chunk write lands in the byte[] instead of on disk, and the
 * mirror is flushed to the real region file atomically (.tmp + ATOMIC_MOVE)
 * on auto-save / save-all / region close. This gives write-back semantics:
 * reads are served from memory, writes never invalidate a cache (there is no
 * disk round-trip until flush), and the crash window equals the vanilla
 * auto-save period.
 * <p>
 * Only the operations used by RegionFile are meaningfully implemented
 * (positioned read/write, size, position, force, truncate); the rest throw
 * {@link UnsupportedOperationException}.
 */
public final class ByteArrayFileChannel extends FileChannel {

    private byte[] data;
    private int size;
    private long position;
    private boolean open = true;

    public ByteArrayFileChannel(byte[] initial, int len) {
        this.data = initial != null ? Arrays.copyOf(initial, Math.max(initial.length, 8192)) : new byte[8192];
        this.size = len;
    }

    /** Snapshot of the mirror content (the full "file" bytes). */
    public synchronized byte[] backing() {
        byte[] out = new byte[this.size];
        System.arraycopy(this.data, 0, out, 0, this.size);
        return out;
    }

    private void checkOpen() throws IOException {
        if (!this.open) {
            throw new IOException("closed channel");
        }
    }

    private void ensureCapacity(int needed) {
        if (needed <= this.data.length) return;
        int newCap = this.data.length;
        while (newCap < needed) {
            newCap <<= 1;
        }
        this.data = Arrays.copyOf(this.data, newCap);
    }

    @Override
    public synchronized int read(ByteBuffer dst, long position) throws IOException {
        this.checkOpen();
        if (position < 0 || position >= this.size) {
            return -1;
        }
        int n = (int) Math.min(dst.remaining(), this.size - position);
        if (n <= 0) {
            return -1;
        }
        dst.put(this.data, (int) position, n);
        return n;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int n = this.read(dst, this.position);
        if (n > 0) {
            this.position += n;
        }
        return n;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        int total = 0;
        for (int i = offset; i < offset + length; i++) {
            int n = this.read(dsts[i]);
            if (n < 0) {
                break;
            }
            total += n;
            if (dsts[i].hasRemaining()) {
                break;
            }
        }
        return total;
    }

    @Override
    public synchronized int write(ByteBuffer src, long position) throws IOException {
        this.checkOpen();
        if (position < 0) {
            throw new IOException("Negative write position: " + position);
        }
        int n = src.remaining();
        if (n == 0) {
            return 0;
        }
        int end = (int) position + n;
        this.ensureCapacity(end);
        src.get(this.data, (int) position, n);
        if (end > this.size) {
            this.size = end;
        }
        return n;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int n = this.write(src, this.position);
        if (n > 0) {
            this.position += n;
        }
        return n;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        int total = 0;
        for (int i = offset; i < offset + length; i++) {
            total += this.write(srcs[i]);
        }
        return total;
    }

    @Override
    public long position() throws IOException {
        this.checkOpen();
        return this.position;
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        this.checkOpen();
        this.position = newPosition;
        return this;
    }

    @Override
    public synchronized long size() throws IOException {
        this.checkOpen();
        return this.size;
    }

    @Override
    public FileChannel truncate(long newSize) throws IOException {
        this.checkOpen();
        if (newSize < this.size) {
            this.size = (int) newSize;
            if (this.position > newSize) {
                this.position = newSize;
            }
        }
        return this;
    }

    @Override
    public void force(boolean metaData) {
        // Data is already in memory; fsync happens on flush to disk.
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void implCloseChannel() {
        this.open = false;
    }
}