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
 *
 * <p><b>And there are two shapes, not one.</b> The eighty byte fixed part above is the
 * <em>device</em> event's - a key, a button, a motion - and XI2 sends <b>enter, leave and focus as
 * different events with a seventy-two byte fixed part</b>, measured against a reference on 2026-09-29
 * while chasing "the search box never looks focused in a forwarded application":
 *
 * <pre>
 *   enter/leave   76 bytes: fixed 72, buttons_len 1 word
 *   focus        104 bytes: fixed 72, buttons_len 8 words
 *   device       120 bytes: fixed 80, buttons_len 8 and valuators_len 2 words
 * </pre>
 *
 * <pre>
 *   ... 48 same_screen    (a device event has buttons_len's low byte here)
 *       49 focus
 *       50 buttons_len    (a device event has valuators_len here)
 *       52 mods base      (a device event has its source id here)
 *       56 mods latched       } four CARD32, where the device event packs its own into two
 *       60 mods locked        }
 *       64 mods effective     }
 *       68 group base         } four CARD8
 *       72 the button mask, where the device event has the button mask's own start at 80
 * </pre>
 *
 * <p>Sending all of them in the device shape is what this did first, and it is the worst kind of
 * wrong: the stream stays in step (the length field is honest), the event names the right window and
 * device, and every field a reader takes from the second half of the event lands two bytes or eight
 * bytes away from where it was written - so a toolkit that reads {@code same_screen}, the modifier
 * state or the button mask out of an enter or a focus event reads this server's <em>lengths</em>
 * instead. GDK reads exactly those, and the visible cost was a window that never looked focused
 * (no focus ring, no caret) while the keys went on arriving, because the focus is what a toolkit
 * takes from the event it could not read.
 */
public final class Xi2Event implements XEvent {

    /** The fixed part of a device event, before its two masks. Measured; see the class comment. */
    private static final int FIXED = 80;

    /** Words of button mask a reference sends, and words of valuator mask: measured lengths. */
    private static final int BUTTON_WORDS = 8;
    private static final int VALUATOR_WORDS = 2;

    /**
     * The three shapes a reference's XI2 events have, with the parts measured that differ.
     *
     * <p>{@code fixed} is where the trailing masks start; {@code buttonWords} and
     * {@code valuatorWords} are what the two length fields at 48/50 say - and for the two shapes
     * that are not device events those offsets hold {@code same_screen} and {@code focus} instead,
     * with {@code buttons_len} at 50.
     */
    public enum Shape {
        /** A key, a button or a motion: eighty bytes of fixed part, both masks. */
        DEVICE(120, 80, BUTTON_WORDS, VALUATOR_WORDS),
        /**
         * Enter and leave: seventy-two bytes, and one word of button mask.
         *
         * <p>One word is what a reference sends for these, because the mask is sized from the
         * <em>pointer's</em> button count - and it carries {@code sourceid}, {@code mode} and
         * {@code detail} in the eight bytes a device event spends on its source id and padding.
         */
        CROSSING(76, 72, 1, 0),
        /**
         * Focus in and focus out: seventy-two bytes, and eight words of button mask.
         *
         * <p>Eight, where the crossing beside it sends one: measured, not reasoned - a reference
         * sends {@code buttons_len = 8} on a keyboard focus event. A client sizes its mask from that
         * field, so matching it exactly is what keeps the two events the same length on the wire.
         */
        FOCUS(104, 72, BUTTON_WORDS, 0);

        final int size;
        final int fixed;
        final int buttonWords;
        final int valuatorWords;

        Shape(int size, int fixed, int buttonWords, int valuatorWords) {
            this.size = size;
            this.fixed = fixed;
            this.buttonWords = buttonWords;
            this.valuatorWords = valuatorWords;
        }
    }

    /** The whole device event, with both masks empty. */
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
    private final Shape shape;
    /** The order this event's fields were written in, which is the recipient's to read them back. */
    private final boolean bigEndian;

    private Xi2Event(byte[] bytes, short sequenceNumber, Shape shape, boolean bigEndian) {
        this.bytes = bytes;
        this.sequenceNumber = sequenceNumber;
        this.shape = shape;
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
        return shape.size;
    }

    /** The shape this event was laid out in, which decides its size and where its masks start. */
    public Shape shape() {
        return shape;
    }

    @Override
    public String getPluginName() {
        return "xinput";
    }

    /** Starts building a device event for one recipient, in its own byte order. */
    public static Builder builder(ClientConnection recipient, int extensionOpcode) {
        return new Builder(recipient.isBigEndian(), recipient.sequenceNumber(), extensionOpcode,
                Shape.DEVICE);
    }

    /** The same, in the shape the event a reference sends has. */
    public static Builder builder(ClientConnection recipient, int extensionOpcode, Shape shape) {
        return new Builder(recipient.isBigEndian(), recipient.sequenceNumber(), extensionOpcode,
                shape);
    }

    /**
     * The same, from the two things the recipient is asked for.
     *
     * <p>Separate because a test has no connection to give - building one needs a server behind it -
     * and the layout is exactly what a test has to be able to pin.
     */
    public static Builder builder(boolean bigEndian, short sequenceNumber, int extensionOpcode) {
        return new Builder(bigEndian, sequenceNumber, extensionOpcode, Shape.DEVICE);
    }

    /** The same, in a chosen shape: what a test uses to pin the crossing and focus layouts. */
    public static Builder builder(boolean bigEndian, short sequenceNumber, int extensionOpcode,
                                  Shape shape) {
        return new Builder(bigEndian, sequenceNumber, extensionOpcode, shape);
    }

    /** Field by field builder that lays out one event in the shape it was asked for. */
    public static final class Builder {
        private final byte[] bytes;
        private final boolean bigEndian;
        private final short sequenceNumber;
        private final Shape shape;

        private Builder(boolean bigEndian, short sequenceNumber, int extensionOpcode, Shape shape) {
            this.bigEndian = bigEndian;
            this.sequenceNumber = sequenceNumber;
            this.shape = shape;
            this.bytes = new byte[shape.size];
            bytes[0] = 35;                              // GenericEvent
            bytes[1] = (byte) extensionOpcode;
            card16(2, sequenceNumber);
            card32(4, shape.size / 4 - 8);              // length is the size in units, less eight
            if (shape == Shape.DEVICE) {
                // The two lengths a client sizes its arrays from, and the eight bytes of button mask
                // they ask for. All zero here, which is what a reference sends for a key; a client
                // told zero reads an empty mask rather than nothing at all.
                card16(48, BUTTON_WORDS);
                card16(50, VALUATOR_WORDS);
            } else {
                // A crossing or a focus event has no valuator mask, and 48/49 are its same_screen and
                // focus flags: only the button mask's length is here, at 50.
                card16(50, shape.buttonWords);
            }
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

        /**
         * The device an event came from: a device event's source id, or a crossing's.
         *
         * <p>Two different fields with the same meaning in the two shapes - at 52 in a device event
         * and at 16 in a crossing, where a device event has nothing (the detail it carries there is
         * the event's own, at 16 for both).
         */
        public Builder source(int deviceId) {
            if (shape == Shape.DEVICE) {
                card16(52, deviceId);
            } else if (shape == Shape.CROSSING) {
                card16(16, deviceId);
            }
            // A focus event has no source id: those four bytes are its own CARD32 detail there.
            return this;
        }

        /**
         * A crossing's {@code mode}, which sits in the byte before its detail.
         *
         * <p>The detail is not passed here: {@link #detail(int)} writes it where each shape keeps it
         * - a CARD32 for a device or focus event, and the byte before this one for a crossing - so
         * that neither field can clobber the other whatever order a caller sets them in.
         */
        public Builder crossing(byte mode) {
            if (shape == Shape.CROSSING) {
                bytes[18] = mode;
            }
            return this;
        }

        /** Whether the event's window is on the same screen as its root - 1, as a reference sends it. */
        public Builder sameScreen(boolean yes) {
            if (shape != Shape.DEVICE) {
                bytes[48] = (byte) (yes ? 1 : 0);
            }
            return this;
        }

        /** Whether the event's window is the focus window, as a crossing in this shape carries it. */
        public Builder focus(boolean yes) {
            if (shape != Shape.DEVICE) {
                bytes[49] = (byte) (yes ? 1 : 0);
            }
            return this;
        }

        public Builder time(int time) {
            card32(12, time);
            return this;
        }

        /**
         * The event's detail, which each shape keeps in its own place.
         *
         * <p>A device or a focus event carries it as a CARD32 at 16; a crossing has only one byte
         * for it, at 19, because {@code sourceid} and {@code mode} take the three before. A crossing
         * whose detail does not fit a byte - a crossing's detail is a value from nought to four - is
         * written as its low byte, which is what the field can hold.
         */
        public Builder detail(int detail) {
            if (shape == Shape.CROSSING) {
                bytes[19] = (byte) detail;
            } else {
                card32(16, detail);
            }
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
         * The keyboard's modifier state, and where a device event carries its flags word too.
         *
         * <p>Copied from the core event's {@code state}, which numbers the modifiers the same way in
         * its low eight bits. What cannot be told apart from a core state - which modifiers are
         * *held* rather than latched or locked - is put in {@code base}, and Caps Lock, the one core
         * bit that is a lock, in {@code locked}; {@code effective} is the whole of it. A client that
         * reads them for a shift-click gets the shift.
         *
         * <p>The two shapes put them in different places: a device event at 60 as it always has, and
         * a crossing or a focus event as four CARD32 from 52, which is where those two shapes keep
         * the state a device event spends on its source id. The button mask that follows - one word
         * for a crossing, eight for a focus - is the buttons the state holds, because that is what a
         * client reads it for.
         */
        public Builder modifiers(int state) {
            int mods = state & 0xFF;
            int locked = mods & LOCK_MASK;
            if (shape == Shape.DEVICE) {
                bytes[60] = (byte) (mods & ~locked);        // base
                bytes[62] = (byte) locked;                  // locked
                bytes[63] = (byte) mods;                    // effective
                return this;
            }
            card32(52, mods & ~locked);                     // base
            card32(56, 0);                                  // latched
            card32(60, locked);                             // locked
            card32(64, mods);                               // effective
            int buttons = (state >> 8) & 0x1F;              // Button1Mask..Button5Mask, as one word
            if (buttons != 0) {
                card32(shape.fixed, buttons);
            }
            return this;
        }

        /** A device event's flags word; a crossing or focus event has no such field. */
        public Builder flags(int flags) {
            if (shape == Shape.DEVICE) {
                card32(56, flags);
            }
            return this;
        }

        public Xi2Event build() {
            return new Xi2Event(bytes, sequenceNumber, shape, bigEndian);
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
