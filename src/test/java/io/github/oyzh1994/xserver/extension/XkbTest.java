package io.github.oyzh1994.xserver.extension;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.input.Keymap;
import io.github.oyzh1994.xserver.input.Keysyms;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XKEYBOARD - the extension that exists because its absence is not a fallback but a crash.
 *
 * <p>{@code xdotool} builds its character map from {@code XkbGetMap} and does not check the answer
 * for null, so a server without the extension makes it dereference one in its own startup, before
 * any subcommand has sent a request. That was measured with a backtrace (A4); what this test pins is
 * the part a Java client can see, and the one property of the reply that made the difference between
 * Xlib parsing it and Xlib returning null.
 *
 * <p><b>The map has to have at least the four mandatory types.</b> That is not a style choice: with
 * three (one-level, two-level and alphabetic - everything this server's keymap actually uses) Xlib's
 * {@code XkbGetMap} answers null, which xdotool cannot tell from the extension being missing, so the
 * crash is identical. It was found by bisecting: the same reply with Xvfb's own types parsed, the
 * types section being byte-for-byte the same in the two; adding the keypad type made it parse. Every
 * assertion about the map below is written against a reply that Xlib accepted.
 */
class XkbTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 65;

    private static final int USE_EXTENSION = 0;
    private static final int SELECT_EVENTS = 1;
    private static final int GET_STATE = 4;
    private static final int LATCH_LOCK_STATE = 5;
    private static final int GET_MAP = 8;
    private static final int PER_CLIENT_FLAGS = 21;

    /** {@code XkbUseCoreKbd}. */
    private static final int CORE_KEYBOARD = 0x0100;

    /** {@code XkbKeyTypesMask | XkbKeySymsMask | XkbKeyModifierMapMask}, what xdotool asks for. */
    private static final int ALL_CLIENT_INFO = 0x7;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--geometry", "600x400", "--backend", "headless", "--no-unix"}));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void itIsOfferedAndAgreesTheVersionTheClientSpeaks() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XKEYBOARD");
            assertNotEquals(0, opcode, "the whole point is that a client finds it");

            byte[] reply = useExtension(client, opcode);
            assertEquals(1, reply[1] & 0xFF, "supported: Xlib's own helpers stop here if it is zero");
            assertEquals(1, RawXClient.readShort(reply, 8), "the major version");
        }
    }

    @Test
    void itsMapCarriesTheFourMandatoryTypes() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            byte[] map = getMap(client, client.queryExtension("XKEYBOARD"), ALL_CLIENT_INFO);
            assertEquals(ALL_CLIENT_INFO, RawXClient.readShort(map, 12), "present");
            int types = map[15] & 0xFF;
            // This is the assertion the item turns on: three types is not a smaller map, it is no
            // map at all - Xlib rejects the reply whole and XkbGetMap returns null.
            assertTrue(types >= 4,
                    "XKB's four required types (one level, two level, alphabetic, keypad) have to be"
                            + " there; a reply with fewer is refused by Xlib, not trimmed by it, and"
                            + " the client then faults on a null keymap - got " + types);
            assertEquals(8, map[10] & 0xFF, "minKeyCode");
            assertEquals(Keymap.MAX_KEYCODE, map[11] & 0xFF, "maxKeyCode");
        }
    }

    @Test
    void itsMapSaysWhatEachKeycodeTypes() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            byte[] map = getMap(client, client.queryExtension("XKEYBOARD"), ALL_CLIENT_INFO);
            // 'a' is two levels and a letter; the keysym array is the server's own, so what XKB
            // answers and what GetKeyboardMapping answers cannot disagree.
            assertArrayEquals(new int[] {'a', 'A'}, keysymsOf(map, Keymap.keycodeForLetter('a')));
            assertArrayEquals(new int[] {Keysyms.ESCAPE}, keysymsOf(map, 9));
        }
    }

    @Test
    void itsMapSaysWhichKeycodesAreModifiers() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            byte[] map = getMap(client, client.queryExtension("XKEYBOARD"), ALL_CLIENT_INFO);
            int entries = map[33] & 0xFF;
            boolean leftShift = false;
            int at = arraysStart(map);
            for (int i = 0; i < entries; i++) {
                if ((map[at + i * 2] & 0xFF) == 50) {
                    leftShift = (map[at + i * 2 + 1] & 0xFF) == 1;      // ShiftMask
                }
            }
            assertTrue(leftShift, "the modifier map is a list of keycode/mods pairs, and Shift_L is"
                    + " the first one every client looks for");
        }
    }

    @Test
    void theOneWayRequestsAreAcceptedAndTheOnesWithRepliesAnswer() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XKEYBOARD");
            assertEquals(3, state(client, opcode)[1] & 0xFF, "the device the map names");

            // SelectEvents and LatchLockState answer with nothing at all - there is no reply to wait
            // for, and a refusal would be an error instead. So the check is the request *after*
            // them: an error would arrive first, and readReply throws rather than answering.
            client.sendRequest(oneWay(opcode, SELECT_EVENTS));
            client.sendRequest(oneWay(opcode, LATCH_LOCK_STATE));
            assertEquals(1, getMapBytes(client, opcode)[0] & 0xFF, "a reply, and not a refusal");

            // PerClientFlags is the one JavaFX actually sends as EasyShell starts. This server
            // supports none of the five flags, and says so in a reply - being silent about it made
            // the client read a protocol error instead.
            byte[] flags = client.extensionRequest(opcode, PER_CLIENT_FLAGS, 7);
            assertEquals(1, flags[0] & 0xFF, "a reply");
            assertEquals(0, RawXClient.readInt(flags, 8), "no per-client flags are supported here");
        }
    }

    // ------------------------------------------------------------------
    // The requests, in bytes, because the layout is positional
    // ------------------------------------------------------------------

    private static byte[] useExtension(RawXClient client, int opcode) throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) USE_EXTENSION;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeShortAt(request, 4, 1);     // wantedMajor
        RawXClient.writeShortAt(request, 6, 0);     // wantedMinor
        client.sendRequest(request);
        return client.readReply();
    }

    /** One of the requests that has no reply: four units, the device, everything else zero. */
    private static byte[] oneWay(int opcode, int minor) {
        byte[] request = new byte[16];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 4);
        RawXClient.writeShortAt(request, 4, CORE_KEYBOARD);
        return request;
    }

    private static byte[] state(RawXClient client, int opcode) throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_STATE;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeShortAt(request, 4, CORE_KEYBOARD);
        client.sendRequest(request);
        return client.readReply();
    }

    private static byte[] getMap(RawXClient client, int opcode, int which) throws IOException {
        byte[] request = mapRequest(opcode, which);
        client.sendRequest(request);
        return client.readReply();
    }

    /** The bytes of the reply, for a caller that only wants to know it was a reply. */
    private static byte[] getMapBytes(RawXClient client, int opcode) throws IOException {
        return getMap(client, opcode, ALL_CLIENT_INFO);
    }

    private static byte[] mapRequest(int opcode, int which) {
        byte[] request = new byte[28];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_MAP;
        RawXClient.writeShortAt(request, 2, 7);
        RawXClient.writeShortAt(request, 4, CORE_KEYBOARD);
        RawXClient.writeShortAt(request, 6, which);
        // Every other field zero: no partial, no slices, which is "the whole map".
        return request;
    }

    // ------------------------------------------------------------------
    // Reading the reply, whose offsets are the protocol's own
    // ------------------------------------------------------------------

    /** Where the modifier map starts: past the types, the sym maps and the keysyms they carry. */
    private static int arraysStart(byte[] map) {
        int at = 40;
        int types = map[15] & 0xFF;
        for (int i = 0; i < types; i++) {
            int entries = map[at + 5] & 0xFF;
            boolean preserve = (map[at + 6] & 0xFF) != 0;
            at += 8 + entries * (preserve ? 12 : 8);
        }
        int syms = map[20] & 0xFF;
        for (int i = 0; i < syms; i++) {
            at += 8 + RawXClient.readShort(map, at + 6) * 4;
        }
        return at;
    }

    /** The keysyms a keycode carries, read the way Xlib reads them: inline, after its own entry. */
    private static int[] keysymsOf(byte[] map, int keycode) {
        int at = 40;
        int types = map[15] & 0xFF;
        for (int i = 0; i < types; i++) {
            int entries = map[at + 5] & 0xFF;
            boolean preserve = (map[at + 6] & 0xFF) != 0;
            at += 8 + entries * (preserve ? 12 : 8);
        }
        int first = map[17] & 0xFF;
        int count = map[20] & 0xFF;
        for (int i = 0; i < count; i++) {
            int nsyms = RawXClient.readShort(map, at + 6);
            if (first + i == keycode) {
                int[] keysyms = new int[nsyms];
                for (int level = 0; level < nsyms; level++) {
                    keysyms[level] = RawXClient.readInt(map, at + 8 + level * 4);
                }
                return keysyms;
            }
            at += 8 + nsyms * 4;
        }
        return new int[0];
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
