package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.JavaFxBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import javafx.application.Platform;
import javafx.scene.Cursor;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the framebuffer actually reaches the JavaFX image.
 *
 * <p>A window cannot be observed on a machine without a visible display, so the check is done by
 * reading the image back instead: if the pixels arrive here, the only thing left unverified is the
 * compositor, which is the platform's business.
 */
class JavaFxBackendTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    @Test
    void presentsFramebufferToImage() throws Exception {
        skipWithoutJavaFx();

        Screen screen = new Screen(0, WIDTH, HEIGHT,
                List.of(VisualInfo.trueColor24(0x200)), 24, 0x200);
        Framebuffer framebuffer = screen.framebuffer();

        JavaFxBackend backend = new JavaFxBackend("test");
        AtomicReference<DisplayBackend.InputSink> sink = new AtomicReference<>();
        backend.start(screen, 5, new NoopInput());
        // Checked here rather than in a test of its own: stop() exits the toolkit, and a JVM gets
        // one JavaFX runtime, so only one window can be opened per test run.
        assertWindowLeavesThePointerAlone(backend);

        // Paint a recognisable pattern, then present the whole screen.
        framebuffer.fillRect(0, 0, WIDTH, HEIGHT, 0xFF102030);
        framebuffer.fillRect(10, 10, 8, 8, 0xFFEEDDCC);
        backend.present(screen, new Rectangle(0, 0, WIDTH, HEIGHT), null);

        int sampled = readPixel(backend, 12, 12);
        assertEquals(0xFFEEDDCC, sampled,
                "the pattern drawn into the framebuffer should be readable from the JavaFX image");
        assertEquals(0xFF102030, readPixel(backend, 0, 0),
                "the background should have reached the JavaFX image too");

        backend.stop();
    }

    /**
     * Hiding the platform pointer is a loan, not something the window keeps.
     *
     * <p>On macOS {@link Cursor#NONE} is a process-wide {@code [NSCursor hide]}, undone only when the
     * window's cursor changes away from NONE - so a window that hides the pointer without a way back
     * takes it off the desktop and every other application. The pointer is only the server's while
     * it is over the window and the window is the active one; anywhere else the platform's arrow
     * stays, which is what this pins down.
     */
    @Test
    void hostCursorHidesThePlatformPointerOnlyOverAnActiveWindow() {
        skipWithoutJavaFx();

        assertSame(Cursor.NONE, JavaFxBackend.hostCursor(true, true),
                "the sprite the server draws is the only pointer over an active window");
        assertSame(Cursor.DEFAULT, JavaFxBackend.hostCursor(true, false),
                "an inactive window has no claim on the pointer");
        assertSame(Cursor.DEFAULT, JavaFxBackend.hostCursor(false, true),
                "the pointer outside the window belongs to the platform");
        assertSame(Cursor.DEFAULT, JavaFxBackend.hostCursor(false, false),
                "nothing is hidden when the pointer is elsewhere and the window is inactive");
    }

    /**
     * Opening the window leaves the platform pointer alone, for the reason above.
     *
     * <p>What the window installs is what is read: the platform effect cannot be observed from a
     * test, since a cursor only reaches the platform when a mouse event arrives and a test has no
     * pointer to move.
     */
    private static void assertWindowLeavesThePointerAlone(JavaFxBackend backend) throws Exception {
        Cursor cursor = onFxThread(backend::windowCursor);
        assertSame(Cursor.DEFAULT, cursor,
                "a freshly opened window must not hide the platform pointer");
    }

    private static void skipWithoutJavaFx() {
        try {
            Class.forName("javafx.scene.image.WritableImage");
        } catch (Throwable e) {
            // No JavaFX on this machine: the server falls back to Swing, and so does this test.
            throw new TestAbortedException("JavaFX is not available", e);
        }
    }

    /** Reads a pixel, on the FX thread, which is the only thread allowed to touch the image. */
    private static int readPixel(JavaFxBackend backend, int x, int y) throws Exception {
        return onFxThread(() -> backend.image().getPixelReader().getArgb(x, y));
    }

    /** Runs a query on the FX thread and hands back what it returned, or what it threw. */
    private static <T> T onFxThread(Callable<T> query) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(query.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the FX thread did not answer in time");
        Throwable thrown = failure.get();
        if (thrown instanceof Exception e) {
            throw e;
        }
        if (thrown != null) {
            throw new AssertionError(thrown);
        }
        return result.get();
    }

    /** The server only calls back into this when the pointer moves, which the test never does. */
    private static final class NoopInput implements DisplayBackend.InputSink {
        @Override
        public void pointerMoved(int x, int y) {
        }

        @Override
        public void buttonChanged(int button, boolean pressed, int x, int y) {
        }

        @Override
        public void keyChanged(int keycode, boolean pressed, int x, int y) {
        }

        @Override
        public void focusChanged(boolean focused) {
        }
    }
}
