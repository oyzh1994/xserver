package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.draw.Framebuffer;

/**
 * Pixels a display backend can show, with the size and scale that go with them.
 *
 * <p>A backend used to be handed a {@link Screen}, and that was more than it needs: of the whole
 * screen it reads only the framebuffer, its size and the scale factor - a screen's visuals, root
 * window and depth mean nothing to a window on the host's desktop. Naming the four things it does
 * use is what lets the same backends show <b>a top level window's own picture</b> instead of the
 * screen (C8, one host window per top level): a frame is a surface of its own, and nothing about it
 * is a screen.
 */
public interface Surface {

    /** The pixels. A backend reads these directly and is told which part of them changed. */
    Framebuffer framebuffer();

    int width();

    int height();

    /** How many framebuffer pixels one logical pixel is worth - a HiDPI screen's 2, else 1. */
    default int scale() {
        return 1;
    }
}
