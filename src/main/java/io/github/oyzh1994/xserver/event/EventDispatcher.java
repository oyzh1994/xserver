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
        deliverCrossing(previous, current, screenX, screenY, rootX, rootY, null);
    }

    /**
     * Crossing events for a move from {@code previous} to {@code current}.
     *
     * @param inside when non-null, only this window and its descendants are told. While a pointer
     *     grab is active, only that subtree is where the pointer "is" as far as any client is
     *     concerned: a menu that grabs the pointer must not have the window underneath it light up,
     *     and - the reason this matters - the window the pointer really left still has to hear about
     *     it, or a toolkit that tracks the pointer by crossing events never learns which of its own
     *     windows the pointer moved to.
     */
    public void deliverCrossing(XWindow previous, XWindow current, int screenX, int screenY,
                                int rootX, int rootY, XWindow inside) {
        deliverCrossing(previous, current, screenX, screenY, rootX, rootY, inside, null);
    }

    /**
     * @param detailGrab the window a grab is held on, whose crossings with the outside are reported
     *     as moves inside the grabbing client's own tree; null when no such grab is held
     */
    public void deliverCrossing(XWindow previous, XWindow current, int screenX, int screenY,
                                int rootX, int rootY, XWindow inside, XWindow detailGrab) {
        if (previous == current) {
            return;
        }
        XWindow common = commonAncestor(previous, current);
        int time = time();
        // Crossing is reported against the window the pointer is on now, or the one it left when
        // it left everything: that is whose screen's pointer state the event carries.
        XWindow crossed = current != null ? current : previous;
        int state = crossed == null ? server.input().pointerState()
                : server.inputFor(crossed.screen()).pointerState();

        if (previous != null) {
            deliverCrossingWindow(previous, false, crossingDetail(previous, current, detailGrab), time,
                    screenX, screenY, rootX, rootY, state, inside);
            if (common != previous) {
                for (XWindow w = previous.parent(); w != null && w != common; w = w.parent()) {
                    deliverCrossingWindow(w, false, NOTIFY_VIRTUAL, time, screenX, screenY,
                            rootX, rootY, state, inside);
                }
            }
        }
        if (current != null) {
            deliverCrossingWindow(current, true, crossingDetail(current, previous, detailGrab), time,
                    screenX, screenY, rootX, rootY, state, inside);
            if (common != current) {
                for (XWindow w = current.parent(); w != null && w != common; w = w.parent()) {
                    deliverCrossingWindow(w, true, NOTIFY_VIRTUAL, time, screenX, screenY,
                            rootX, rootY, state, inside);
                }
            }
        }
    }

    private void deliverCrossingWindow(XWindow window, boolean enter, byte detail, int time,
                                       int screenX, int screenY, int rootX, int rootY, int state,
                                       XWindow inside) {
        if (inside != null && inside != window && !inside.isAncestorOf(window)) {
            // A grab is held on another window: this one is outside the tree the pointer belongs to
            // for as long as the grab lasts, so it is told nothing.
            if (log.isDebugEnabled()) {
                log.debug("{}Notify for 0x{} withheld: the grab on 0x{} does not cover it",
                        enter ? "Enter" : "Leave", Integer.toHexString(window.id()),
                        Integer.toHexString(inside.id()));
            }
            return;
        }
        if (log.isDebugEnabled()) {
            // Which window a crossing is reported against, and whether a grab produced it, is what
            // tells a client where its pointer is: the events are indistinguishable in the log
            // otherwise, and this is the pair a menu's submenu lives or dies by.
            log.debug("{}Notify on 0x{} detail={}", enter ? "Enter" : "Leave",
                    Integer.toHexString(window.id()), detail);
        }
        int maskBit = enter ? EventMasks.ENTER_WINDOW : EventMasks.LEAVE_WINDOW;
        // The focus bit of a crossing is set when the focus window is this window *or a window
        // inside it*, not only when it is this one exactly, and the focus that counts is the one
        // keys actually go to - so a focus of PointerRoot means the window under the pointer. Both
        // are what a real server reports, and a toolkit reads the bit to decide whether the window
        // it is tracking still has the focus.
        XWindow focus = server.inputFor(window.screen()).inputFocus();
        boolean focused = focus == null || focus.isRoot() || window.isAncestorOf(focus);
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
                        .root(window.root().id())
                        .event(w.id())
                        .child(childId)
                        .rootX((short) rootX)
                        .rootY((short) rootY)
                        .eventX((short) x)
                        .eventY((short) y)
                        .state((short) state)
                        .mode((byte) 0)
                        .sameScreenFocus((byte) (0x01 | (focused ? 0x02 : 0)))
                        .build();
            }
            return LeaveNotifyEvent.builder()
                    .firstEventOffset((byte) 0)
                    .sentEvent(false)
                    .sequenceNumber(sequence)
                    .detail(detail)
                    .time(time)
                    .root(window.root().id())
                    .event(w.id())
                    .child(childId)
                    .rootX((short) rootX)
                    .rootY((short) rootY)
                    .eventX((short) x)
                    .eventY((short) y)
                    .state((short) state)
                    .mode((byte) 0)
                    .sameScreenFocus((byte) (0x01 | (focused ? 0x02 : 0)))
                    .build();
        });
    }

    /**
     * How the pointer moved between two windows, for the {@code detail} of a crossing.
     *
     * <p>While a grab that reports the client's own windows is held, a crossing between the grab
     * window and a window outside it is reported as a move inside the grabbing client's tree -
     * {@code NotifyAncestor} - rather than {@code NotifyNonlinear}, which is what the reference
     * server was measured sending for the same gesture.
     */
    private byte crossingDetail(XWindow window, XWindow other, XWindow inside) {
        if (inside != null && (other == inside || window == inside)) {
            return NOTIFY_ANCESTOR;
        }
        return crossingDetail(window, other);
    }

    /** How the pointer moved between two windows, for the {@code detail} of a crossing. */
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
