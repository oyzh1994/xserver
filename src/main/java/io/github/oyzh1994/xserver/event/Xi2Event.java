package io.github.oyzh1994.xserver.event;

import com.github.moaxcp.x11.protocol.X11Output;
import com.github.moaxcp.x11.protocol.XEvent;
import io.github.oyzh1994.xserver.client.ClientConnection;

import java.io.IOException;

/**
 * One XInput2 device event, assembled from raw wire fields (backlog B4).
 *
 * <p>An XI2 event is a core {@code GenericEvent} and the protocol fork has no class for it - the
 * generated one lives in an artifact this project does not depend on - so it is written here, byte
 * by byte, the way {@link RawEvent} writes the events the generated classes cannot express.
 *
 * <p><b>The layout is measured, not derived, and the measurement corrected the protocol XML.**
 * {@code tools/XiProbe} against Xvfb: a key event is **120 bytes** and a motion with two axes 136,
 * which comes out exactly when the fixed part is **eighty** bytes and the two trailing arrays follow
 * it - { @code buttons_len} eight words and {@code valuators_len} two:
 *
 * <pre>
 *   0  type 35 (GenericEvent)      24  event window
 *   1  the extension's opcode      28  child
 *   2  sequence                    32  root x   (FP1616)
 *   4  length = size/4 - 8         36  root y
 *   8  evtype                      40  window x
 *   10 device id                   44  window y
 *   12 time                        48  buttons_len     = 8, eight zero words follow
 *   16 detail                      50  valuators_len   = 2, two zero words follow
 *   20 root window                 52  source id
 *                                  54  pad, then flags, modifiers, group
 *                                  68  twelve bytes the measurement finds and the XML does not
 *                                  80  the button mask, where the arrays start
 * </pre>
 *
 * <p>Writing the fixed part as sixty eight bytes - which is what the protocol XML's field list adds
 * up to, and what this did first - puts the arrays twelve bytes early and makes the event <b>fifty
 * two bytes too short</b>. GDK does not reject that, which is the danger: it read the pointer and the
 * key positions out of the wrong place and EasyShell stopped producing characters from the keyboard,
 * with nothing in any log to say so. Measured against the same gesture on the previous build, where
 * the character appears.
 *
 * <p>Two more things about it were got wrong once each: there is <b>no 32 bit {@code full_sequence}</b>
 * after the device id, so the body starts at the timestamp (reading it as though there were one makes
 * the timestamp 1 and the detail the root's id); and the length field is the size in four byte units
 * <b>minus eight</b>.
 */
public final class Xi2Event implements XEvent {

    /** The fixed part, before the button mask and the valuator mask. Measured; see the class comment. */
    private static final int FIXED = 80;

    /** Words of button mask a reference sends, and words of valuator mask: measured lengths. */
    private static final int BUTTON_WORDS = 8;
    private static final int VALUATOR_WORDS = 2;

    /** The whole event, with both masks empty. */
    public static final int SIZE = FIXED + BUTTON_WORDS * 4 + VALUATOR_WORDS * 4;

    /**
     * {@code XI_PointerEmulated}: set on an event that a core event was translated from. A reference
     * server leaves it clear for an event that came from a real device - measured: an XTEST-driven
     * button press arrives with its flags word zero - so this server, which translates the core
     * events it already delivers, sets it the same way.
     */
    public static final int FLAG_POINTER_EMULATED = 1 << 16;

    private final byte[] bytes;
    private final short sequenceNumber;
    /** The order this event's fields were written in, which is the recipient's to read them back. */
    private final boolean bigEndian;

    private Xi2Event(byte[] bytes, short sequenceNumber, boolean bigEndian) {
        this.bytes = bytes;
        this.sequenceNumber = sequenceNumber;
        this.bigEndian = bigEndian;
    }

    /** The bytes as they go on the wire, for a test that wants to read the layout back. */
    public byte[] wire() {
        return bytes.clone();
    }

    /**
     * What this event is, for a log line.
     *
     * <p>The class name alone says {@code Xi2Event} forty times over, and which device event it is
     * - a key or a button, delivered or withheld - is exactly what a delivery question turns on. The
     * dispatcher logs events by class, so this is where the two numbers that identify it have to be.
     */
    @Override
    public String toString() {
        return "Xi2Event[evtype=" + evtype() + " device=" + card16(10) + "]";
    }

    /** The XI2 event number, as the header carries it. */
    public int evtype() {
        return card16(8);
    }

    private int card16(int offset) {
        return bigEndian ? ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF)
                : (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    @Override
    public byte getNumber() {
        return (byte) (bytes[0] & 0x7F);
    }

    @Override
    public byte getResponseCode() {
        return getNumber();
    }

    @Override
    public short getSequenceNumber() {
        return sequenceNumber;
    }

    @Override
    public byte getFirstEventOffset() {
        return 0;
    }

    @Override
    public void write(X11Output out) throws IOException {
        out.writeVoid(org.eclipse.collections.impl.factory.primitive.ByteLists.immutable.of(bytes));
    }

    @Override
    public int getSize() {
        return SIZE;
    }

    @Override
    public String getPluginName() {
        return "xinput";
    }

    /** Starts building an event for one recipient, in its own byte order. */
    public static Builder builder(ClientConnection recipient, int extensionOpcode) {
        return new Builder(recipient.isBigEndian(), recipient.sequenceNumber(), extensionOpcode);
    }

    /**
     * The same, from the two things the recipient is asked for.
     *
     * <p>Separate because a test has no connection to give - building one needs a server behind it -
     * and the layout is exactly what a test has to be able to pin.
     */
    public static Builder builder(boolean bigEndian, short sequenceNumber, int extensionOpcode) {
        return new Builder(bigEndian, sequenceNumber, extensionOpcode);
    }

    /** Field by field builder that lays out the sixty-eight byte event. */
    public static final class Builder {
        private final byte[] bytes = new byte[SIZE];
        private final boolean bigEndian;
        private final short sequenceNumber;

        private Builder(boolean bigEndian, short sequenceNumber, int extensionOpcode) {
            this.bigEndian = bigEndian;
            this.sequenceNumber = sequenceNumber;
            bytes[0] = 35;                              // GenericEvent
            bytes[1] = (byte) extensionOpcode;
            card16(2, sequenceNumber);
            card32(4, SIZE / 4 - 8);                    // length is the size in units, less eight
            // The two lengths a client sizes its arrays from, and the eight bytes of button mask
            // they ask for. All zero here, which is what a reference sends for a key; a client told
            // zero reads an empty mask rather than nothing at all.
            card16(48, BUTTON_WORDS);
            card16(50, VALUATOR_WORDS);
        }

        /** The XI2 event number - which is also the bit a client selects it by. */
        public Builder evtype(int eventNumber) {
            card16(8, eventNumber);
            return this;
        }

        public Builder device(int deviceId) {
            card16(10, deviceId);
            return this;
        }

        public Builder source(int deviceId) {
            card16(52, deviceId);
            return this;
        }

        public Builder time(int time) {
            card32(12, time);
            return this;
        }

        public Builder detail(int detail) {
            card32(16, detail);
            return this;
        }

        public Builder root(int rootId) {
            card32(20, rootId);
            return this;
        }

        public Builder event(int windowId) {
            card32(24, windowId);
            return this;
        }

        public Builder child(int childId) {
            card32(28, childId);
            return this;
        }

        /** The pointer's position on the screen, which XI2 carries as {@code FP1616}. */
        public Builder rootPosition(int x, int y) {
            card32(32, x << 16);
            card32(36, y << 16);
            return this;
        }

        /** The position in the event window's coordinates, as the core events also carry it. */
        public Builder windowPosition(int x, int y) {
            card32(40, x << 16);
            card32(44, y << 16);
            return this;
        }

        /**
         * The keyboard's modifier state, as the four bytes XI2 puts after the flags.
         *
         * <p>Copied from the core event's {@code state}, which numbers the modifiers the same way in
         * its low eight bits. What cannot be told apart from a core state - which modifiers are
         * *held* rather than latched or locked - is put in {@code base}, and Caps Lock, the one core
         * bit that is a lock, in {@code locked}; {@code effective} is the whole of it. A client that
         * reads them for a shift-click gets the shift.
         */
        public Builder modifiers(int state) {
            int mods = state & 0xFF;
            int locked = mods & LOCK_MASK;
            bytes[60] = (byte) (mods & ~locked);        // base
            bytes[62] = (byte) locked;                  // locked
            bytes[63] = (byte) mods;                    // effective
            return this;
        }

        public Builder flags(int flags) {
            card32(56, flags);
            return this;
        }

        public Xi2Event build() {
            return new Xi2Event(bytes, sequenceNumber, bigEndian);
        }

        private static final int LOCK_MASK = 0x02;       // LockMask, which is Caps Lock

        private void card16(int offset, int value) {
            if (bigEndian) {
                bytes[offset] = (byte) (value >> 8);
                bytes[offset + 1] = (byte) value;
            } else {
                bytes[offset] = (byte) value;
                bytes[offset + 1] = (byte) (value >> 8);
            }
        }

        private void card32(int offset, int value) {
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
    }
}
