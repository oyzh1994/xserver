package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Backing store: a window that asks for one is not asked to draw again what it never lost.
 *
 * <p>Without it, this server restored a revealed window the only way it could - paint the lower
 * window's background and send it an {@code Expose} - and that is still what a window with the
 * default {@code backing_store} gets. A window that asks for {@code WhenMapped} or {@code Always}
 * has its pixels saved before something covers it, and gets them back with no {@code Expose} at all,
 * which is what a reference server was measured doing: with the attribute set, uncovering the window
 * delivers **no** event and the pixels are the ones that were there; with it unset, one
 * {@code Expose} arrives. The pixels come back either way - the difference is whether the client is
 * told to draw them.
 *
 * <p>The third test is the half that makes the first one safe: a window that <em>draws</em> while it
 * is covered is asked to draw again after all. Its drawing was clipped to what showed, so the saved
 * pixels are no longer what it would have drawn - and putting them back would show a client its own
 * stale picture with no way to correct it.
 */
class BackingStoreTest {

    /** A display number nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 81;

    private static final int EXPOSURE_MASK = 1 << 15;

    private static final int EXPOSE = 12;

    /** What a window is created with: X's own 24 bit colour, which this server reads as opaque. */
    private static final int WHITE_RGB = 0xFFFFFF;
    private static final int BLACK_RGB = 0x000000;

    /** And the same colours the way {@code pixelAt} answers them, ARGB and opaque. */
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    /** {@code WhenMapped}, which is the capability a server advertises when it has one. */
    private static final int WHEN_MAPPED = 1;

    /** How long to wait before deciding that nothing more is coming. */
    private static final int QUIET_MILLIS = 200;

    private XServer server;
    private RawXClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix"}));
        server.start();
        client = RawXClient.connect(DISPLAY);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        server.stop();
    }

    @Test
    void aWindowThatAskedForOneIsNotAskedToDrawWhatItKept() throws Exception {
        int lower = lowerWindow(WHEN_MAPPED);
        quiet();
        int upper = coverAndUncover(lower);

        assertNull(client.readEvent(),
                "the window asked for a backing store and was asked to draw anyway");
        assertEquals(WHITE, client.pixelAt(lower, 50, 50),
                "and the pixels it kept are the ones that were under the window that covered it");
        assertNotNull(upper, "the covering window existed");
    }

    @Test
    void aWindowWithNoBackingStoreIsAskedToDrawAgain() throws Exception {
        int lower = lowerWindow(0);
        quiet();
        coverAndUncover(lower);

        byte[] event = client.readEvent();
        assertNotNull(event, "a window with no backing store has to be told to draw");
        assertEquals(EXPOSE, event[0] & 0xFF, "and told with the event that means it");
        assertEquals(WHITE, client.pixelAt(lower, 50, 50),
                "the area came back either way - the difference is who draws it");
    }

    @Test
    void aWindowThatDrewWhileItWasCoveredIsAskedToDrawAgain() throws Exception {
        int lower = lowerWindow(WHEN_MAPPED);
        int gc = client.allocateId();
        client.createGC(gc, client.root(), BLACK_RGB);
        client.flush();
        quiet();

        int upper = coverAndUncover(lower, gc);

        byte[] event = client.readEvent();
        assertNotNull(event, "a window that drew since it was saved has to be told to draw again:"
                + " its drawing was clipped to what showed, so the saved pixels are not its picture"
                + " any more");
        assertEquals(EXPOSE, event[0] & 0xFF, "and told with the event that means it");
        assertNotNull(upper, "the covering window existed");
    }

    // ------------------------------------------------------------------

    /** A window that shows white, with the backing store the test is about, mapped and quiet. */
    private int lowerWindow(int backingStore) throws Exception {
        int lower = client.allocateId();
        byte[] request = new byte[40];
        request[0] = 1;                             // CreateWindow
        request[1] = 0;                             // depth: copy from the parent
        RawXClient.writeShortAt(request, 2, request.length / 4);
        RawXClient.writeIntAt(request, 4, lower);
        RawXClient.writeIntAt(request, 8, client.root());
        RawXClient.writeShortAt(request, 16, 200);  // width
        RawXClient.writeShortAt(request, 18, 200);  // height
        RawXClient.writeShortAt(request, 22, 1);    // InputOutput
        // CWBackPixel | CWBackingStore, whose values follow in the order the bits are numbered in.
        RawXClient.writeIntAt(request, 28, 0x2 | 0x40);
        RawXClient.writeIntAt(request, 32, WHITE_RGB);
        RawXClient.writeIntAt(request, 36, backingStore);
        client.sendRequest(request);
        client.selectInput(lower, EXPOSURE_MASK);
        client.mapWindow(lower);
        client.flush();
        quiet();
        return lower;
    }

    /**
     * Covers the middle of {@code lower} with a black window and takes it away again, leaving
     * whatever the server did about the reveal for the caller to read.
     */
    private int coverAndUncover(int lower) throws Exception {
        return coverAndUncover(lower, -1);
    }

    private int coverAndUncover(int lower, int gc) throws Exception {
        int upper = client.allocateId();
        client.createWindow(upper, client.root(), 50, 50, 100, 100, BLACK_RGB);
        client.mapWindow(upper);
        client.flush();
        quiet();
        // Read through the windows themselves: a window's own pixels are what GetImage answers for
        // it, and the root's answer stops where its children begin - which is the whole point of the
        // region this server clips every drawable by.
        assertEquals(BLACK, client.pixelAt(upper, 50, 50), "the covering window shows");

        if (gc >= 0) {
            // The window draws while it is covered: clipped to the part that shows, which is the
            // whole reason the saved pixels stop being its picture.
            client.fillRectangle(lower, gc, 0, 0, 200, 200);
            client.flush();
            quiet();
        }

        client.unmapWindow(upper);
        client.flush();
        return upper;
    }

    /** Reads and drops whatever is left, so a test only reads what its own step produced. */
    private void quiet() throws Exception {
        client.readTimeoutMillis(QUIET_MILLIS);
        while (client.readEvent() != null) {
            // Quiet is the end of it.
        }
        client.readTimeoutMillis(5000);
    }
}
