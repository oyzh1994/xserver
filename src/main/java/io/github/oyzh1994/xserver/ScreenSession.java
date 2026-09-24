package io.github.oyzh1994.xserver;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.input.InputState;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XWindow;

import java.awt.Rectangle;

/**
 * One X screen together with the host window that shows it: the master screen shared by every
 * client, or the private screen of a single connection.
 *
 * <p>A client's own screen is what makes "one window per forwarded application" work. Its windows
 * live on that screen, so they cannot be drawn over by another client's, and its input goes to that
 * screen's own pointer and focus. The window is opened when the client first maps something, shows
 * the part of the screen the client's windows occupy, and goes away with the connection.
 */
public final class ScreenSession {

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

    public ScreenSession(Screen screen, InputState input, ClientConnection client) {
        this.screen = screen;
        this.input = input;
        this.client = client;
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

    public java.awt.Dimension maxClientArea() {
        return maxClientArea;
    }

    public void setMaxClientArea(java.awt.Dimension maxClientArea) {
        this.maxClientArea = maxClientArea;
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
