package io.github.oyzh1994.xserver.handler;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What a window paints stays inside the part of it that is visible.
 *
 * <p>Windows draw straight into one framebuffer, so a window that ignores the window on top of it
 * erases it - and since the covered window is never told to draw again, it stays erased. That is
 * what makes a dialog vanish when the window underneath repaints, with no client involved.
 */
public class WindowObscuringTest {

    private static final int LOWER = 0xFF203040;
    private static final int UPPER = 0xFF807060;
    private static final int ROOT_COLOUR = 0xFF000000;

    private static XWindow window(Screen screen, XWindow parent, int id, int x, int y, int w,
                                  int h, int colour) {
        XWindow window = new XWindow(id, null, screen, parent, 24, XWindow.INPUT_OUTPUT,
                screen.rootVisual(), x, y, w, h, 0);
        window.setBackgroundArgb(colour);
        window.setBorderArgb(colour);
        return window;
    }

    @Test
    void aWindowUnderneathDoesNotPaintOverTheOneOnTop() {
        XServer server = new XServer(ServerOptions.parse(
                new String[]{"--display", "97", "--backend", "headless"}));
        Screen screen = server.screen();
        Framebuffer framebuffer = screen.framebuffer();
        XWindow lower = window(screen, server.rootWindow(), 0x400001, 10, 10, 60, 60, LOWER);
        server.resources().add(lower);
        server.windows().mapWindow(lower);
        XWindow upper = window(screen, server.rootWindow(), 0x400002, 40, 40, 20, 20, UPPER);
        server.resources().add(upper);
        server.windows().mapWindow(upper);

        // The window on top has its pixels ...
        assertEquals(UPPER, framebuffer.pixel(45, 45));
        // ... and the one underneath repainting the whole of itself leaves them alone.
        server.windows().paintBackground(lower, lower.absoluteBounds());

        assertEquals(UPPER, framebuffer.pixel(45, 45), "the covered square belongs to the window "
                + "on top and must survive the one below repainting");
        assertEquals(LOWER, framebuffer.pixel(15, 15), "the visible part is the lower window's");
    }

    @Test
    void unmappingAWindowGivesItsAreaBackToTheWindowUnderneath() {
        XServer server = new XServer(ServerOptions.parse(
                new String[]{"--display", "96", "--backend", "headless"}));
        Screen screen = server.screen();
        Framebuffer framebuffer = screen.framebuffer();
        XWindow lower = window(screen, server.rootWindow(), 0x400001, 10, 10, 60, 60, LOWER);
        server.resources().add(lower);
        server.windows().mapWindow(lower);
        XWindow upper = window(screen, server.rootWindow(), 0x400002, 40, 40, 20, 20, UPPER);
        server.resources().add(upper);
        server.windows().mapWindow(upper);
        assertEquals(UPPER, framebuffer.pixel(45, 45));

        server.windows().unmapWindow(upper, true);

        assertEquals(LOWER, framebuffer.pixel(45, 45),
                "the uncovered square is repainted with the lower window's background");
        assertEquals(ROOT_COLOUR, framebuffer.pixel(70, 70), "the root shows where nothing is left");
        assertNotEquals(UPPER, framebuffer.pixel(45, 45));
    }

    @Test
    void aNewWindowOnTopKeepsWhatItWasDrawnWith() {
        XServer server = new XServer(ServerOptions.parse(
                new String[]{"--display", "95", "--backend", "headless"}));
        Screen screen = server.screen();
        Framebuffer framebuffer = screen.framebuffer();
        XWindow lower = window(screen, server.rootWindow(), 0x400001, 10, 10, 60, 60, LOWER);
        server.resources().add(lower);
        server.windows().mapWindow(lower);

        // The root paints its own background over everything, as an expose would, and the window on
        // top is not touched by it.
        XWindow upper = window(screen, server.rootWindow(), 0x400002, 40, 40, 20, 20, UPPER);
        server.resources().add(upper);
        server.windows().mapWindow(upper);
        server.windows().paintBackground(server.rootWindow(), server.rootWindow().absoluteBounds());

        assertEquals(UPPER, framebuffer.pixel(45, 45), "the root must not paint over its children");
        assertEquals(LOWER, framebuffer.pixel(15, 15));
    }
}
