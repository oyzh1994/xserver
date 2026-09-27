package io.github.oyzh1994.xserver.test;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Surface;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One host window per top level (C8), and each of them shows its own window's pixels.
 *
 * <p>A client's screen used to be shown by a single window holding the **union** of everything the
 * client had mapped, and that window showed the screen's pixels - so an application that opened a
 * dialog beside its parent grew and jumped to cover both, and the dialog's content appeared inside
 * the parent's frame as well. The item was built once and reverted for exactly that, and what it
 * needed was for a window's pixels to be its own (B5) and for a frame to be built from them
 * ({@code WindowSubtree}). These tests are the two halves of that: a dialog gets a window of its
 * own, and **neither window contains the other's pixels**.
 */
class PerToplevelWindowTest {

    /** A display number nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 85;

    private static final int RED = 0xFFCC2222;
    private static final int GREEN = 0xFF22CC22;

    @Test
    void eachTopLevelGetsItsOwnHostWindow() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int parent = client.allocateId();
            client.createWindow(parent, client.root(), 0, 0, 400, 300);
            client.mapWindow(parent);
            client.fillRectangle(parent, client.createGC(client.allocateId(), client.root(), RED),
                    0, 0, 400, 300);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the first window should have opened");

            // A dialog over the right-hand half of its parent - a sibling, which is what an AWT
            // dialog is, and what the union frame could not tell apart.
            int dialog = client.allocateId();
            client.createWindow(dialog, client.root(), 250, 100, 200, 150);
            client.mapWindow(dialog);
            client.fillRectangle(dialog, client.createGC(client.allocateId(), client.root(), GREEN),
                    0, 0, 200, 150);
            client.flush();

            waitUntil(() -> server.windows.size() == 2,
                    "the dialog should have a host window of its own, not the parent's grown");
            TestBackend parentFrame = server.windows.get(0);
            TestBackend dialogFrame = server.windows.get(1);
            assertEquals(new Rectangle(0, 0, 400, 300), parentFrame.viewport,
                    "the frame is the window it frames, and not the union of both");
            assertEquals(new Rectangle(250, 100, 200, 150), dialogFrame.viewport);

            waitUntil(() -> parentFrame.frames.get() > 0 && dialogFrame.frames.get() > 0,
                    "both windows should have been shown");
            waitUntil(() -> dialogFrame.screen.framebuffer().pixel(10, 10) == GREEN,
                    "the dialog's own window shows the dialog");

            // The measurement the item turns on: where the dialog covers the parent, the parent's
            // frame still has the parent's pixels. Reading the screen's rectangle here would show
            // the dialog instead, which is what the first attempt did and what it was reverted for.
            assertEquals(RED, parentFrame.screen.framebuffer().pixel(300, 150),
                    "the dialog is not in its parent's frame");
            assertEquals(RED, parentFrame.screen.framebuffer().pixel(10, 10),
                    "and the parent's own pixels are still its own");
            assertEquals(GREEN, dialogFrame.screen.framebuffer().pixel(10, 10),
                    "while the dialog's frame has only the dialog in it");
        } finally {
            server.stop();
        }
    }

    @Test
    void aClosedTopLevelTakesItsFrameWithIt() throws Exception {
        Server server = new Server(DISPLAY);
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int parent = client.allocateId();
            client.createWindow(parent, client.root(), 0, 0, 400, 300);
            client.mapWindow(parent);
            client.flush();
            waitUntil(() -> server.windows.size() == 1, "the first window should have opened");

            int dialog = client.allocateId();
            client.createWindow(dialog, client.root(), 250, 100, 200, 150);
            client.mapWindow(dialog);
            client.flush();
            waitUntil(() -> server.windows.size() == 2, "the dialog should have opened");

            TestBackend dialogFrame = server.windows.get(1);
            client.unmapWindow(dialog);
            client.flush();

            waitUntil(() -> dialogFrame.stopped.get(),
                    "a frame around a window that is gone is a stale picture");
            assertTrue(!server.windows.get(0).stopped.get(), "and the parent's window stays");
        } finally {
            server.stop();
        }
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

    /** A window that records what it is asked to show. */
    private static final class TestBackend implements DisplayBackend {
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicInteger frames = new AtomicInteger();
        volatile Surface screen;
        volatile Rectangle viewport = new Rectangle();

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void start(Surface surface, int displayNumber, InputSink input) {
            this.screen = surface;
        }

        @Override
        public void setViewport(Rectangle viewport) {
            this.viewport = new Rectangle(viewport);
        }

        @Override
        public void present(Surface surface, Rectangle damage, CursorSprite cursor) {
            frames.incrementAndGet();
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
}
