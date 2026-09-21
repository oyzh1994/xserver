package io.github.oyzh1994.xserver.event;

import com.github.moaxcp.x11.protocol.XEvent;
import com.github.moaxcp.x11.protocol.xproto.EnterNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.LeaveNotifyEvent;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.resource.XWindow;
import io.github.oyzh1994.xserver.resource.XWindow.OtherClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Builds and delivers events.
 *
 * <p>The generated event classes are immutable, so a delivery target is never patched into an
 * event after the fact: callers supply a factory and one event is built per recipient, carrying
 * that recipient's sequence number, the window the event is reported against, the child window
 * and window relative coordinates. That is what makes propagation and grabs work, since the same
 * logical event is reported relative to different windows for different clients.
 */
public final class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    /** Detail codes for EnterNotify and LeaveNotify. */
    public static final byte NOTIFY_ANCESTOR = 0;
    public static final byte NOTIFY_VIRTUAL = 1;
    public static final byte NOTIFY_INFERIOR = 2;
    public static final byte NOTIFY_NONLINEAR = 3;
    public static final byte NOTIFY_NONLINEAR_VIRTUAL = 4;

    /** Normal crossing mode; grabs use the Grab and Ungrab detail modes. */
    public static final byte NOTIFY_MODE_NORMAL = 0;

    private final XServer server;

    public EventDispatcher(XServer server) {
        this.server = server;
    }

    /**
     * Builds one event for a specific delivery target.
     *
     * @param recipient   the client that will receive this copy
     * @param eventWindow the window the event is reported against
     * @param child       the child of that window the pointer is in, or null
     * @param x           x relative to {@code eventWindow}
     * @param y           y relative to {@code eventWindow}
     */
    public interface EventFactory {
        XEvent create(ClientConnection recipient, XWindow eventWindow, XWindow child, int x, int y);
    }

    /** Milliseconds truncated to the 32 bit X timestamp. */
    public int time() {
        return (int) (System.currentTimeMillis() & 0xFFFFFFFFL);
    }

    /** Sends an already built event, silently dropping it for dead connections. */
    public void send(ClientConnection client, XEvent event) {
        if (client == null || client.isClosed() || client.isShuttingDown()) {
            return;
        }
        try {
            client.output().writeEvent(event);
            if (log.isDebugEnabled()) {
                // What the server tells the client matters as much as what the client asks for: a
                // toolkit that never learns its window is visible will never draw into it.
                log.debug("event {} -> {}", event.getClass().getSimpleName(), client);
            }
        } catch (IOException e) {
            log.debug("dropping event for {}: {}", client.peer(), e.getMessage());
            client.close();
        }
    }

    /**
     * Delivers to every client that selected {@code maskBit} on {@code window}: the owner plus
     * any client that registered interest through {@code ChangeWindowAttributes}.
     *
     * @return the number of clients the event reached
     */
    public int deliverToWindow(XWindow window, int maskBit, EventFactory factory) {
        int delivered = 0;
        ClientConnection owner = window.owner();
        if (owner != null && (window.eventMask() & maskBit) != 0) {
            send(owner, factory.create(owner, window, null, 0, 0));
            delivered++;
        }
        for (OtherClient other : window.otherClients()) {
            if ((other.mask & maskBit) != 0) {
                send(other.client, factory.create(other.client, window, null, 0, 0));
                delivered++;
            }
        }
        return delivered;
    }

    /**
     * Delivers a pointer or key event, propagating up the window tree when nobody on the starting
     * window is interested. Propagation stops at the first window that had an interested client, at
     * the root, or when a window blocks the mask with {@code doNotPropagateMask}.
     *
     * @param start   the deepest window the pointer is in
     * @param maskBit the event mask that makes a client interested
     * @param screenX pointer x in screen coordinates
     * @param screenY pointer y in screen coordinates
     */
    public int deliverPropagating(XWindow start, int maskBit, int screenX, int screenY,
                              EventFactory factory) {
        XWindow window = start;
        XWindow child = null;
        int delivered = 0;
        while (window != null) {
            if (window.anyClientSelected(maskBit)) {
                XWindow eventWindow = window;
                XWindow eventChild = child;
                int x = screenX - eventWindow.absoluteX();
                int y = screenY - eventWindow.absoluteY();
                delivered += deliverToWindow(eventWindow, maskBit,
                        (client, w, c, cx, cy) -> factory.create(client, w, eventChild, x, y));
                break;
            }
            if ((maskBit & EventMasks.PROPAGATE) == 0) {
                break;
            }
            if ((window.doNotPropagateMask() & maskBit) != 0) {
                break;
            }
            child = window;
            window = window.parent();
        }
        return delivered;
    }

    /**
     * Sends an event both to the window itself (for clients selecting {@code ownMaskBit}) and to
     * its parent (for clients selecting {@code parentMaskBit}), which is how structure changes are
     * reported.
     */
    public void sendStructureNotify(XWindow window, int ownMaskBit, int parentMaskBit,
                                    EventFactory factory) {
        if (ownMaskBit != 0) {
            deliverToWindow(window, ownMaskBit, factory);
        }
        XWindow parent = window.parent();
        if (parentMaskBit != 0 && parent != null) {
            deliverToWindow(parent, parentMaskBit, factory);
        }
    }

    // ------------------------------------------------------------------
    // Pointer crossing
    // ------------------------------------------------------------------

    /**
     * Generates the EnterNotify and LeaveNotify events for a pointer move from {@code previous} to
     * {@code current}.
     *
     * <p>The windows notified are those on the path up from the old window and down to the new one,
     * stopping at their common ancestor. The {@code detail} of each event names the relationship of
     * the *other* window to the event window, which is what toolkits rely on: moving from a window
     * into its child reports {@code NotifyInferior} on the parent and {@code NotifyAncestor} on the
     * child.
     */
    public void deliverCrossing(XWindow previous, XWindow current, int screenX, int screenY,
                                int rootX, int rootY) {
        if (previous == current) {
            return;
        }
        XWindow common = commonAncestor(previous, current);
        int time = time();
        int state = server.input().pointerState();

        if (previous != null) {
            deliverCrossingWindow(previous, false, crossingDetail(previous, current), time,
                    screenX, screenY, rootX, rootY, state);
            if (common != previous) {
                for (XWindow w = previous.parent(); w != null && w != common; w = w.parent()) {
                    deliverCrossingWindow(w, false, NOTIFY_VIRTUAL, time, screenX, screenY,
                            rootX, rootY, state);
                }
            }
        }
        if (current != null) {
            deliverCrossingWindow(current, true, crossingDetail(current, previous), time,
                    screenX, screenY, rootX, rootY, state);
            if (common != current) {
                for (XWindow w = current.parent(); w != null && w != common; w = w.parent()) {
                    deliverCrossingWindow(w, true, NOTIFY_VIRTUAL, time, screenX, screenY,
                            rootX, rootY, state);
                }
            }
        }
    }

    private void deliverCrossingWindow(XWindow window, boolean enter, byte detail, int time,
                                       int screenX, int screenY, int rootX, int rootY, int state) {
        int maskBit = enter ? EventMasks.ENTER_WINDOW : EventMasks.LEAVE_WINDOW;
        boolean focused = server.input().inputFocus() == window;
        deliverPropagating(window, maskBit, screenX, screenY, (client, w, child, x, y) -> {
            short sequence = client.sequenceNumber();
            int childId = child == null ? 0 : child.id();
            if (enter) {
                return EnterNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(sequence)
                        .detail(detail)
                        .time(time)
                        .root(server.rootWindow().id())
                        .event(w.id())
                        .child(childId)
                        .rootX((short) rootX)
                        .rootY((short) rootY)
                        .eventX((short) x)
                        .eventY((short) y)
                        .state((short) state)
                        .mode(NOTIFY_MODE_NORMAL)
                        .sameScreenFocus((byte) (0x01 | (focused ? 0x02 : 0)))
                        .build();
            }
            return LeaveNotifyEvent.builder()
                    .firstEventOffset((byte) 0)
                    .sentEvent(false)
                    .sequenceNumber(sequence)
                    .detail(detail)
                    .time(time)
                    .root(server.rootWindow().id())
                    .event(w.id())
                    .child(childId)
                    .rootX((short) rootX)
                    .rootY((short) rootY)
                    .eventX((short) x)
                    .eventY((short) y)
                    .state((short) state)
                    .mode(NOTIFY_MODE_NORMAL)
                    .sameScreenFocus((byte) (0x01 | (focused ? 0x02 : 0)))
                    .build();
        });
    }

    private byte crossingDetail(XWindow window, XWindow other) {
        if (other == null) {
            return NOTIFY_NONLINEAR;
        }
        if (other.isAncestorOf(window)) {
            return NOTIFY_ANCESTOR;
        }
        if (window.isAncestorOf(other)) {
            return NOTIFY_INFERIOR;
        }
        return NOTIFY_NONLINEAR;
    }

    /** The deepest window that is an ancestor of both, or null when they are unrelated. */
    public static XWindow commonAncestor(XWindow a, XWindow b) {
        for (XWindow window = a; window != null; window = window.parent()) {
            if (b != null && window.isAncestorOf(b)) {
                return window;
            }
        }
        return null;
    }
}
