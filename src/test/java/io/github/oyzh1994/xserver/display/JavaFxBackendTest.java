package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.JavaFxBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        try {
            Class.forName("javafx.scene.image.WritableImage");
        } catch (Throwable e) {
            // No JavaFX on this machine: the server falls back to Swing, and so does this test.
            org.junit.jupiter.api.Assumptions.abort("JavaFX is not available");
        }

        Screen screen = new Screen(0, WIDTH, HEIGHT,
                List.of(VisualInfo.trueColor24(0x200)), 24, 0x200);
        Framebuffer framebuffer = screen.framebuffer();

        JavaFxBackend backend = new JavaFxBackend("test");
        AtomicReference<DisplayBackend.InputSink> sink = new AtomicReference<>();
        backend.start(screen, 5, new NoopInput());

        // Paint a recognisable pattern, then present the whole screen.
        framebuffer.fillRect(0, 0, WIDTH, HEIGHT, 0xFF102030);
        framebuffer.fillRect(10, 10, 8, 8, 0xFFEEDDCC);
        backend.present(screen, new Rectangle(0, 0, WIDTH, HEIGHT));

        int sampled = readPixel(backend, 12, 12);
        assertEquals(0xFFEEDDCC, sampled,
                "the pattern drawn into the framebuffer should be readable from the JavaFX image");
        assertEquals(0xFF102030, readPixel(backend, 0, 0),
                "the background should have reached the JavaFX image too");

        backend.stop();
    }

    /** Reads a pixel on the FX thread, which is the only thread allowed to touch the image. */
    private static int readPixel(JavaFxBackend backend, int x, int y) throws Exception {
        AtomicReference<Integer> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                PixelReader reader = backend.image().getPixelReader();
                result.set(reader.getArgb(x, y));
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the FX thread did not answer in time");
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
