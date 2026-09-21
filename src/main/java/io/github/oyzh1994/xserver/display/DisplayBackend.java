package io.github.oyzh1994.xserver.display;

import java.awt.Rectangle;

/**
 * Presents a screen's framebuffer in a real window and feeds keyboard and pointer input back into
 * the server.
 *
 * <p>The server always renders into its own pixel buffer; the backend only copies changed regions
 * to the screen and translates platform input events into X keycodes and coordinates. That keeps
 * the protocol implementation independent of the toolkit, and makes headless operation a matter
 * of choosing a different backend.
 */
public interface DisplayBackend {

    /** Receives input translated into X terms. */
    interface InputSink {
        /** Pointer moved to screen coordinates. */
        void pointerMoved(int x, int y);

        /**
         * Button state changed at the given position. {@code button} is the X button number
         * (1 = left, 2 = middle, 3 = right, 4/5 = wheel).
         */
        void buttonChanged(int button, boolean pressed, int x, int y);

        /** A key changed state. {@code keycode} is an X keycode, not a keysym. */
        void keyChanged(int keycode, boolean pressed, int x, int y);

        /** The window gained or lost focus; the server tracks this for the input focus. */
        void focusChanged(boolean focused);
    }

    /** Human readable name, used in logs. */
    String name();

    /**
     * Opens the window and starts delivering input. Called once from the server's start method,
     * which must return before any input arrives.
     */
    void start(Screen screen, int displayNumber, InputSink input);

    /**
     * Copies the damaged region of the framebuffer to the screen, then draws the pointer sprite
     * over it.
     *
     * <p>{@code cursor} is null when there is nothing to draw. It is passed with the frame rather
     * than painted into the framebuffer so that a frame always shows the pointer at the position it
     * had when the frame was taken, and so that what the clients drew is never covered by it.
     */
    void present(Screen screen, Rectangle damage, CursorSprite cursor);

    /** Tears the window down. */
    void stop();

    /** True when the backend reports the window has been closed by the user. */
    boolean isClosed();

    /** True when this backend draws anything at all. */
    default boolean isHeadless() {
        return false;
    }
}
