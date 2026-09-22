package io.github.oyzh1994.xserver.net;

import com.github.moaxcp.x11.protocol.X11Output;
import com.github.moaxcp.x11.protocol.X11OutputStream;
import com.github.moaxcp.x11.protocol.XError;
import com.github.moaxcp.x11.protocol.XEvent;
import com.github.moaxcp.x11.protocol.X11BigEndianOutputStream;
import com.github.moaxcp.x11.protocol.X11LittleEndianOutputStream;
import com.github.moaxcp.x11.protocol.XReply;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes replies, events and errors to a client in that client's byte order.
 *
 * <p>Two quirks of the generated protocol classes are corrected here, centrally:
 *
 * <ul>
 *   <li>{@code XReply.getLength()} computes the reply length from the wrong base, so the 32-bit
 *       length field would be wrong in every reply. We rewrite it from the bytes actually
 *       produced.</li>
 *   <li>Replies shorter than the 32 byte wire header are serialized without their trailing
 *       padding. A real client reads those 32 bytes regardless, so we pad here.</li>
 * </ul>
 *
 * <p>Because both fixes need the finished byte image, everything is serialized into a small
 * scratch buffer first. Replies and events are at most a few kilobytes in practice, and the
 * copy is far cheaper than a desynchronized protocol stream.
 */
public final class ClientOutput {

    private static final Logger log = LoggerFactory.getLogger(ClientOutput.class);


    /** Fixed size of an event or error on the wire. */
    private static final int EVENT_SIZE = 32;
    /** Fixed size of a reply header; reply payload follows. */
    private static final int REPLY_HEADER_SIZE = 32;

    private final OutputStream out;
    private final boolean bigEndian;
    private final ByteArrayOutputStream scratch = new ByteArrayOutputStream(256);

    private boolean broken;

    /**
     * True when bytes have been buffered but not yet handed to the socket. A caller holding a
     * reply in here holds a client waiting for it, which is why the request loop asks.
     */
    private boolean pending;

    public ClientOutput(OutputStream out, boolean bigEndian) {
        this.out = out;
        this.bigEndian = bigEndian;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    /** True once a write has failed; the connection is torn down by the owner. */
    public boolean isBroken() {
        return broken;
    }

    public synchronized void writeReply(XReply reply) throws IOException {
        byte[] bytes = serialize(out -> reply.write(out));
        if (bytes.length < REPLY_HEADER_SIZE) {
            // Clients always consume 32 bytes of reply header, so pad the short ones.
            byte[] padded = new byte[REPLY_HEADER_SIZE];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
        }
        // length is "everything after the 32 byte header, in 4 byte units"
        int length = (bytes.length - REPLY_HEADER_SIZE) / 4;
        putCard32(bytes, 4, length);
        if (log.isTraceEnabled()) {
            log.trace("reply {}: {} bytes, length field {}", reply.getClass().getSimpleName(),
                    bytes.length, length);
        }
        write(bytes);
    }

    /**
     * Writes a reply whose length field is part of its own layout rather than the reply length.
     * Only the connection setup reply works this way: its bytes 4..8 hold the protocol version.
     */
    public synchronized void writeReplyRaw(XReply reply) throws IOException {
        write(serialize(out -> reply.write(out)));
    }

    /**
     * Writes a protocol struct verbatim. The connection setup reply is a struct rather than a
     * reply, and its length field counts only the variable part, so nothing is patched here.
     */
    public synchronized void writeStruct(com.github.moaxcp.x11.protocol.XStruct struct)
            throws IOException {
        write(serialize(struct::write));
    }

    /**
     * Writes an event and pushes it out.
     *
     * <p>An event is often produced with no request in flight - a focus change arrives from the
     * toolkit's own thread - and a client waiting for that event has nothing else to send. Left in
     * the buffer it would sit there until some unrelated request happened to arrive, which for a
     * client blocked on the event is never.
     */
    public synchronized void writeEvent(XEvent event) throws IOException {
        byte[] bytes = serialize(out -> event.write(out));
        correctEventType(bytes, event);
        write(padTo(bytes, EVENT_SIZE));
        flush();
    }

    /**
     * The event numbers the generated classes get wrong, and what they should be.
     *
     * <p>{@code xproto.xml} declares these five with {@code <eventcopy ...>} - a release is a press
     * with another number, a leave is an enter, and so on - and the generator ignores the number on
     * a copy, so each generated class carries the number of the event it copies. On the wire a
     * release then looks like a second press and a leave looks like an enter, with the effect that a
     * client never sees a click completed (buttons never fire), gets every keystroke twice, and
     * tracks hover and focus backwards.
     */
    private static final Map<Class<?>, Byte> EVENT_NUMBERS = Map.of(
            com.github.moaxcp.x11.protocol.xproto.ButtonReleaseEvent.class, (byte) 5,
            com.github.moaxcp.x11.protocol.xproto.KeyReleaseEvent.class, (byte) 3,
            com.github.moaxcp.x11.protocol.xproto.LeaveNotifyEvent.class, (byte) 8,
            com.github.moaxcp.x11.protocol.xproto.FocusOutEvent.class, (byte) 10,
            com.github.moaxcp.x11.protocol.xproto.CirculateRequestEvent.class, (byte) 27);

    /**
     * Rewrites the type byte of an event the generated protocol numbers wrongly.
     *
     * <p>Done here because every generated class's output already passes through this method, and
     * because the fault is in a dependency: the real fix is for the generator to honour the number
     * on an {@code eventcopy}.
     */
    private static void correctEventType(byte[] bytes, XEvent event) {
        Byte number = EVENT_NUMBERS.get(event.getClass());
        if (number != null) {
            // Bit 7 is the synthetic flag, which the type byte also carries.
            bytes[0] = (byte) ((bytes[0] & 0x80) | number);
        }
    }

    /**
     * Writes an error. The wire format is fixed, so it is produced directly rather than going
     * through the generated per-code error classes.
     */
    public synchronized void writeError(byte errorCode, int badValue, short sequenceNumber,
                                        byte majorOpcode, short minorOpcode) throws IOException {
        byte[] bytes = new byte[EVENT_SIZE];
        bytes[0] = 0;                       // error
        bytes[1] = errorCode;
        putCard16(bytes, 2, sequenceNumber);
        putCard32(bytes, 4, badValue);
        putCard16(bytes, 8, minorOpcode);
        bytes[10] = majorOpcode;
        // bytes 11..31 stay zero
        write(bytes);
    }

    /** Writes an already encoded reply/event image, used by extensions that format their own. */
    public synchronized void writeRaw(byte[] bytes) throws IOException {
        write(bytes);
    }

    /** True when a reply, event or error is still sitting in the buffer. */
    public synchronized boolean hasPending() {
        return pending;
    }

    public synchronized void flush() throws IOException {
        if (broken) {
            return;
        }
        try {
            out.flush();
            pending = false;
        } catch (IOException e) {
            broken = true;
            throw e;
        }
    }

    private interface Writer {
        void write(X11Output out) throws IOException;
    }

    private byte[] serialize(Writer writer) throws IOException {
        scratch.reset();
        X11OutputStream encoded = bigEndian
                ? new X11BigEndianOutputStream(scratch)
                : new X11LittleEndianOutputStream(scratch);
        writer.write(encoded);
        encoded.flush();
        return scratch.toByteArray();
    }

    private void write(byte[] bytes) throws IOException {
        if (broken) {
            throw new IOException("connection is broken");
        }
        try {
            out.write(bytes);
            pending = true;
        } catch (IOException e) {
            broken = true;
            throw e;
        }
    }

    private static byte[] padTo(byte[] bytes, int size) {
        if (bytes.length >= size) {
            return bytes;
        }
        byte[] padded = new byte[size];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    private void putCard16(byte[] bytes, int offset, short value) {
        if (bigEndian) {
            bytes[offset] = (byte) (value >> 8);
            bytes[offset + 1] = (byte) value;
        } else {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >> 8);
        }
    }

    private void putCard32(byte[] bytes, int offset, int value) {
        if (bigEndian) {
            bytes[offset] = (byte) (value >> 24);
            bytes[offset + 1] = (byte) (value >> 16);
            bytes[offset + 2] = (byte) (value >> 8);
            bytes[offset + 3] = (byte) value;
        } else {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >> 8);
            bytes[offset + 2] = (byte) (value >> 16);
            bytes[offset + 3] = (byte) (value >> 24);
        }
    }

    /** Convenience for {@link XError} instances produced by extension code. */
    public synchronized void writeError(XError error) throws IOException {
        byte[] bytes = serialize(out -> error.write(out));
        write(padTo(bytes, EVENT_SIZE));
    }
}
