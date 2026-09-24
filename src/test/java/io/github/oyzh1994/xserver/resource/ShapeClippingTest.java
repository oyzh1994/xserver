package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.extension.ShapeExtension;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bounding shape takes pixels away from a window, so drawing into it stays inside the shape.
 *
 * <p>Only the input shape used to be honoured, which made SHAPE look implemented: a client could set
 * a shape, hit-test through it, ask for its extents - and the window's corners were still painted.
 * That is the whole point of the extension on a server with no compositor: a rounded popup, a shaped
 * splash, anything that is not a rectangle, reaches the screen as its shape and nothing else.
 *
 * <p>A bounding shape <em>removes</em> pixels rather than clipping on top of them, and it applies to
 * the window's children too: a child that falls outside its parent's shape is not on screen either.
 * Both halves are what these tests pin.
 */
class ShapeClippingTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 87;

    /** The colour the client fills with, and the background a window shows where the fill cannot land. */
    private static final int FILL = 0x00FF00;
    private static final int BACKGROUND = Screen.DEFAULT_BACKGROUND_ARGB & 0xFFFFFF;

    private static final int KIND_BOUNDING = 0;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        // --single-window so the client's windows are on the screen this test reads pixels from: in
        // the default mode every connection gets a screen and framebuffer of its own, and the
        // master's framebuffer would be empty however well the client drew.
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix", "--single-window"}));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Sets a window's bounding shape through the extension the requests would reach anyway.
     *
     * <p>In process rather than over the wire on purpose: what the shape <em>does</em> is what these
     * tests are for, and the wire encoding of {@code ShapeRectangles} has its own question to answer
     * first - the hand-written request in `RawXClient` is refused as a bad request by this server,
     * which is worth chasing on its own (see the item's notes in `docs/backlog.md`).
     */
    private static void shape(int windowId, Rectangle rectangle) {
        XWindow window = (XWindow) server.resources().get(windowId);
        new ShapeExtension().setShape(server, window, KIND_BOUNDING, List.of(rectangle), 0, 0);
    }

    /**
     * Nothing is synchronous between this thread and the server's: a request that is answered proves
     * everything sent before it has been processed, which is what a framebuffer assertion needs.
     */
    private static void sync(RawXClient client, int window) throws IOException {
        client.queryTree(window);
    }

    @Test
    void drawingStaysInsideTheShape() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            int gc = client.allocateId();
            client.createWindow(window, client.root(), 100, 100, 40, 40);
            sync(client, window);                       // the server must have made it first
            shape(window, new Rectangle(10, 10, 10, 10));
            client.mapWindow(window);
            client.createGC(gc, client.root(), FILL);

            client.fillRectangle(window, gc, 0, 0, 40, 40);
            sync(client, window);

            Screen screen = server.screen();
            assertEquals(FILL, screen.framebuffer().pixel(115, 115) & 0xFFFFFF,
                    "a point inside the shape is painted");
            assertEquals(BACKGROUND, screen.framebuffer().pixel(102, 102) & 0xFFFFFF,
                    "and a corner the shape took away is not");
            assertEquals(BACKGROUND, screen.framebuffer().pixel(138, 138) & 0xFFFFFF,
                    "nor the far corner");
        }
    }

    @Test
    void aShapeSetAfterMappingHandsTheAreaBack() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            int gc = client.allocateId();
            client.createWindow(window, client.root(), 300, 100, 40, 40);
            client.mapWindow(window);
            sync(client, window);
            client.createGC(gc, client.root(), FILL);

            // Painted unshaped, so the corners are the client's paint to begin with.
            client.fillRectangle(window, gc, 0, 0, 40, 40);
            sync(client, window);
            Screen screen = server.screen();
            assertEquals(FILL, screen.framebuffer().pixel(302, 102) & 0xFFFFFF,
                    "an unshaped window takes the paint everywhere");

            // Shaping it afterwards has to take those pixels away again, not leave them painted:
            // the area belongs to whatever is underneath once the window no longer covers it.
            shape(window, new Rectangle(10, 10, 10, 10));
            sync(client, window);

            assertEquals(BACKGROUND, screen.framebuffer().pixel(302, 102) & 0xFFFFFF,
                    "the corner the shape took away goes back to what is underneath");
            assertEquals(FILL, screen.framebuffer().pixel(315, 115) & 0xFFFFFF,
                    "and what is still inside the shape keeps the client's paint");
        }
    }

    @Test
    void aChildOutsideItsParentsShapeIsNotOnScreen() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int parent = client.allocateId();
            int child = client.allocateId();
            int gc = client.allocateId();
            client.createWindow(parent, client.root(), 200, 300, 40, 40);
            sync(client, parent);
            shape(parent, new Rectangle(0, 0, 20, 40));
            client.mapWindow(parent);
            client.createWindow(child, parent, 25, 0, 10, 10);
            client.mapWindow(child);

            client.createGC(gc, client.root(), FILL);
            client.fillRectangle(child, gc, 0, 0, 10, 10);
            sync(client, child);

            Screen screen = server.screen();
            assertEquals(BACKGROUND, screen.framebuffer().pixel(230, 305) & 0xFFFFFF,
                    "the child is outside the parent's shape, so its paint does not land");
        }
    }
}