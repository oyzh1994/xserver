package io.github.oyzh1994.xserver.draw;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.resource.XWindow;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A top level's own picture, built from the windows' stores - the piece a frame per top level needs.
 *
 * <p>Two things are pinned here, and the second is the one the item was reverted for the first time.
 * One: what the composite produces is what the screen shows inside the window's rectangle, pixel for
 * pixel, so a frame is not a different rendering of the application. Two: a **sibling that overlaps
 * the window, completely or partly, is not in it** - the composite comes from the stores, not from
 * the screen's rectangle, so an overlapping dialog stays in its own frame.
 */
class WindowSubtreeTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 84;

    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        // --single-window so the client's windows are on the screen these tests read pixels from.
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

    @Test
    void theCompositeIsWhatTheScreenShows() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int parent = client.allocateId();
            client.createWindow(parent, client.root(), 0, 0, 300, 200);
            client.mapWindow(parent);
            int parentGc = client.createGC(client.allocateId(), client.root(), RED);
            client.fillRectangle(parent, parentGc, 0, 0, 300, 200);
            int child = client.allocateId();
            client.createWindow(child, parent, 50, 50, 100, 100);
            client.mapWindow(child);
            int childGc = client.createGC(client.allocateId(), client.root(), BLUE);
            client.fillRectangle(child, childGc, 0, 0, 100, 100);
            sync(client, parent);

            XWindow top = window(parent);
            Framebuffer picture = picture(top);
            assertEquals(0, differences(picture, top),
                    "the top level's own picture is what the screen shows inside its rectangle");
        }
    }

    @Test
    void anOverlappingSiblingIsNotInThePicture() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int parent = client.allocateId();
            client.createWindow(parent, client.root(), 0, 0, 300, 200);
            client.mapWindow(parent);
            int parentGc = client.createGC(client.allocateId(), client.root(), RED);
            client.fillRectangle(parent, parentGc, 0, 0, 300, 200);
            sync(client, parent);

            // The dialog, over the right-hand half of the parent - a sibling, not a child, which is
            // what an AWT dialog is: a top level of its own, placed over the window it came from.
            int dialog = client.allocateId();
            client.createWindow(dialog, client.root(), 200, 50, 100, 100, 0x00FF00);
            client.mapWindow(dialog);
            sync(client, dialog);
            waitForGreen(250, 100);

            XWindow top = window(parent);
            Framebuffer picture = picture(top);
            // Where the dialog is, the parent's frame has the parent's own pixels.
            assertEquals(RED, picture.pixel(250, 100), "the overlapping sibling is not in the picture"
                    + " - which is what a rectangle of the screen would have shown");
            assertEquals(RED, picture.pixel(10, 10), "and its own pixels are still its own");
        }
    }

    // ------------------------------------------------------------------

    private static XWindow window(int id) {
        return (XWindow) server.resources().get(id);
    }

    /** A top level's picture, as a frame would keep it: the window's own size, at its own origin. */
    private static Framebuffer picture(XWindow top) {
        Framebuffer picture = new Framebuffer(top.width(), top.height(), 0);
        WindowSubtree.render(top, picture, List.of(new Rectangle(0, 0, top.width(), top.height())));
        return picture;
    }

    /** How many pixels of the picture differ from the screen over the window's rectangle. */
    private static int differences(Framebuffer picture, XWindow top) {
        Framebuffer screen = server.screen().framebuffer();
        int differing = 0;
        for (int y = 0; y < top.height(); y++) {
            for (int x = 0; x < top.width(); x++) {
                if (picture.pixel(x, y) != screen.pixel(top.absoluteX() + x, top.absoluteY() + y)) {
                    differing++;
                }
            }
        }
        return differing;
    }

    private static void sync(RawXClient client, int window) throws IOException {
        client.geometry(window);
    }

    private static void waitForGreen(int x, int y) {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (server.screen().framebuffer().pixel(x, y) == GREEN) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertEquals(GREEN, server.screen().framebuffer().pixel(x, y), "the dialog should be showing");
    }
}
