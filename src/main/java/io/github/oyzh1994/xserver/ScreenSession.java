package io.github.oyzh1994.xserver;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.Surface;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.input.InputState;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XWindow;

import java.awt.Rectangle;

/**
 * One host window and the X screen it shows - or, once a client has more than one top level, the
 * window one of them is shown in (C8).
 *
 * <p>A client's own screen is what makes "one window per forwarded application" work. Its windows
 * live on that screen, so they cannot be drawn over by another client's, and its input goes to that
 * screen's own pointer and focus. A client's screen is shown by **one host window per mapped top
 * level**: each of these objects is one of them, and the ones that show a window present that
 * window's own picture - its subtree composited from the windows' stores ({@code WindowSubtree}) -
 * rather than a rectangle of the screen, which is what keeps a dialog out of its parent's frame.
 *
 * <p>There is at least one session per screen, and it is the one that opens with the connection and
 * closes with it ({@link #primary()}). The rest are frames around the client's other top levels, and
 * they are created and destroyed by the display tick as those windows are mapped and unmapped.
 */
public final class ScreenSession implements DisplayBackend.InputSink, Surface {

    private final Screen screen;
    private final InputState input;
    /** The connection this screen belongs to, or null for the master screen. */
    private final ClientConnection client;

    /** Set once the connection is gone: the window is closed and the screen freed by the display tick. */
    private volatile boolean closed;

    // Everything below is only touched from the display tick, which is the only thread that opens
    // windows or presents to them. Reading it outside is how a request thread asks for a repaint,
    // which is why the fields are volatile rather than locked.
    private volatile DisplayBackend backend;
    /** True while the session has no window on purpose (see {@link #detached()}). */
    private volatile boolean detached;
    /** True when the next frame has to be the whole viewport rather than the damage. */
    private volatile boolean fullFrame;
    /** The rectangle of the screen the window shows: the area the client's windows occupy. */
    private volatile Rectangle viewport;
    private volatile CursorSprite lastCursor;
    private volatile boolean idle;
    /** What the backend was last told about idleness, so it is told once rather than every frame. */
    private volatile boolean idleApplied;
    /** When the host window was closed and the client was asked to quit, or 0 before that. */
    private volatile long closeDeadline;
    /** A rectangle the client's windows have moved to, waiting to hold still before it is applied. */
    private Rectangle pendingViewport;
    private long pendingSince;
    /**
     * The title and icon the client publishes for its own window, waiting to be put on the host
     * window, and the one it was last told about. Compared by identity: a new value is only built
     * when a property actually changed, so an equal-looking one is the same object.
     */
    private volatile WindowIdentity identity = WindowIdentity.NONE;
    private WindowIdentity appliedIdentity = WindowIdentity.NONE;
    /** The client window the identity above was read from, or null before one is mapped. */
    private XWindow identitySource;
    /**
     * Whether the host window still owes a raise, because the client's first top level window has
     * appeared (C2).
     *
     * <p>Set when that window is mapped and cleared once the raise has been made, which is one raise
     * per session however many windows the client goes on to map. A client that maps a popup per
     * keystroke is not asking to be brought to the front, and one that is already in front does not
     * need to be.
     */
    private volatile boolean raisePending;
    /**
     * Whether a frame has been put on the screen at all.
     *
     * <p>The raise waits for this: bringing the window forward the moment a client's first window is
     * mapped would show the user an empty frame, and what they are being told is that their
     * application has arrived.
     */
    private volatile boolean framePresented;
    /** The largest client area the window was last told it may be, or null before it was told. */
    private java.awt.Dimension maxClientArea;
    /**
     * The cursor the XFixes subscribers were last told about, and whether they have been told
     * anything at all yet. Only the display tick touches either.
     *
     * <p>The first value is adopted silently rather than announced: the pointer's position is not
     * known until the first mouse event, so the first cursor the server can name is the baseline the
     * subscription started at, not a change. A reference server sends nothing until the cursor moves
     * off what was displayed when the client subscribed, and this is what makes the two the same.
     */
    private XCursor notifiedCursor;
    private boolean cursorBaselineTaken;
    /**
     * What this client is remembered by between runs (its {@code WM_CLASS}), and where its window was
     * left. Only the display tick touches these.
     */
    private String clientKey;
    /**
     * Set when the tick has grown this screen and the backend has not been told yet. It happens
     * under the server lock - the pixels are replaced there - and the backend is reached from the
     * tick's unlocked half, like every other call into the toolkit.
     */
    private boolean screenResized;

    private java.awt.Point windowPosition;
    private java.awt.Point persistedWindowPosition;
    private long positionHeldSince;

    /**
     * Whether this is the session the connection opened with - the one that owns the screen and
     * answers for it.
     *
     * <p>The per-screen work (the XFixes cursor notification) and the screen's own teardown belong
     * to it alone: a client with three top levels has three of these objects over one screen, and
     * three of them announcing the same cursor change would tell a subscriber the same thing twice.
     */
    private boolean primary;

    /**
     * The X window this host window shows, or null when it shows the whole screen (the master screen
     * and single-window mode). Set from the display tick, which is what follows the client's top
     * levels.
     */
    private volatile XWindow target;

    /**
     * The pixels shown when {@link #target} is set: that window's own picture, kept up to date from
     * the windows' stores (C8). Null for a session that shows the screen.
     */
    private Framebuffer picture;

    /**
     * A size the user asked for by dragging <em>this</em> host window's edge, waiting for the tick.
     *
     * <p>Here rather than on the {@link InputState}, where it used to be: a screen now has one host
     * window per top level, and a drag of one of them must resize that window and not every window
     * the client has - which is what reading the request off the shared input state did.
     */
    private volatile java.awt.Dimension requestedSize;
    /** Where the user last dragged this host window, reported by the backend. */
    private volatile java.awt.Point hostWindowPosition;

    public ScreenSession(Screen screen, InputState input, ClientConnection client) {
        this.screen = screen;
        this.input = input;
        this.client = client;
    }

    public boolean primary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public XWindow target() {
        return target;
    }

    public void setTarget(XWindow target) {
        this.target = target;
    }

    /**
     * The pixels this host window shows: the window's own picture when it shows one, and the screen
     * otherwise. What {@code DisplayBackend.present} is handed.
     */
    public Surface surface() {
        return this;
    }

    @Override
    public Framebuffer framebuffer() {
        Framebuffer frame = picture;
        return frame != null ? frame : screen.framebuffer();
    }

    @Override
    public int width() {
        XWindow window = target;
        return window != null ? window.width() : screen.width();
    }

    @Override
    public int height() {
        XWindow window = target;
        return window != null ? window.height() : screen.height();
    }

    @Override
    public int scale() {
        return screen.scale();
    }

    /**
     * The picture of the window this session shows, made to the window's current size.
     *
     * <p>Reallocated when the window changes size, and **not** kept across one: a frame is a view of
     * the window, so a resize is a new picture rather than a stretched old one. The contents are
     * drawn by {@code WindowSubtree} before the frame is presented.
     */
    public Framebuffer picture() {
        XWindow window = target;
        if (window == null) {
            return null;
        }
        if (picture == null || picture.width() != window.width()
                || picture.height() != window.height()) {
            picture = new Framebuffer(Math.max(1, window.width()), Math.max(1, window.height()), 0);
        }
        return picture;
    }

    @Override
    public void pointerMoved(int x, int y) {
        input.pointerMoved(x, y);
    }

    @Override
    public void buttonChanged(int button, boolean pressed, int x, int y) {
        input.buttonChanged(button, pressed, x, y);
    }

    @Override
    public void keyChanged(int keycode, boolean pressed, int x, int y) {
        input.keyChanged(keycode, pressed, x, y);
    }

    @Override
    public void keyTyped(char character) {
        input.keyTyped(character);
    }

    @Override
    public void hostDrop(java.util.List<String> paths, int x, int y) {
        input.hostDrop(paths, x, y);
    }

    @Override
    public void focusChanged(boolean focused) {
        input.focusChanged(focused);
    }

    /**
     * The user dragged this host window's edge. Recorded rather than applied: this arrives on the
     * toolkit's thread, and resizing a client's window means taking the server lock and walking the
     * window tree, which the tick is doing at the same time.
     */
    @Override
    public void windowResized(int width, int height) {
        if (width > 0 && height > 0) {
            requestedSize = new java.awt.Dimension(width, height);
        }
    }

    /** The size the user last dragged this window to, clearing it. Null when there is none. */
    public java.awt.Dimension takeRequestedSize() {
        java.awt.Dimension requested = requestedSize;
        requestedSize = null;
        return requested;
    }

    /**
     * The user moved this host window. Called from the toolkit's thread, like
     * {@link #windowResized}, so it only records where the window is: the display tick is what
     * remembers it, because that is where the server's own bookkeeping happens and where a write to
     * a file is not holding anybody up.
     */
    @Override
    public void windowMoved(int x, int y) {
        hostWindowPosition = new java.awt.Point(x, y);
    }

    /** Where this host window is now, or null when it has not been moved since the server started. */
    public java.awt.Point hostWindowPosition() {
        return hostWindowPosition;
    }

    public Screen screen() {
        return screen;
    }

    public InputState input() {
        return input;
    }

    public ClientConnection client() {
        return client;
    }

    public boolean closed() {
        return closed;
    }

    public void markClosed() {
        this.closed = true;
    }

    public DisplayBackend backend() {
        return backend;
    }

    public void setBackend(DisplayBackend backend) {
        this.backend = backend;
    }

    /**
     * True while this session has no window and none may be opened (backlog D1).
     *
     * <p>It is a state of its own rather than "the backend is null", because null is also what a
     * session looks like before its first window has opened: the display tick opens one as soon as
     * the client maps something, which is exactly what must not happen while detached.
     */
    public boolean detached() {
        return detached;
    }

    public void setDetached(boolean detached) {
        this.detached = detached;
    }

    /**
     * A frame the whole viewport has to be presented for, whatever the damage says.
     *
     * <p>Set when a window attaches to a screen that has been drawing with nobody to show it: the
     * pixels are all still in the framebuffer, but the damage they were drawn with was consumed
     * frame by frame while there was no window to present it to, so a new window that waited for
     * damage would show the client's background until the client next drew something.
     */
    public boolean takeFullFrame() {
        boolean wanted = fullFrame;
        fullFrame = false;
        return wanted;
    }

    public void setFullFrame(boolean fullFrame) {
        this.fullFrame = fullFrame;
    }

    public Rectangle viewport() {
        return viewport;
    }

    public void setViewport(Rectangle viewport) {
        this.viewport = viewport;
    }

    public CursorSprite lastCursor() {
        return lastCursor;
    }

    public void setLastCursor(CursorSprite lastCursor) {
        this.lastCursor = lastCursor;
    }

    public boolean idle() {
        return idle;
    }

    public void setIdle(boolean idle) {
        this.idle = idle;
    }

    public boolean idleApplied() {
        return idleApplied;
    }

    public void setIdleApplied(boolean idleApplied) {
        this.idleApplied = idleApplied;
    }

    public Rectangle pendingViewport() {
        return pendingViewport;
    }

    public void setPendingViewport(Rectangle pendingViewport) {
        this.pendingViewport = pendingViewport;
    }

    public long pendingSince() {
        return pendingSince;
    }

    public void setPendingSince(long pendingSince) {
        this.pendingSince = pendingSince;
    }

    public WindowIdentity identity() {
        return identity;
    }

    public void setIdentity(WindowIdentity identity) {
        this.identity = identity;
    }

    public XWindow identitySource() {
        return identitySource;
    }

    public void setIdentitySource(XWindow identitySource) {
        this.identitySource = identitySource;
    }

    /** True while the host window still owes a raise - see {@link #raisePending}. */
    public boolean raisePending() {
        return raisePending;
    }

    public void setRaisePending(boolean raisePending) {
        this.raisePending = raisePending;
    }

    /** True once the frame has been put on the screen, which is what a raise waits for. */
    public boolean framePresented() {
        return framePresented;
    }

    public void setFramePresented(boolean framePresented) {
        this.framePresented = framePresented;
    }

    public java.awt.Dimension maxClientArea() {
        return maxClientArea;
    }

    public void setMaxClientArea(java.awt.Dimension maxClientArea) {
        this.maxClientArea = maxClientArea;
    }

    public boolean appliedFullScreen() {
        return appliedFullScreen;
    }

    public void setAppliedFullScreen(boolean appliedFullScreen) {
        this.appliedFullScreen = appliedFullScreen;
    }

    /** True once the frame has been raised for the client's urgency hint. */
    public boolean appliedUrgent() {
        return appliedUrgent;
    }

    public void setAppliedUrgent(boolean appliedUrgent) {
        this.appliedUrgent = appliedUrgent;
    }

    /** Whether this screen has grown and the backend has still to be told. */
    public boolean takeScreenResized() {
        boolean resized = screenResized;
        screenResized = false;
        return resized;
    }

    public void setScreenResized() {
        this.screenResized = true;
    }

    public String clientKey() {
        return clientKey;
    }

    public void setClientKey(String clientKey) {
        this.clientKey = clientKey;
    }

    /** Where the user has left the window, waiting to hold still long enough to be written out. */
    public java.awt.Point windowPosition() {
        return windowPosition;
    }

    public void setWindowPosition(java.awt.Point windowPosition) {
        this.windowPosition = windowPosition;
    }

    /** Whether the host window is showing full screen, and whether it was raised for an urgency. */
    private boolean appliedFullScreen;
    private boolean appliedUrgent;

    /** Where the window was when it was last written to the store. */
    public java.awt.Point persistedWindowPosition() {
        return persistedWindowPosition;
    }

    public void setPersistedWindowPosition(java.awt.Point persistedWindowPosition) {
        this.persistedWindowPosition = persistedWindowPosition;
    }

    /** When the position it is holding was first seen, for the settle. */
    public long positionHeldSince() {
        return positionHeldSince;
    }

    public void setPositionHeldSince(long positionHeldSince) {
        this.positionHeldSince = positionHeldSince;
    }

    public XCursor notifiedCursor() {
        return notifiedCursor;
    }

    public void setNotifiedCursor(XCursor notifiedCursor) {
        this.notifiedCursor = notifiedCursor;
    }

    public boolean cursorBaselineTaken() {
        return cursorBaselineTaken;
    }

    public void setCursorBaselineTaken(boolean cursorBaselineTaken) {
        this.cursorBaselineTaken = cursorBaselineTaken;
    }

    public WindowIdentity appliedIdentity() {
        return appliedIdentity;
    }

    public void setAppliedIdentity(WindowIdentity appliedIdentity) {
        this.appliedIdentity = appliedIdentity;
    }

    public long closeDeadline() {
        return closeDeadline;
    }

    public void setCloseDeadline(long closeDeadline) {
        this.closeDeadline = closeDeadline;
    }
}
