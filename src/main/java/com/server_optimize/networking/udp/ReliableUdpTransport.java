package com.server_optimize.networking.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.EventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reliable, ordered, encrypted byte-stream over UDP (net.useUDP phase 2).
 * <p>
 * A Minecraft connection's byte stream is split into frames; each frame is
 * fragmented into UDP datagrams (≤ {@link #MAX_DATAGRAM_PAYLOAD}) carrying a
 * frame sequence number. The receiver reorders, de-duplicates, reassembles
 * fragments and delivers complete frames strictly in order. ACKs advance the
 * sender's sliding window; un-acked frames are retransmitted on a timer.
 * Every datagram is authenticated/encrypted with AES-GCM (independent keys
 * per direction, per-direction counter nonces), because the vanilla MC
 * stream cipher cannot be used on a reordering/retransmitting medium.
 * <p>
 * Control messages (migration handshake) ride the same reliable channel so
 * they are also ordered and retransmitted.
 * <p>
 * Datagram plaintext layout (before AES-GCM):
 * <pre>
 *   [byte kind]           0 = DATA, 1 = ACK
 *   DATA: varInt frameSeq, varInt fragIndex, varInt fragTotal, payload
 *   ACK:  varInt ackBase, varInt bitmapLen, bitmap bytes
 * </pre>
 * Frame payload layout (inside DATA):
 * <pre>
 *   [byte channel]        0 = app, 1 = control
 *   [byte ctrlType]       0 for app; SYN/SYN_ACK/READY/READY_ACK for control
 *   data bytes
 * </pre>
 */
public final class ReliableUdpTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger("server-optimize-udp");

    /** Maximum application payload bytes per UDP datagram (safe MTU). */
    public static final int MAX_DATAGRAM_PAYLOAD = 1200;
    /** Maximum frames in flight (sliding window). */
    public static final int WINDOW_SIZE = 512;
    private static final long RETRANSMIT_MS = 50;

    private static final int KIND_DATA = 0;
    private static final int KIND_ACK = 1;

    private static final int CHANNEL_APP = 0;
    private static final int CHANNEL_CTRL = 1;

    // control message types
    public static final int CTRL_SYN = 1;
    public static final int CTRL_SYN_ACK = 2;
    public static final int CTRL_READY = 3;
    public static final int CTRL_READY_ACK = 4;

    private final EventExecutor executor;
    private final BiConsumer<ByteBuf, java.net.SocketAddress> datagramSender;
    private final java.net.SocketAddress peer;
    private final byte[] token = new byte[8];
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;
    private final SecretKeySpec encryptKey;
    private final SecretKeySpec decryptKey;
    private long encryptNonce = 0;

    private Consumer<byte[]> appSink = null; // null = not armed yet
    private final java.util.ArrayDeque<byte[]> pendingApp = new java.util.ArrayDeque<>();
    private BiConsumer<Integer, byte[]> controlSink = (t, b) -> { };

    /** Arm the app sink. Frames delivered before arming are buffered and
     *  flushed in order (no loss during the migration window). */
    public void setAppSink(Consumer<byte[]> sink) {
        executor.execute(() -> {
            this.appSink = sink;
            if (sink != null) {
                while (!pendingApp.isEmpty()) sink.accept(pendingApp.poll());
            }
        });
    }

    // sender state
    private long nextSeq = 0;
    private long ackBase = 0; // first unacked seq
    private final TreeMap<Long, byte[]> outstanding = new TreeMap<>();
    private final Deque<byte[]> stallQueue = new ArrayDeque<>();
    private boolean timerScheduled = false;

    // receiver state
    private long deliverBase = 0; // next frame seq to deliver
    private final TreeMap<Long, FrameAssembler> assemblers = new TreeMap<>();
    private final TreeMap<Long, byte[]> completed = new TreeMap<>();
    private long lastAckSent = -1;

    private volatile boolean stopped = false;

    /** Frame fragment store. */
    private static final class FrameAssembler {
        final int total;
        final byte[][] frags;
        boolean[] seen;
        int received = 0;

        FrameAssembler(int total) {
            this.total = total;
            this.frags = new byte[total][];
            this.seen = new boolean[total];
        }

        boolean add(int index, byte[] data) {
            if (index < 0 || index >= total || seen[index]) return false;
            seen[index] = true;
            frags[index] = data;
            received++;
            return true;
        }

        boolean complete() {
            return received == total;
        }

        byte[] assemble() {
            int len = 0;
            for (byte[] f : frags) len += f.length;
            byte[] out = new byte[len];
            int p = 0;
            for (byte[] f : frags) {
                System.arraycopy(f, 0, out, p, f.length);
                p += f.length;
            }
            return out;
        }
    }

    /**
     * @param executor       event loop for the retransmit timer
     * @param datagramSender writes one datagram (buf + remote address). The
     *                       buffer already contains the 8-byte session token
     *                       prefix and must be sent as-is.
     * @param peer           remote address of the peer
     * @param sessionKey     32-byte shared secret (exchanged over TCP)
     * @param sessionToken   8-byte session token (plaintext prefix, used by
     *                       the server to route datagrams to this session)
     * @param isClient       true on the client side (selects direction keys)
     */
    public ReliableUdpTransport(EventExecutor executor,
                                BiConsumer<ByteBuf, java.net.SocketAddress> datagramSender,
                                java.net.SocketAddress peer,
                                byte[] sessionKey,
                                long sessionToken,
                                boolean isClient) {
        this.executor = executor;
        this.datagramSender = datagramSender;
        this.peer = peer;
        for (int i = 0; i < 8; i++) {
            this.token[i] = (byte) (sessionToken >>> (56 - i * 8));
        }
        byte[] c2s = deriveKey(sessionKey, "so:c2s");
        byte[] s2c = deriveKey(sessionKey, "so:s2c");
        this.encryptKey = new SecretKeySpec(isClient ? c2s : s2c, "AES");
        this.decryptKey = new SecretKeySpec(isClient ? s2c : c2s, "AES");
        try {
            this.encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            this.decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            // NOTE: no init here - every encrypt/decrypt re-inits with a fresh
            // per-datagram nonce. Initializing with an all-zero IV here would
            // collide with the first real nonce (0) and throw
            // "Cannot reuse iv for GCM encryption".
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM init failed", e);
        }
    }

    private static byte[] deriveKey(byte[] secret, String label) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(secret);
            md.update(label.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setControlSink(BiConsumer<Integer, byte[]> sink) {
        this.controlSink = sink;
    }

    private Runnable firstAppFrameHook;

    /** Called on the executor when the first application frame is delivered.
     *  Used to install the pipeline tunnel exactly when the peer's transport
     *  switch becomes observable. */
    public void onFirstAppFrame(Runnable hook) {
        executor.execute(() -> {
            if (firstAppFrameHook == null) {
                firstAppFrameHook = hook;
            }
        });
    }

    public java.net.SocketAddress peer() {
        return peer;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Queue an application frame for reliable transmission. */
    public void sendApp(byte[] data) {
        byte[] frame = new byte[data.length + 2];
        frame[0] = CHANNEL_APP;
        frame[1] = 0;
        System.arraycopy(data, 0, frame, 2, data.length);
        sendFrame(frame);
    }

    /** Send a control message on the reliable channel. */
    public void sendControl(int ctrlType, byte[] data) {
        byte[] frame = new byte[data.length + 2];
        frame[0] = CHANNEL_CTRL;
        frame[1] = (byte) ctrlType;
        System.arraycopy(data, 0, frame, 2, data.length);
        sendFrame(frame);
    }

    private void sendFrame(byte[] frame) {
        if (stopped) return;
        executor.execute(() -> {
            if (stopped) return;
            if (nextSeq - ackBase >= WINDOW_SIZE) {
                stallQueue.add(frame);
                scheduleTimer();
                return;
            }
            transmitFrame(nextSeq++, frame);
            scheduleTimer();
        });
    }

    private void transmitFrame(long seq, byte[] frame) {
        outstanding.put(seq, frame);
        int total = Math.max(1, (frame.length + MAX_DATAGRAM_PAYLOAD - 1) / MAX_DATAGRAM_PAYLOAD);
        for (int i = 0; i < total; i++) {
            int off = i * MAX_DATAGRAM_PAYLOAD;
            int len = Math.min(MAX_DATAGRAM_PAYLOAD, frame.length - off);
            ByteBuf plain = Unpooled.buffer(64 + len);
            plain.writeByte(KIND_DATA);
            writeVarInt(plain, seq);
            writeVarInt(plain, i);
            writeVarInt(plain, total);
            plain.writeBytes(frame, off, len);
            sendEncrypted(plain);
        }
    }

    /** Send an ACK with the current receiver state (force = even if unchanged). */
    private void sendAck(boolean force) {
        if (stopped) return;
        long base = deliverBase - 1;
        if (!force && base == lastAckSent && completed.isEmpty()) return;
        lastAckSent = base;
        int bits = Math.min(WINDOW_SIZE, 256);
        byte[] bitmap = new byte[(bits + 7) / 8];
        for (Map.Entry<Long, FrameAssembler> e : assemblers.entrySet()) {
            long seq = e.getKey();
            if (seq >= deliverBase && seq < deliverBase + bits && e.getValue().complete()) {
                int off = (int) (seq - deliverBase);
                bitmap[off >> 3] |= (byte) (1 << (off & 7));
            }
        }
        ByteBuf plain = Unpooled.buffer(32);
        plain.writeByte(KIND_ACK);
        writeVarInt(plain, base);
        writeVarInt(plain, bitmap.length);
        plain.writeBytes(bitmap);
        sendEncrypted(plain);
    }

    private void sendEncrypted(ByteBuf plain) {
        try {
            byte[] pt = new byte[plain.readableBytes()];
            plain.readBytes(pt);
            byte[] nonce = nextNonce();
            encryptCipher.init(Cipher.ENCRYPT_MODE, encryptKey, new GCMParameterSpec(128, nonce));
            byte[] ct = encryptCipher.doFinal(pt);
            ByteBuf out = Unpooled.buffer(token.length + nonce.length + ct.length);
            out.writeBytes(token);
            out.writeBytes(nonce);
            out.writeBytes(ct);
            datagramSender.accept(out, peer);
        } catch (Exception e) {
            LOGGER.warn("UDP encrypt failed", e);
        }
    }

    private byte[] nextNonce() {
        byte[] nonce = new byte[12];
        long v = encryptNonce++;
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return nonce;
    }

    // ------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------

    /** Process one inbound datagram (token + ciphertext). */
    public void receive(ByteBuf datagram) {
        if (stopped) return;
        try {
            if (datagram.readableBytes() < 8 + 12 + 16) return;
            for (int i = 0; i < 8; i++) {
                if (datagram.readByte() != token[i]) return; // wrong session
            }
            byte[] nonce = new byte[12];
            datagram.readBytes(nonce);
            byte[] ct = new byte[datagram.readableBytes()];
            datagram.readBytes(ct);
            byte[] pt;
            try {
                decryptCipher.init(Cipher.DECRYPT_MODE, decryptKey, new GCMParameterSpec(128, nonce));
                pt = decryptCipher.doFinal(ct);
            } catch (Exception e) {
                // Replay / forgery / garbage: drop silently.
                return;
            }
            ByteBuf plain = Unpooled.wrappedBuffer(pt);
            int kind = plain.readByte() & 0xFF;
            if (kind == KIND_ACK) {
                handleAck(plain);
            } else {
                handleData(plain);
            }
        } catch (Exception e) {
            LOGGER.warn("UDP receive error", e);
        }
    }

    private void handleData(ByteBuf plain) {
        long seq = readVarInt(plain);
        int fragIndex = (int) readVarInt(plain);
        int fragTotal = (int) readVarInt(plain);
        byte[] payload = new byte[plain.readableBytes()];
        plain.readBytes(payload);

        if (seq < deliverBase || fragTotal <= 0 || fragTotal > 256) {
            sendAck(true); // duplicate or bogus - remind the sender
            return;
        }
        FrameAssembler assembler = assemblers.get(seq);
        if (assembler == null) {
            assembler = new FrameAssembler(fragTotal);
            assemblers.put(seq, assembler);
        }
        assembler.add(fragIndex, payload);

        if (assembler.complete()) {
            assemblers.remove(seq);
            completed.put(seq, assembler.assemble());
            deliverReady();
            sendAck(false);
        }
    }

    private void deliverReady() {
        while (true) {
            byte[] frame = completed.get(deliverBase);
            if (frame == null) break;
            completed.remove(deliverBase);
            deliverBase++;
            int channel = frame[0] & 0xFF;
            int ctrlType = frame[1] & 0xFF;
            byte[] body = new byte[frame.length - 2];
            System.arraycopy(frame, 2, body, 0, body.length);
            if (channel == CHANNEL_CTRL) {
                controlSink.accept(ctrlType, body);
            } else {
                if (firstAppFrameHook != null) {
                    Runnable hook = firstAppFrameHook;
                    firstAppFrameHook = null;
                    hook.run();
                }
                if (appSink != null) {
                    appSink.accept(body);
                } else {
                    pendingApp.add(body);
                }
            }
        }
    }

    private void handleAck(ByteBuf plain) {
        long base = readVarInt(plain);
        int bitmapLen = (int) readVarInt(plain);
        byte[] bitmap = new byte[Math.min(bitmapLen, 256)];
        plain.readBytes(bitmap);
        if (base >= ackBase) {
            ackBase = base + 1;
        }
        for (int i = 0; i < bitmap.length; i++) {
            byte b = bitmap[i];
            for (int j = 0; j < 8; j++) {
                if ((b & (1 << j)) != 0) {
                    long seq = base + 1 + (long) i * 8 + j;
                    outstanding.remove(seq);
                }
            }
        }
        outstanding.headMap(ackBase).clear();
        while (!stallQueue.isEmpty() && nextSeq - ackBase < WINDOW_SIZE) {
            transmitFrame(nextSeq++, stallQueue.poll());
        }
        if (!outstanding.isEmpty()) scheduleTimer();
    }

    // ------------------------------------------------------------------
    // Timer / retransmit
    // ------------------------------------------------------------------

    private void scheduleTimer() {
        if (stopped) return;
        if (timerScheduled) return;
        timerScheduled = true;
        executor.schedule(this::onTimer, RETRANSMIT_MS, TimeUnit.MILLISECONDS);
    }

    private void onTimer() {
        timerScheduled = false;
        if (stopped) return;
        if (!outstanding.isEmpty()) {
            for (Map.Entry<Long, byte[]> e : outstanding.entrySet()) {
                long seq = e.getKey();
                byte[] frame = e.getValue();
                int total = Math.max(1, (frame.length + MAX_DATAGRAM_PAYLOAD - 1) / MAX_DATAGRAM_PAYLOAD);
                for (int i = 0; i < total; i++) {
                    int off = i * MAX_DATAGRAM_PAYLOAD;
                    int len = Math.min(MAX_DATAGRAM_PAYLOAD, frame.length - off);
                    ByteBuf plain = Unpooled.buffer(64 + len);
                    plain.writeByte(KIND_DATA);
                    writeVarInt(plain, seq);
                    writeVarInt(plain, i);
                    writeVarInt(plain, total);
                    plain.writeBytes(frame, off, len);
                    sendEncrypted(plain);
                }
            }
        }
        if (!outstanding.isEmpty() || !stallQueue.isEmpty()) {
            scheduleTimer();
        }
    }

    /** Stop timers / sends. Safe to call multiple times. */
    public void stop() {
        stopped = true;
        outstanding.clear();
        stallQueue.clear();
        assemblers.clear();
        completed.clear();
    }

    public boolean isStopped() {
        return stopped;
    }

    private static void writeVarInt(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    private static long readVarInt(ByteBuf buf) {
        long value = 0;
        int bytes = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (long) (b & 127) << bytes * 7;
            if (++bytes > 9) {
                throw new IllegalStateException("VarInt too big");
            }
        } while ((b & 128) == 128);
        return value;
    }
}