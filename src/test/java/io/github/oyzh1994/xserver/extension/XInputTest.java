package io.github.oyzh1994.xserver.extension;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.event.Xi2Event;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XInput2 - the extension whose absence is a fallback and whose half-implementation is a trap.
 *
 * <p>What is pinned here is what a client reads, and every offset in it was measured against Xvfb
 * with {@code tools/XiProbe} rather than derived from the protocol XML, because the XML is wrong in
 * one place and the wrongness is invisible: <b>the fixed part of a device event is eighty bytes, not
 * the sixty eight its field list adds up to</b>, and an event written to sixty eight is fifty two
 * bytes too short. A reference sends a hundred and twenty for a key press and a hundred and thirty
 * six for a motion with two axes, which is exactly what eighty plus the two masks comes to - and GDK,
 * told the wrong size, does not refuse the event: it reads the key code and the pointer position out
 * of the middle of it and the application quietly stops producing characters.
 *
 * <p>Two of the assertions below are about requests that are not answered at all, which is the other
 * half of the same lesson and was also measured by getting it wrong: a request <em>with</em> a reply
 * that is accepted in silence hangs the client (the XInput 1.x handshake, which is what `xinput` asks
 * first), and a request <em>without</em> one that is answered shifts the client's whole stream (the
 * cursor change GDK sends when a text field is focused, after which it asked for the pointer position
 * a million times in two minutes).
 */
class XInputTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 68;

    private static final int GET_EXTENSION_VERSION = 1;
    private static final int XI_CHANGE_CURSOR = 42;
    private static final int XI_GET_CLIENT_POINTER = 45;
    private static final int XI_SELECT_EVENTS = 46;
    private static final int XI_QUERY_VERSION = 47;
    private static final int XI_QUERY_DEVICE = 48;
    private static final int XI_QUERY_POINTER = 40;
    private static final int XI_LIST_PROPERTIES = 56;
    private static final int XI_GET_PROPERTY = 59;

    /**
     * {@code XI_ButtonPress | XI_ButtonRelease}: measured to be {@code 1<<4 | 1<<5}, not the core
     * masks' {@code 1<<2 | 1<<3}.
     *
     * <p>Both, because that is what a toolkit selects - GDK asks for the two together with motion
     * and the crossings - and because a client that selects only one of a pair is a case with no
     * right answer: the release it did not select arrives on the core rail.
     */
    private static final int XI_BUTTONS = (1 << 4) | (1 << 5);

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--geometry", "600x400", "--backend", "headless", "--no-unix"}));
        server.start();
        server.startEventLoop();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void theOldHandshakeIsAnsweredWhereTheClientReadsIt() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            assertNotEquals(0, opcode, "a client that asks has to find it");
            byte[] reply = request(client, opcode, GET_EXTENSION_VERSION, 0, 0);
            // The reply's own type byte is the header's data byte, and the fields follow from offset
            // eight - four words earlier than a reply written like XKB's GetKbdByName, which is what
            // this did first. With them four out, `present` reads as zero and `xinput list` segfaults.
            assertEquals(1, reply[1] & 0xFF, "the reply type belongs in the header's second byte");
            assertEquals(2, RawXClient.readShort(reply, 8), "server_major");
            assertEquals(0, RawXClient.readShort(reply, 10), "server_minor: what this server has");
            assertEquals(1, reply[12] & 0xFF, "present");
        }
    }

    @Test
    void itAnswersTheVersionAndNamesTwoMasterDevices() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            byte[] version = request(client, opcode, XI_QUERY_VERSION, 2, 0);
            assertEquals(2, RawXClient.readShort(version, 8), "major");
            assertEquals(0, RawXClient.readShort(version, 10), "minor");

            byte[] devices = request(client, opcode, XI_QUERY_DEVICE, 0);
            assertEquals(2, RawXClient.readShort(devices, 8), "one pointer and one keyboard");
            // The records are positional and the classes carry their own lengths. The first device
            // is the pointer at 32, and its name follows the twelve byte header; the keyboard's name
            // is the second record's, which starts after the pointer's three classes.
            assertEquals(2, RawXClient.readShort(devices, 32), "the pointer's device id");
            assertEquals(1, RawXClient.readShort(devices, 34), "MasterPointer");
            assertEquals(3, RawXClient.readShort(devices, 36), "attached to the keyboard");
            assertEquals(3, RawXClient.readShort(devices, 38), "a button class and two axes");
            assertEquals("Virtual core pointer".length(), RawXClient.readShort(devices, 40));
        }
    }

    @Test
    void aClientThatSelectsTheDeviceEventGetsThatAndNotTheCoreOne() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int xtest = client.queryExtension("XTEST");
            request(client, opcode, XI_QUERY_VERSION, 2, 0);
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 200, 0x00FF00);
            client.mapWindow(window);
            client.flush();
            // The core mask as well, deliberately: a client that asks for both is the case the
            // suppression is about, and it is what GDK does.
            client.selectInput(window, 0x0C);      // ButtonPressMask | ButtonReleaseMask

            byte[] select = new byte[20];
            select[0] = (byte) opcode;
            select[1] = XI_SELECT_EVENTS;
            RawXClient.writeShortAt(select, 2, 5);
            RawXClient.writeIntAt(select, 4, window);
            RawXClient.writeShortAt(select, 8, 1);          // one mask record
            RawXClient.writeShortAt(select, 12, 0);         // XIAllDevices
            RawXClient.writeShortAt(select, 14, 1);         // one mask word
            RawXClient.writeIntAt(select, 16, XI_BUTTONS);
            client.sendRequest(select);
            client.flush();

            client.fakeMotion(xtest, 20, 20);
            client.fakeButton(xtest, 1, true);
            client.fakeButton(xtest, 1, false);
            client.flush();

            byte[] device = null;
            java.util.List<String> seen = new java.util.ArrayList<>();
            for (byte[] event = client.readEvent(); event != null; event = client.readEvent()) {
                int type = event[0] & 0x7F;
                if (type == RawXClient.GENERIC_EVENT) {
                    int evtype = RawXClient.readShort(event, 8);
                    seen.add("device evtype " + evtype);
                    if (device == null && evtype == 4 && (event[1] & 0xFF) == opcode) {
                        device = event;
                    }
                } else {
                    seen.add("core " + type);
                }
            }
            // The click's two events, and nothing else on the button rail: a client that asked for
            // the device event and got the core one as well would act on every click twice.
            assertTrue(seen.contains("device evtype 4"),
                    "the device ButtonPress has to arrive: " + seen);
            assertTrue(seen.contains("device evtype 5"),
                    "and the device ButtonRelease: " + seen);
            assertFalse(seen.contains("core 4"), "the core ButtonPress arrived too: " + seen);
            assertFalse(seen.contains("core 5"), "the core ButtonRelease arrived too: " + seen);
            assertNotNull(device, "the device event has to arrive at all");
            assertEquals(opcode, device[1] & 0xFF, "the extension opcode in the header");
            assertEquals(4, RawXClient.readShort(device, 8), "XI_ButtonPress");
            assertEquals(2, RawXClient.readShort(device, 10), "the pointer device");
        }
    }

    @Test
    void aClientThatSelectsOnlyTheDeviceEventStillGetsIt() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int xtest = client.queryExtension("XTEST");
            request(client, opcode, XI_QUERY_VERSION, 2, 0);
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 200, 0x00FF00);
            client.mapWindow(window);
            client.flush();
            // **No core mask at all** - which is the case the delivery missed. The owner's mask and
            // the list of other clients are the *core* selections, so a client that only ever called
            // XISelectEvents appears in neither and was reached by nothing: measured with `xinput
            // test-xi2 --root`, where the server logged the button press delivered to the window and
            // sent the client no event at all. GDK asks for both rails, which is why every test
            // written against it passed.
            byte[] select = new byte[20];
            select[0] = (byte) opcode;
            select[1] = XI_SELECT_EVENTS;
            RawXClient.writeShortAt(select, 2, 5);
            RawXClient.writeIntAt(select, 4, window);
            RawXClient.writeShortAt(select, 8, 1);          // one mask record
            RawXClient.writeShortAt(select, 12, 0);         // XIAllDevices
            RawXClient.writeShortAt(select, 14, 1);         // one mask word
            RawXClient.writeIntAt(select, 16, XI_BUTTONS);
            client.sendRequest(select);
            client.flush();

            client.fakeMotion(xtest, 20, 20);
            client.fakeButton(xtest, 1, true);
            client.flush();

            byte[] event = client.readEvent();
            assertNotNull(event, "a client that asked only through XInput2 has to be told");
            assertEquals(RawXClient.GENERIC_EVENT, event[0] & 0x7F, "and told the device event");
            assertEquals(4, RawXClient.readShort(event, 8), "XI_ButtonPress");
        }
    }

    @Test
    void theDeviceEventIsTheSizeAClientSizesItFrom() {
        // Built for a little endian reader, which is what every client in this suite is.
        Xi2Event event = Xi2Event.builder(false, (short) 1, 130)
                .evtype(4)
                .device(2)
                .source(2)
                .time(1000)
                .detail(1)
                .root(0x1ff)
                .event(0x200001)
                .child(0)
                .rootPosition(10, 20)
                .windowPosition(5, 6)
                .modifiers(0)
                .build();
        byte[] wire = event.wire();
        // A reference's key event is a hundred and twenty bytes and its motion, with two axes, a
        // hundred and thirty six. A generated event class could not have carried this: every core
        // event is thirty two bytes, and this one's size is written down in its own length field.
        assertEquals(120, wire.length, "eighty bytes of fixed part and the two masks");
        assertEquals(22, RawXClient.readInt(wire, 4), "length is the size in words, less eight");
        assertEquals(35, wire[0] & 0xFF, "a GenericEvent");
        assertEquals(130, wire[1] & 0xFF, "the extension's opcode");
        assertEquals(8, RawXClient.readShort(wire, 48), "buttons_len");
        assertEquals(2, RawXClient.readShort(wire, 50), "valuators_len");
        assertEquals(2, RawXClient.readShort(wire, 52), "sourceid");
        assertEquals(10 << 16, RawXClient.readInt(wire, 32), "root x as FP1616");
        assertEquals(1, RawXClient.readInt(wire, 16), "the detail is the button");
    }

    @Test
    void aRequestWithNoReplyIsNotAnswered() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 100, 100);
            client.flush();

            // XIChangeCursor has no reply. Answering it shifts everything the client reads after it
            // by a reply's worth, and the client then acts on data that is not the answer to
            // anything - which is what GDK did, a million times over, when a text field was focused.
            byte[] cursor = new byte[12];
            cursor[0] = (byte) opcode;
            cursor[1] = XI_CHANGE_CURSOR;
            RawXClient.writeShortAt(cursor, 2, 3);
            RawXClient.writeIntAt(cursor, 4, window);
            RawXClient.writeIntAt(cursor, 8, 0);
            client.sendRequest(cursor);

            // And the next request that does have a reply gets its own back, undisturbed.
            byte[] query = request(client, opcode, XI_GET_CLIENT_POINTER, window);
            assertEquals(0, query[8] & 0xFF, "set: this client never chose a pointer");
        }
    }

    /**
     * A device property read is answered "absent", not refused - and the reply's shape is a
     * reference's, measured rather than reasoned.
     *
     * <p>A forwarded {@code EasyShell} asks this as it starts, between two {@code InternAtom} calls,
     * and the refusal was a {@code BadRequest}: an unknown minor reached the decoder, which throws.
     * The reply is thirty two bytes whose only non-zero fields are the header's, and byte 1 carries
     * the <em>minor opcode</em> - measured by asking about two devices and watching it stay 59, which
     * is what makes it the request's number and not the device's.
     *
     * <p>The request is <b>six units</b>: a reference refuses seven with {@code BadLength}, so the
     * shorter form is what a client sends and what this decoder reads.
     */
    @Test
    void theDevicePropertyReadIsAnsweredAbsentRatherThanRefused() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int property = client.internAtom("XServerBacklogProbe");
            byte[] request = new byte[24];
            request[0] = (byte) opcode;
            request[1] = XI_GET_PROPERTY;
            RawXClient.writeShortAt(request, 2, 6);
            RawXClient.writeShortAt(request, 4, 2);          // the pointer device
            RawXClient.writeIntAt(request, 8, property);
            RawXClient.writeIntAt(request, 12, 0);           // type: AnyPropertyType
            RawXClient.writeIntAt(request, 20, 1000);        // len, in four byte units
            client.sendRequest(request);

            byte[] reply = client.readReply();               // refuses by throwing
            assertEquals(1, reply[0] & 0xFF, "a reply, not an error - and a property read expects one,"
                    + " so accepting the request and answering nothing would be worse");
            assertEquals(XI_GET_PROPERTY, reply[1] & 0xFF, "the minor opcode, where a reference puts it");
            assertEquals(0, RawXClient.readInt(reply, 8), "type = None: no such property");
            assertEquals(0, RawXClient.readInt(reply, 12), "bytes_after");
            assertEquals(0, RawXClient.readInt(reply, 16), "num_items");
            assertEquals(0, reply[20] & 0xFF, "format");
        }
    }

    /**
     * The list of a device's properties is answered "none" rather than refused - the same shape of
     * bug as the property read, and the request {@code xinput list-props} sends first.
     *
     * <p>The reply is a bare header: the count is a CARD16 at 8, this server has no properties so
     * there is no tail, and byte 1 carries the request's minor opcode - read off a reference's reply
     * for a device that <em>has</em> properties ({@code XiProbe listprops 2}), since the empty answer
     * is that reply with the count at zero. Measured: {@code xinput list-props 2} prints the device
     * and no properties against a reference, and was a {@code BadRequest} here.
     */
    @Test
    void theDevicePropertyListIsAnsweredEmptyRatherThanRefused() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            byte[] request = new byte[8];
            request[0] = (byte) opcode;
            request[1] = XI_LIST_PROPERTIES;
            RawXClient.writeShortAt(request, 2, 2);
            RawXClient.writeShortAt(request, 4, 2);              // the pointer device
            client.sendRequest(request);

            byte[] reply = client.readReply();                   // refuses by throwing
            assertEquals(1, reply[0] & 0xFF, "a reply, not an error");
            assertEquals(XI_LIST_PROPERTIES, reply[1] & 0xFF, "the minor opcode, where a reference"
                    + " puts it");
            assertEquals(0, RawXClient.readShort(reply, 8), "num_properties");
            assertEquals(0, RawXClient.readInt(reply, 4), "length: no atoms follow");
        }
    }

    @Test
    void thePointerQueryAnswersInTheSizeItIsDefinedWith() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 100, 100);
            client.flush();
            byte[] reply = request(client, opcode, XI_QUERY_POINTER, window, 2 << 16);
            // Forty four bytes: the two coordinate pairs and the modifier groups come after the
            // point where a core reply would have ended, and written into a thirty two byte array
            // the last pair runs off the end.
            assertEquals(3, RawXClient.readInt(reply, 4), "length: (44 - 32) / 4");
            assertEquals(1, reply[32] & 0xFF, "same_screen, which is at 32 and not in the header");
        }
    }

    /**
     * The reply's {@code child} is the queried window's **own child**, and None when the pointer is
     * in the queried window itself.
     *
     * <p>A client walks the tree with this - ask about a window, ask again about the child the
     * answer names, until the answer is None - so an answer of "the deepest window the pointer is
     * in" ends the walk nowhere: the query about that window names itself, and the client asks about
     * it for ever. Measured live (A16): GDK does exactly that walk after a click, and EasyShell
     * against this server asked {@code XIQueryPointer} ~3700 times a second, while against Xvfb it
     * asks twice - child = the toplevel for the root query, then None for the query about it.
     */
    @Test
    void theChildIsTheQueriedWindowsOwnChildAndNotTheDeepestWindow() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XInputExtension");
            int parent = client.allocateId();
            client.createWindow(parent, client.root(), 100, 100, 200, 200);
            client.mapWindow(parent);
            int child = client.allocateId();
            client.createWindow(child, parent, 10, 10, 100, 100);
            client.mapWindow(child);
            // The pointer inside the child, so it is inside both windows - which is the case the
            // walk depends on. The warp is relative to the child, and the round trip that follows
            // is what makes the server's state readable by the next query.
            client.warpPointer(child, 50, 50);
            client.geometry(parent);

            byte[] aboutParent = request(client, opcode, XI_QUERY_POINTER, parent, 2 << 16);
            assertEquals(child, RawXClient.readInt(aboutParent, 12),
                    "the queried window's own child, which is what a client descends to");
            byte[] aboutChild = request(client, opcode, XI_QUERY_POINTER, child, 2 << 16);
            assertEquals(0, RawXClient.readInt(aboutChild, 12),
                    "and None when the pointer is in that window itself: answering the child again"
                            + " is a walk with no bottom");
        }
    }

    /**
     * Sends an XInput2 request whose body is whole words, and reads the reply.
     *
     * <p>Hand written rather than taken from the generated classes: this extension has none - the
     * fork's {@code x11-protocol-xinput} is not published - so a test that wants to speak it has to
     * lay the bytes out itself, which is also what a client on the other side of the wire does.
     */
    private static byte[] request(RawXClient client, int opcode, int minor, int... body)
            throws IOException {
        byte[] request = new byte[4 + body.length * 4];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 1 + body.length);
        for (int i = 0; i < body.length; i++) {
            RawXClient.writeIntAt(request, 4 + i * 4, body[i]);
        }
        client.sendRequest(request);
        client.flush();
        return client.readReply();
    }

}
