package io.github.oyzh1994.xserver.extension;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composite: a compositor takes a window's pixels and puts them on the screen itself.
 *
 * <p>The rule these tests exist for is the one this server gets wrong by default, and silently: every
 * window here draws into a store of its own and is composited from it onto the screen, so a
 * redirected window keeps drawing to the screen unless something stops it. A reference does not -
 * measured, a redirected window repainted is new in its pixmap and old on the screen
 * ({@code tools/CompositeProbe}) - and a compositor that saw both would draw the window twice.
 *
 * <p>Each request is built by hand rather than through a {@code RawXClient} helper, which is the
 * shape the probes use too: the layouts come from the generated protocol classes and are the ones the
 * probe confirmed against a reference.
 */
class CompositeTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 84;

    private static final int QUERY_VERSION = 0;
    private static final int REDIRECT_WINDOW = 1;
    private static final int UNREDIRECT_WINDOW = 3;
    private static final int NAME_WINDOW_PIXMAP = 6;
    private static final int GET_OVERLAY_WINDOW = 7;
    private static final int AUTOMATIC = 0;

    private static final int GREEN = 0x00AA22;
    private static final int BLUE = 0x0000FF;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--single-window", "--no-unix"}));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * The whole of the extension's behaviour, in one test: a redirected window's drawing goes into
     * the pixmap the server named for it and **not** onto the screen, and the screen keeps what was
     * there. Fails without the guard in {@code XServer.compositeOwnPixels}.
     */
    @Test
    void aRedirectedWindowDrawsIntoItsPixmapAndNotOntoTheScreen() throws Exception {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = opcode(client);
            int window = client.allocateId();
            client.createWindow(window, client.root(), 20, 20, 100, 80);
            client.mapWindow(window);
            int green = client.createGC(client.allocateId(), client.root(), GREEN);
            client.fillRectangle(window, green, 0, 0, 100, 80);
            client.geometry(window);                    // sync

            int pixmap = client.allocateId();
            redirect(client, opcode, REDIRECT_WINDOW, window);
            nameWindowPixmap(client, opcode, window, pixmap);
            assertEquals(0xFF000000 | GREEN, client.pixelAt(pixmap, 50, 40),
                    "the named pixmap holds what the window had drawn");

            int blue = client.createGC(client.allocateId(), client.root(), BLUE);
            client.fillRectangle(window, blue, 0, 0, 100, 80);
            client.geometry(window);

            assertEquals(0xFF000000 | BLUE, client.pixelAt(pixmap, 50, 40),
                    "and it follows what the window draws while it is redirected");
            assertEquals(0xFF000000 | GREEN, screenPixel(50, 60),
                    "while the screen keeps what was there - a compositor draws the window, not this "
                            + "server");

            unredirect(client, opcode, window);
            client.geometry(window);
            waitForScreenColor(50, 60, 0xFF000000 | BLUE,
                    "and unredirecting puts the window back on the screen");
        }
    }

    /**
     * One overlay window, the size of the screen, above the windows - the drawable a compositor puts
     * its result on. Measured against a reference, which answers a screen-sized window of its own.
     */
    @Test
    void theOverlayWindowIsTheSizeOfTheScreen() throws Exception {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            byte[] request = new byte[8];
            request[0] = (byte) opcode(client);
            request[1] = (byte) GET_OVERLAY_WINDOW;
            RawXClient.writeShortAt(request, 2, 2);
            RawXClient.writeIntAt(request, 4, client.root());
            client.sendRequest(request);
            int overlay = RawXClient.readInt(client.readReply(), 8);

            assertTrue(overlay != 0, "a compositor needs somewhere to draw its result");
            int[] geometry = client.geometry(overlay);
            assertEquals(server.screen().width(), geometry[2], "as wide as the screen");
            assertEquals(server.screen().height(), geometry[3], "and as tall");
            assertEquals(0, geometry[0], "at its origin");
            assertEquals(0, geometry[1], "on both axes");
        }
    }

    // ------------------------------------------------------------------

    private static void redirect(RawXClient client, int opcode, int minor, int window)
            throws IOException {
        byte[] request = new byte[12];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 3);
        RawXClient.writeIntAt(request, 4, window);
        request[8] = (byte) AUTOMATIC;
        client.sendRequest(request);
        client.geometry(window);
    }

    private static void unredirect(RawXClient client, int opcode, int window) throws IOException {
        redirect(client, opcode, UNREDIRECT_WINDOW, window);
    }

    private static void nameWindowPixmap(RawXClient client, int opcode, int window, int pixmap)
            throws IOException {
        byte[] request = new byte[12];
        request[0] = (byte) opcode;
        request[1] = (byte) NAME_WINDOW_PIXMAP;
        RawXClient.writeShortAt(request, 2, 3);
        RawXClient.writeIntAt(request, 4, window);
        RawXClient.writeIntAt(request, 8, pixmap);
        client.sendRequest(request);
        client.geometry(window);
    }

    private static int opcode(RawXClient client) throws IOException {
        int[] info = client.queryExtensionInfo("Composite");
        assertTrue(info[0] != 0, "the extension has to be advertised");
        // The version is the server's and not the lower of the two, which the probe measured against
        // a reference: a client asking for 0.2 is answered 0.4.
        byte[] request = new byte[12];
        request[0] = (byte) info[1];
        request[1] = (byte) QUERY_VERSION;
        RawXClient.writeShortAt(request, 2, 3);
        RawXClient.writeIntAt(request, 4, 0);
        RawXClient.writeIntAt(request, 8, 4);
        client.sendRequest(request);
        byte[] reply = client.readReply();
        assertEquals(0, RawXClient.readInt(reply, 8), "Composite 0.x");
        assertEquals(4, RawXClient.readInt(reply, 12), "the server's own minor version");
        return info[1];
    }

    private static int screenPixel(int x, int y) {
        return server.screen().framebuffer().pixel(x, y) | 0xFF000000;
    }

    private static void waitForScreenColor(int x, int y, int expected, String what) {
        long deadline = System.currentTimeMillis() + 4000;
        int last = 0;
        while (System.currentTimeMillis() < deadline) {
            last = screenPixel(x, y);
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(expected, last, what + " (at " + x + "," + y + ")");
    }
}
