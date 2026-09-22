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

        /**
         * The user resized the host window: the client's own window should become this size.
         *
         * <p>This is a request, not an application. The backend calls it from the toolkit's thread,
         * where taking the server lock and resizing a client's window would deadlock against the
         * display tick waiting on the toolkit; the server applies it from the tick instead, which is
         * also what keeps the framebuffer and the window from ever disagreeing about their size.
         */
        default void windowResized(int width, int height) {
        }
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

    /**
     * Restricts the window to this rectangle of the screen, in framebuffer coordinates.
     *
     * <p>A window that shows one client's own screen only has to show the part of it that client's
     * windows occupy, which is what keeps a small application from arriving in a screen-sized window
     * with black around it. May be called before {@link #start}: the rectangle is remembered and the
     * window opens at that size.
     *
     * <p>Called from the display tick only, never from a request thread.
     */
    default void setViewport(Rectangle viewport) {
    }

    /**
     * Releases whatever the backend holds open for the whole process, once no window is left. The
     * JavaFX toolkit is the reason this exists: it keeps a non-daemon thread alive, so something has
     * to end it, but ending it on the first window that closes would take every other window with
     * it.
     */
    default void releaseToolkit() {
    }

    /**
     * Tells the window whether any client is connected.
     *
     * <p>With no client the server stops sending frames and the window holds the last one, and the
     * host pointer is handed back: it was hidden in favour of the pointer the server draws from the
     * client's cursor, and with no client there is nothing to draw in its place.
     */
    default void setIdle(boolean idle) {
    }

    /**
     * Sets the title the window shows, taken from the client's own window title. The server's
     * configured title is the fallback for a client that publishes none.
     *
     * <p>Called from the display tick only, never from a request thread.
     */
    default void setWindowTitle(String title) {
    }

    /**
     * Sets the icon the window shows, as non-premultiplied ARGB pixels, or clears it when
     * {@code argb} is null.
     *
     * <p>Called from the display tick only, never from a request thread.
     */
    default void setWindowIcon(int[] argb, int width, int height) {
    }

    /**
     * The largest client area this window can be given, which the user must not be able to drag it
     * past.
     *
     * <p>The screen is what it is: a window stretched beyond the area the client's windows can fill
     * leaves the part it grew into showing nothing, and the frame is pulled back to the content the
     * moment the server next says how big that is - a window that jumps away from the pointer that
     * is still dragging it. A window manager states the limit in {@code WM_NORMAL_HINTS} and the
     * platform enforces it; the backend is where that limit has to be applied here, because the
     * toolkit is what owns the window.
     *
     * <p>Called from the display tick only, never from a request thread.
     */
    default void setMaxClientArea(int width, int height) {
    }

    /** Tears the window down. */
    void stop();

    /** True when the backend reports the window has been closed by the user. */
    boolean isClosed();

    /** True when this backend draws anything at all. */
    default boolean isHeadless() {
        return false;
    }
}
