package io.github.oyzh1994.xserver.test;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the host window shows and does for the client behind it: the title and icon that client
 * publishes, and the size the user drags it to.
 *
 * <p>These are the three things that make a forwarded application a window rather than a picture of
 * one - a window that is still called "xserver", has no icon, and cannot be resized is one the user
 * has to guess at.
 */
class HostWindowTest {

    /** A display nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 97;

    private static final int STRUCTURE_NOTIFY = 1 << 17;
    private static final int CONFIGURE_NOTIFY = 22;

    @Test
    void theHostWindowShowsTheTitleTheClientPublishes() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 100);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            assertEquals(null, backend.title, "a client that publishes no title renames nothing");

            // The modern property, UTF-8 by definition: a title that is not ASCII has to survive.
            client.setStringProperty(window, client.internAtom("_NET_WM_NAME"),
                    client.internAtom("UTF8_STRING"), "终端 — /tmp");
            client.flush();
            waitUntil(() -> "终端 — /tmp".equals(backend.title),
                    "the host window should have taken the client's title, saw " + backend.title);

            // Retitling is what a terminal does on every directory change, and the window follows.
            client.setStringProperty(window, client.internAtom("_NET_WM_NAME"),
                    client.internAtom("UTF8_STRING"), "别的东西");
            client.flush();
            waitUntil(() -> "别的东西".equals(backend.title), "a retitle should arrive too");
        } finally {
            server.stop();
        }
    }

    @Test
    void theHostWindowShowsTheTitleACommandLineClientPublishes() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 100);
            client.mapWindow(window);
            // xclock and xterm publish the old property only, which is the one Xlib sets by default.
            client.setStringProperty(window, client.internAtom("WM_NAME"),
                    client.internAtom("STRING"), "xclock");
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitUntil(() -> "xclock".equals(backend.title),
                    "WM_NAME alone should still name the host window, saw " + backend.title);
        } finally {
            server.stop();
        }
    }

    @Test
    void theHostWindowShowsTheIconTheClientPublishes() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 100);
            client.mapWindow(window);
            // Two sizes, the small one first: a client is free to publish several and the window
            // manager picks, and the larger one is the one that scales down without going blurry.
            client.setCardinalProperty(window, client.internAtom("_NET_WM_ICON"),
                    client.internAtom("CARDINAL"),
                    1, 1, 0xFF112233,
                    2, 2, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitUntil(() -> backend.iconArgb != null, "the host window took no icon");
            assertEquals(2, backend.iconWidth, "the larger of the two icons should win");
            assertEquals(2, backend.iconHeight);
            assertArrayEquals(new int[]{0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF},
                    backend.iconArgb, "the pixels should arrive in the order they were published");
        } finally {
            server.stop();
        }
    }

    @Test
    void resizingTheHostWindowResizesTheClientsWindow() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 100);
            client.selectInput(window, STRUCTURE_NOTIFY);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitUntil(() -> new Rectangle(0, 0, 200, 100).equals(backend.viewport),
                    "the window opens at the size the client's window is");

            // The user drags the host window's edge; the client's window is what has to change.
            backend.input.windowResized(320, 240);

            byte[] configure = configureOfWidth(client, 320);
            assertNotNull(configure, "the client was never told its window had been resized");
            assertEquals(240, RawXClient.readShort(configure, 22),
                    "the client should have been told the new height as well");
            // And the frame follows, since the window it frames is now that size.
            waitUntil(() -> new Rectangle(0, 0, 320, 240).equals(backend.viewport),
                    "the host window should end up the size the client's window became, saw "
                            + backend.viewport);
        } finally {
            server.stop();
        }
    }

    /**
     * The screen is bigger than the window's own corner of it, and a window that centres itself on
     * the root - which is what a toolkit does - starts away from the origin. Dragging the frame then
     * has to be understood as "make this much of the client visible", not "make the client's window
     * this big": the two differ by the part of the window that hangs off the screen, and using the
     * second shrinks the client's window in the dimension the user did not touch.
     */
    @Test
    void aDragIsMeasuredInWhatTheFrameCanShow() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            // EasyShell's own geometry, measured: 1300x700 centred on a 1280x800 root.
            client.createWindow(window, client.root(), -50, 20, 1300, 700);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitForFrame(backend, new Rectangle(0, 20, 1250, 700));

            // Taller by fifty, which fits. Only the height was dragged, so only the height changes.
            backend.input.windowResized(1250, 750);
            waitForFrame(backend, new Rectangle(0, 20, 1250, 750));
        } finally {
            server.stop();
        }
    }

    /**
     * A drag that asks for more than the screen can show stops at the most there is.
     *
     * <p>It must not overshoot and then be pulled back to something smaller than the frame already
     * was: that reads as the window jumping away from the pointer. The window's own origin counts
     * here too - a window twenty pixels down the screen has twenty pixels less of itself that can
     * ever be shown.
     */
    @Test
    void aDragPastTheScreenStopsAtTheMostThatCanBeShown() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), -50, 20, 1300, 700);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitForFrame(backend, new Rectangle(0, 20, 1250, 700));

            backend.input.windowResized(1250, 4000);
            waitForFrame(backend, new Rectangle(0, 20, 1250, backend.screen.height() - 20));
        } finally {
            server.stop();
        }
    }

    /**
     * A menu must not resize the frame it is opened in.
     *
     * <p>A context menu is an override-redirect top-level window like any other, and the frame is
     * sized to the union of what the client has mapped - so a menu counted as content makes the whole
     * window grow the moment one is opened and shrink back the moment it closes, which is a window
     * that jumps every time the user right-clicks.
     */
    @Test
    void aMenuDoesNotResizeTheFrameItOpensIn() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), -50, 20, 1300, 700);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitForFrame(backend, new Rectangle(0, 20, 1250, 700));

            // A menu below the window's bottom edge, but still on the screen: exactly the case where
            // counting it as content would make the frame grow.
            int menu = client.allocateId();
            client.createWindow(menu, client.root(), 100, 700, 200, 60);
            client.setOverrideRedirect(menu);
            client.mapWindow(menu);
            client.flush();
            Thread.sleep(300);
            assertEquals(new Rectangle(0, 20, 1250, 700), backend.viewport,
                    "a menu opening grew the frame around it");

            client.unmapWindow(menu);
            client.flush();
            Thread.sleep(300);
            assertEquals(new Rectangle(0, 20, 1250, 700), backend.viewport,
                    "a menu closing changed the frame around it");
        } finally {
            server.stop();
        }
    }

    /**
     * The window is told the largest area it may be given, so the platform can stop the user
     * dragging it past that. Without it the drag runs on into area the client's windows cannot fill:
     * the frame is bigger than its content, and the next time the server says how big the content is
     * the window is pulled back to it - away from the pointer that is still dragging.
     */
    @Test
    void theWindowIsToldTheLargestAreaItCanBeGiven() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), -50, 20, 1300, 700);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitForFrame(backend, new Rectangle(0, 20, 1250, 700));

            // The screen is the limit, less the twenty pixels of it the client's window starts below.
            waitUntil(() -> new Dimension(1280, 780).equals(backend.maxClientArea),
                    "the window should have been told how large it may be, was told "
                            + backend.maxClientArea);

            // A client window that moves to the top of the screen has the whole screen to grow into,
            // and the limit follows it there.
            client.configureWindow(window, 0, 0, 1300, 700);
            client.flush();
            waitUntil(() -> new Dimension(1280, 800).equals(backend.maxClientArea),
                    "the limit should follow the content up the screen, was told "
                            + backend.maxClientArea);
        } finally {
            server.stop();
        }
    }

    @Test
    void aResizeIsCappedAtTheScreen() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 100);
            client.selectInput(window, STRUCTURE_NOTIFY);
            client.mapWindow(window);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the window should have opened");
            TestBackend backend = server.windows.get(0);
            waitUntil(() -> new Rectangle(0, 0, 200, 100).equals(backend.viewport), "no window");

            // The framebuffer is not resized with the window, so a drag past the screen cannot be
            // honoured: the client's window stops at the screen rather than growing into pixels
            // that do not exist.
            backend.input.windowResized(backend.screen.width() + 500, 100);

            assertNotNull(configureOfWidth(client, backend.screen.width()),
                    "the client's window should have been resized to the screen, not past it");
        } finally {
            server.stop();
        }
    }

    /**
     * Reads events until the client is told its window is this wide.
     *
     * <p>Other notices arrive first - the window was mapped, and something has to be drawn - so the
     * one that matters is picked out rather than assumed to be next.
     */
    private static byte[] configureOfWidth(RawXClient client, int width) throws Exception {
        for (int i = 0; i < 20; i++) {
            byte[] event = client.readEvent();
            if (event == null) {
                return null;
            }
            if ((event[0] & 0x7F) == CONFIGURE_NOTIFY
                    && RawXClient.readShort(event, 20) == width) {
                return event;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // The server under test
    // ------------------------------------------------------------------

    /** Runs a server whose windows are backends a test can inspect instead of real windows. */
    private static final class Server {

        final XServer xserver;
        final List<TestBackend> windows = new CopyOnWriteArrayList<>();

        Server(int display) throws Exception {
            this.xserver = new XServer(ServerOptions.parse(new String[]{"--display", ":" + display,
                    "--backend", "headless", "--no-unix"}));
            xserver.setBackendFactory(() -> {
                TestBackend backend = new TestBackend();
                windows.add(backend);
                return backend;
            });
            xserver.start();
            xserver.startEventLoop();
        }

        void stop() {
            xserver.stop();
        }
    }

    /** A window that records what it is asked to show, and can be resized like a real one. */
    private static final class TestBackend implements DisplayBackend {
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile Screen screen;
        volatile InputSink input;
        volatile Rectangle viewport = new Rectangle();
        volatile String title;
        volatile int[] iconArgb;
        volatile int iconWidth;
        volatile int iconHeight;
        volatile Dimension maxClientArea;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void start(Screen screen, int displayNumber, InputSink input) {
            this.screen = screen;
            this.input = input;
        }

        @Override
        public void setViewport(Rectangle viewport) {
            this.viewport = new Rectangle(viewport);
        }

        @Override
        public void present(Screen screen, Rectangle damage, CursorSprite cursor) {
        }

        @Override
        public void setWindowTitle(String title) {
            this.title = title;
        }

        @Override
        public void setMaxClientArea(int width, int height) {
            this.maxClientArea = new Dimension(width, height);
        }

        @Override
        public void setWindowIcon(int[] argb, int width, int height) {
            this.iconArgb = argb;
            this.iconWidth = width;
            this.iconHeight = height;
        }

        @Override
        public void stop() {
            stopped.set(true);
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    private static void waitUntil(BooleanSupplier condition, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }

    /**
     * Waits for the frame to show a rectangle, and says what it shows instead when it does not.
     *
     * <p>Separate from {@link #waitUntil} because the size a test is waiting for is only meaningful
     * in the failure message: building that message at the call site reads the frame before anything
     * has had a chance to happen, and reports the size the test started from.
     */
    private static void waitForFrame(TestBackend backend, Rectangle expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(backend.viewport)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the frame should show " + expected + " but shows "
                + backend.viewport);
    }
}
