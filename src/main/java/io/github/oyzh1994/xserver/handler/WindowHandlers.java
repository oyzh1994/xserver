package io.github.oyzh1994.xserver.handler;

import com.github.moaxcp.x11.protocol.xproto.ChangeSaveSet;
import com.github.moaxcp.x11.protocol.xproto.ChangeWindowAttributes;
import com.github.moaxcp.x11.protocol.xproto.Circulate;
import com.github.moaxcp.x11.protocol.xproto.CirculateNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.CirculateWindow;
import com.github.moaxcp.x11.protocol.xproto.ConfigWindow;
import com.github.moaxcp.x11.protocol.xproto.ConfigureNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.ConfigureWindow;
import com.github.moaxcp.x11.protocol.xproto.CreateNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.CreateWindow;
import com.github.moaxcp.x11.protocol.xproto.Cw;
import com.github.moaxcp.x11.protocol.xproto.DestroyNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.DestroySubwindows;
import com.github.moaxcp.x11.protocol.xproto.DestroyWindow;
import com.github.moaxcp.x11.protocol.xproto.ExposeEvent;
import com.github.moaxcp.x11.protocol.xproto.GetGeometry;
import com.github.moaxcp.x11.protocol.xproto.GetGeometryReply;
import com.github.moaxcp.x11.protocol.xproto.GetWindowAttributes;
import com.github.moaxcp.x11.protocol.xproto.GetWindowAttributesReply;
import com.github.moaxcp.x11.protocol.xproto.GravityNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.MapNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.MapRequestEvent;
import com.github.moaxcp.x11.protocol.xproto.MapSubwindows;
import com.github.moaxcp.x11.protocol.xproto.MapWindow;
import com.github.moaxcp.x11.protocol.xproto.Place;
import com.github.moaxcp.x11.protocol.xproto.QueryBestSize;
import com.github.moaxcp.x11.protocol.xproto.QueryBestSizeReply;
import com.github.moaxcp.x11.protocol.xproto.QueryTree;
import com.github.moaxcp.x11.protocol.xproto.QueryTreeReply;
import com.github.moaxcp.x11.protocol.xproto.ReparentNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.ReparentWindow;
import com.github.moaxcp.x11.protocol.xproto.StackMode;
import com.github.moaxcp.x11.protocol.xproto.TranslateCoordinates;
import com.github.moaxcp.x11.protocol.xproto.TranslateCoordinatesReply;
import com.github.moaxcp.x11.protocol.xproto.UnmapNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.UnmapSubwindows;
import com.github.moaxcp.x11.protocol.xproto.UnmapWindow;
import com.github.moaxcp.x11.protocol.xproto.VisibilityNotifyEvent;
import com.github.moaxcp.x11.protocol.xproto.Visibility;
import com.github.moaxcp.x11.protocol.xproto.WindowClass;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.event.RawEvent;
import io.github.oyzh1994.xserver.extension.XFixesExtension;
import io.github.oyzh1994.xserver.resource.ResourceTable;
import io.github.oyzh1994.xserver.resource.ShapeState;
import io.github.oyzh1994.xserver.resource.XColormap;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XPixmap;
import io.github.oyzh1994.xserver.resource.XResource;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.impl.factory.primitive.IntLists;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Window requests: creating, mapping, stacking, resizing and destroying windows, plus the queries
 * that describe them.
 *
 * <p>Structure changes are reported to the window itself (StructureNotify) and to its parent
 * (SubstructureNotify), and the {@code event} field of those notifications is the window the
 * client selected on rather than the window that changed - which is why each notification is built
 * per delivery target rather than once.
 */
public final class WindowHandlers {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(WindowHandlers.class);

    private final XServer server;

    public WindowHandlers(XServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Creation and destruction
    // ------------------------------------------------------------------

    public void create(ClientConnection client, CreateWindow r) {
        XWindow parent = window(client, r.getParent());
        int wid = r.getWid();
        if (wid == 0) {
            throw ProtocolException.badIdChoice(wid);
        }
        if (!client.ownsIdRange(wid)) {
            throw ProtocolException.badIdChoice(wid);
        }
        if (server.resources().contains(wid)) {
            throw ProtocolException.badIdChoice(wid);
        }

        int requestedClass = r.getClazz();
        int windowClass;
        if (requestedClass == WindowClass.COPY_FROM_PARENT.getValue()) {
            windowClass = parent.windowClass();
        } else if (requestedClass == WindowClass.INPUT_OUTPUT.getValue()) {
            windowClass = XWindow.INPUT_OUTPUT;
        } else if (requestedClass == WindowClass.INPUT_ONLY.getValue()) {
            windowClass = XWindow.INPUT_ONLY;
        } else {
            throw ProtocolException.badValue(requestedClass);
        }

        int depth = Byte.toUnsignedInt(r.getDepth());
        if (depth == 0) {
            depth = parent.depth();
        } else if (depth != parent.depth()) {
            // A child must share its parent's depth; anything else is a match failure.
            throw ProtocolException.badMatch();
        }
        if (windowClass == XWindow.INPUT_ONLY && depth != 0 && depth != parent.depth()) {
            throw ProtocolException.badMatch();
        }

        VisualInfo visual;
        if (r.getVisual() == 0 || r.getVisual() == 0xFFFFFFFF) {
            visual = parent.visual();
        } else {
            visual = parent.screen().visualById(r.getVisual());
            if (visual == null) {
                throw ProtocolException.badMatch();
            }
        }
        if (visual.depth() != depth) {
            throw ProtocolException.badMatch();
        }

        int borderWidth = Short.toUnsignedInt(r.getBorderWidth());
        if (windowClass == XWindow.INPUT_ONLY && borderWidth != 0) {
            throw ProtocolException.badMatch();
        }

        XWindow window = new XWindow(wid, client, parent.screen(), parent, depth, windowClass,
                visual, r.getX(), r.getY(), Short.toUnsignedInt(r.getWidth()),
                Short.toUnsignedInt(r.getHeight()), borderWidth);
        // The colours the window starts with are the ones XWindow gives every window: the screen's
        // own default behind it, and a distinct border. A client that names its own in this request
        // overrides them below.

        applyAttributes(client, window, r.getValueMask(), r.getBackgroundPixmap(),
                r.getBackgroundPixel(), r.getBorderPixmap(), r.getBorderPixel(), r.getBitGravity(),
                r.getWinGravity(), r.getBackingStore(), r.getBackingPlanes(), r.getBackingPixel(),
                r.getOverrideRedirect() != 0, r.getSaveUnder() != 0, r.getEventMask(),
                r.getDoNotPropogateMask(), r.getColormap(), r.getCursor());

        server.resources().add(window);

        // CreateNotify goes to the parent's SubstructureNotify clients.
        server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> CreateNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .parent(w.id())
                        .window(window.id())
                        .x((short) window.x())
                        .y((short) window.y())
                        .width((short) window.width())
                        .height((short) window.height())
                        .borderWidth((short) window.borderWidth())
                        .overrideRedirect(window.isOverrideRedirect())
                        .build());
    }

    public void changeAttributes(ClientConnection client, ChangeWindowAttributes r) {
        XWindow window = window(client, r.getWindow());
        applyAttributes(client, window, r.getValueMask(), r.getBackgroundPixmap(),
                r.getBackgroundPixel(), r.getBorderPixmap(), r.getBorderPixel(), r.getBitGravity(),
                r.getWinGravity(), r.getBackingStore(), r.getBackingPlanes(), r.getBackingPixel(),
                r.getOverrideRedirect() != 0, r.getSaveUnder() != 0, r.getEventMask(),
                r.getDoNotPropogateMask(), r.getColormap(), r.getCursor());
        // Changing attributes does not repaint anything. A background change takes effect the next
        // time the window is cleared or exposed, and repainting here would paint over every child
        // window - which is precisely what erased xcalc's buttons the first time it ran.
    }

    /**
     * Applies a {@code CW} value mask shared by {@code CreateWindow} and
     * {@code ChangeWindowAttributes}. Only fields whose bit is set in the mask are meaningful, and
     * the generated decoder has already zeroed the rest.
     */
    private void applyAttributes(ClientConnection client, XWindow window, int valueMask,
                                 int backgroundPixmap, int backgroundPixel, int borderPixmap,
                                 int borderPixel, int bitGravity, int winGravity, int backingStore,
                                 int backingPlanes, int backingPixel, boolean overrideRedirect,
                                 boolean saveUnder, int eventMask, int doNotPropagateMask,
                                 int colormap, int cursor) {
        if (Cw.BACK_PIXMAP.isEnabled(valueMask)) {
            XResource pixmap = backgroundPixmap == 0 ? null
                    : server.resources().require(client, backgroundPixmap, XResource.CLASS_PIXMAP,
                            true);
            if (backgroundPixmap != 0 && ((XPixmap) pixmap).depth() != window.depth()) {
                throw ProtocolException.badMatch();
            }
            if (pixmap == null) {
                // Background None: the window shows what is behind it, which is the screen's own
                // default colour - the same thing a real server's root shows through.
                window.setBackgroundArgb(Screen.DEFAULT_BACKGROUND_ARGB);
            } else {
                window.setBackgroundPixmap(pixmap);
            }
        }
        if (Cw.BACK_PIXEL.isEnabled(valueMask)) {
            window.setBackgroundPixel(backgroundPixel);
        }
        if (Cw.BORDER_PIXMAP.isEnabled(valueMask)) {
            if (!window.isInputOnly()) {
                XResource pixmap = borderPixmap == 0 ? null
                        : server.resources().require(client, borderPixmap, XResource.CLASS_PIXMAP,
                        true);
                if (borderPixmap != 0 && ((XPixmap) pixmap).depth() != window.depth()) {
                    throw ProtocolException.badMatch();
                }
                if (pixmap == null) {
                    // Border None: nothing was named to draw it with, so it falls back to the colour
                    // a border starts with.
                    window.setBorderArgb(Screen.DEFAULT_BORDER_ARGB);
                } else {
                    window.setBorderPixmap(pixmap);
                }
            }
        }
        if (Cw.BORDER_PIXEL.isEnabled(valueMask) && !window.isInputOnly()) {
            window.setBorderPixel(borderPixel);
        }
        if (Cw.BIT_GRAVITY.isEnabled(valueMask)) {
            window.setBitGravity(bitGravity);
        }
        if (Cw.WIN_GRAVITY.isEnabled(valueMask)) {
            window.setWinGravity(winGravity);
        }
        if (Cw.BACKING_STORE.isEnabled(valueMask)) {
            if (backingStore == 2) {
                // Always: accepted, since every window keeps its content in the framebuffer.
                window.setBackingStore(1);
            } else {
                window.setBackingStore(backingStore == 1 ? 1 : 0);
            }
        }
        if (Cw.BACKING_PLANES.isEnabled(valueMask) && !window.isInputOnly()) {
            window.setBackingPlanes(backingPlanes);
        }
        if (Cw.BACKING_PIXEL.isEnabled(valueMask) && !window.isInputOnly()) {
            window.setBackingPixel(backingPixel);
        }
        if (Cw.OVERRIDE_REDIRECT.isEnabled(valueMask)) {
            window.setOverrideRedirect(overrideRedirect);
        }
        if (Cw.SAVE_UNDER.isEnabled(valueMask)) {
            window.setSaveUnder(saveUnder);
        }
        if (Cw.EVENT_MASK.isEnabled(valueMask)) {
            if ((eventMask & ~EventMasks.ALL) != 0) {
                throw ProtocolException.badValue(eventMask);
            }
            if (log.isDebugEnabled()) {
                // Which events a client asked for is the first thing to check when one it needs never
                // arrives, and the request itself says nothing about it.
                log.debug("{} selected 0x{} on 0x{}", client.peer(),
                        Integer.toHexString(eventMask), Integer.toHexString(window.id()));
            }
            if (window.owner() == client) {
                window.setEventMask(eventMask);
            } else {
                window.setOtherClientMask(client, eventMask);
            }
        }
        if (Cw.DONT_PROPAGATE.isEnabled(valueMask)) {
            if ((doNotPropagateMask & ~EventMasks.ALL) != 0) {
                throw ProtocolException.badValue(doNotPropagateMask);
            }
            window.setDoNotPropagateMask(doNotPropagateMask);
        }
        if (Cw.COLORMAP.isEnabled(valueMask) && !window.isInputOnly()) {
            XColormap map = colormap == 0 ? null
                    : (XColormap) server.resources().require(client, colormap,
                    XResource.CLASS_COLORMAP, true);
            if (map != null && map.visual() != window.visual()) {
                throw ProtocolException.badMatch();
            }
            window.setColormap(map);
        }
        if (Cw.CURSOR.isEnabled(valueMask)) {
            XCursor value = cursor == 0 ? null
                    : (XCursor) server.resources().require(client, cursor, XResource.CLASS_CURSOR,
                    true);
            window.setCursor(value);
        }
    }

    public void getAttributes(ClientConnection client, GetWindowAttributes r) {
        XWindow window = window(client, r.getWindow());
        client.sendReply(GetWindowAttributesReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .backingStore((byte) window.backingStore())
                .visual(window.visual() == null ? window.screen().rootVisual().id()
                        : window.visual().id())
                .clazz((short) window.windowClass())
                .bitGravity((byte) window.bitGravity())
                .winGravity((byte) window.winGravity())
                .backingPlanes(window.backingPlanes())
                .backingPixel(window.backingPixel())
                .saveUnder(window.isSaveUnder())
                .mapIsInstalled(window.isMapped())
                .mapState((byte) window.mapState())
                .overrideRedirect(window.isOverrideRedirect())
                .colormap(window.colormap() == null ? server.rootColormap().id()
                        : window.colormap().id())
                .allEventMasks(window.allEventMasks())
                .yourEventMask(window.eventMaskFor(client))
                .doNotPropagateMask((short) window.doNotPropagateMask())
                .build());
    }

    public void destroy(ClientConnection client, DestroyWindow r) {
        XWindow window = window(client, r.getWindow());
        destroyWindowTree(client, window);
    }

    public void destroySubwindows(ClientConnection client, DestroySubwindows r) {
        XWindow window = window(client, r.getWindow());
        for (XWindow child : new ArrayList<>(window.children())) {
            destroyWindowTree(client, child);
        }
    }

    /**
     * Destroys {@code window} and everything below it, generating the destroy notifications in
     * bottom-up order so a client never sees a parent destroyed before its children.
     */
    public void destroyWindowTree(ClientConnection client, XWindow window) {
        if (window.isDestroyed()) {
            return;
        }
        for (XWindow child : new ArrayList<>(window.children())) {
            destroyWindowTree(client, child);
        }
        if (window.isMapped()) {
            unmapWindow(window, false);
        }
        XWindow parent = window.parent();
        if (parent != null) {
            parent.removeChild(window);
        }
        // A window that is gone cannot be the window a grab is held on, nor the window that has the
        // input focus: X11 releases the one and reverts the other with the window, and a client that
        // grabs for a dialog or focuses one and then destroys it instead of putting things back
        // depends on both.
        server.inputFor(window.screen()).forgetGrabWindow(window);
        server.inputFor(window.screen()).forgetFocusWindow(window);
        server.events().deliverToWindow(window, EventMasks.STRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> DestroyNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .event(w.id())
                        .window(window.id())
                        .build());
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> DestroyNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .build());
        }
        // A selection is owned by a window, so it dies with it - and the clients that subscribed to
        // it hear about it, with the time its owner took it rather than the current time: nothing
        // took the selection away, it lost the window that held it.
        server.releaseSelectionsOwnedBy(window.id(), XFixesExtension.SELECTION_WINDOW_DESTROY);
        XFixesExtension xfixes = XFixesExtension.of(server);
        if (xfixes != null) {
            // The subscription itself lived on this window, so it goes too.
            xfixes.windowDestroyed(window);
        }
        server.resources().free(window.id());
        server.markScreenDirty(window);
    }

    public void changeSaveSet(ClientConnection client, ChangeSaveSet r) {
        int mode = r.getMode();
        if (mode != 0 && mode != 1) {
            throw ProtocolException.badValue(mode);
        }
        window(client, r.getWindow());
    }

    public void reparent(ClientConnection client, ReparentWindow r) {
        XWindow window = window(client, r.getWindow());
        XWindow parent = window(client, r.getParent());
        if (window == parent || window.isAncestorOf(parent)) {
            throw ProtocolException.badMatch();
        }
        if (window.isInputOnly() != parent.isInputOnly()) {
            throw ProtocolException.badMatch();
        }
        XWindow oldParent = window.parent();
        boolean wasMapped = window.isMapped();
        if (wasMapped) {
            unmapWindow(window, true);
        }
        if (oldParent != null) {
            oldParent.removeChild(window);
        }
        parent.addChild(window, -1);
        window.setPosition(r.getX(), r.getY());
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.STRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> ReparentNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .parent(parent.id())
                            .x((short) window.x())
                            .y((short) window.y())
                            .overrideRedirect(window.isOverrideRedirect())
                            .build());
        }
        if (wasMapped || window.isMapped()) {
            mapWindow(window);
        }
        server.markScreenDirty(window);
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    public void map(ClientConnection client, MapWindow r) {
        XWindow window = window(client, r.getWindow());
        if (window.isInputOnly() && !window.parent().isInputOnly()) {
            // InputOnly windows may only be children of InputOnly windows in the protocol sense;
            // real servers allow this, so it is not an error here.
        }
        if (window.isMapped()) {
            return;
        }
        // Mapping a child of an unmapped parent is not an error: the window is mapped but not
        // viewable until its parent is. Toolkits map their children before the toplevel, and
        // window managers map frames before their clients, so rejecting this broke twm.
        mapWindow(window);
    }

    public void mapSubwindows(ClientConnection client, MapSubwindows r) {
        XWindow window = window(client, r.getWindow());
        for (XWindow child : new ArrayList<>(window.children())) {
            if (!child.isMapped()) {
                mapWindow(child);
            }
        }
    }

    /**
     * Maps a window and generates the notifications and exposes that go with becoming viewable.
     * Also used internally when a parent is remapped.
     */
    public void mapWindow(XWindow window) {
        // Nothing of it showed before it was mapped, so everything it is about to cover is
        // repainted from the windows underneath and the window itself is drawn and exposed.
        mapWindow(window, visibleRegionOf(window), true);
    }

    /**
     * @param before the area the window showed before this call. For a window being mapped that is
     *     nothing, and it is nothing for its children too however long they have been mapped: what
     *     a window that has just become viewable shows is new at every depth below it.
     * @param newlyVisible whether this map is what puts the window on screen. It has to be carried
     *     down the subtree rather than re-read, because a descendant's own region looks unchanged
     *     the moment the window above it is mapped - and a window that gained nothing is sent no
     *     Expose. A toolkit maps its children before the window that holds them, so that is a
     *     toolkit that never draws them: xclock, xeyes and xcalc come up black.
     */
    private void mapWindow(XWindow window, List<Rectangle> before, boolean newlyVisible) {
        window.setMapped(true);
        boolean viewable = window.isViewable();

        server.events().deliverToWindow(window, EventMasks.STRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> MapNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .event(w.id())
                        .window(window.id())
                        .overrideRedirect(window.isOverrideRedirect())
                        .build());
        XWindow parent = window.parent();
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> MapNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .overrideRedirect(window.isOverrideRedirect())
                            .build());
            // A client that redirected its children's mapping gets a request instead of a map.
            if (parent.anyClientSelected(EventMasks.SUBSTRUCTURE_REDIRECT)) {
                server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_REDIRECT,
                        (recipient, w, child, x, y) -> MapRequestEvent.builder()
                                .firstEventOffset((byte) 0)
                                .sentEvent(false)
                                .sequenceNumber(recipient.sequenceNumber())
                                .parent(w.id())
                                .window(window.id())
                                .build());
            }
        }

        if (viewable) {
            // Painting and exposing is what visibilityChanged does, region by region: the parts of
            // the window a window on top already owns are left to it.
            visibilityChanged(window, before, visibleRegionOf(window));
            for (XWindow child : window.children()) {
                if (child.isMapped()) {
                    mapWindow(child, newlyVisible ? List.of() : visibleRegionOf(child), newlyVisible);
                }
            }
        }
        server.markScreenDirty(window);
        // A window that appears under a stationary pointer is entered by it, which is what a
        // reference server reports at the map. Leaving it to the move that follows makes the client's
        // first motion a crossing it has no reason to expect - and until it moves, a popup that
        // appeared under the pointer is a window its client does not know the pointer is in: nothing
        // in it highlights, and a click in it is read as a click outside.
        server.inputFor(window.screen()).refreshPointerWindow();
    }

    public void unmap(ClientConnection client, UnmapWindow r) {
        XWindow window = window(client, r.getWindow());
        if (!window.isMapped()) {
            return;
        }
        unmapWindow(window, true);
        server.markScreenDirty(window);
    }

    public void unmapSubwindows(ClientConnection client, UnmapSubwindows r) {
        XWindow window = window(client, r.getWindow());
        for (XWindow child : new ArrayList<>(window.children())) {
            if (child.isMapped()) {
                unmapWindow(child, true);
            }
        }
        server.markScreenDirty(window);
    }

    /** Unmaps a window, optionally reporting it to the clients that selected for it. */
    public void unmapWindow(XWindow window, boolean notify) {
        // The area it covered goes back to the windows below, which have to draw it again: their
        // pixels there are the ones this window painted over. This happens even when the unmap is
        // not reported, because a destroyed window's area has to be restored just the same - and it
        // is what the window *covers* rather than what it owns, so a frame whose content window
        // fills it gives back the dialog's picture rather than the nothing its own region is.
        List<Rectangle> before = coveredRegionOf(window);
        window.setMapped(false);
        // A window that is no longer on screen cannot be holding the pointer's implicit grab: pinning
        // events to it would leave every client without input until another button press.
        server.inputFor(window.screen()).forgetGrabWindow(window);
        // Nor can it hold the input focus - a window nobody can see must not go on receiving keys -
        // and X11 moves the focus out of a window that stops being viewable, or out of one whose
        // ancestor does. A reference server was measured doing it, and it is what a toolkit that
        // unmaps a dialog instead of putting the focus back itself is relying on.
        server.inputFor(window.screen()).forgetFocusWindow(window);
        // A window that goes away under the pointer takes the pointer out of itself and puts it in
        // whatever was underneath, so the client has to be told rather than left reporting events
        // against a window that no longer exists.
        server.inputFor(window.screen()).refreshPointerWindow();
        repaintUnder(before, window);
        if (!notify) {
            return;
        }
        server.events().deliverToWindow(window, EventMasks.STRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> UnmapNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .event(w.id())
                        .window(window.id())
                        .fromConfigure(false)
                        .build());
        XWindow parent = window.parent();
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> UnmapNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .fromConfigure(false)
                            .build());
        }
        for (XWindow child : window.children()) {
            if (child.isMapped()) {
                unmapWindow(child, true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Geometry and stacking
    // ------------------------------------------------------------------

    public void configure(ClientConnection client, ConfigureWindow r) {
        applyConfigure(window(client, r.getWindow()), r, client);
    }

    /**
     * Resizes a window on the user's behalf, as the frame the host window draws around it.
     *
     * <p>This is the one configure the server owes a client rather than the other way round: the
     * user dragged the host window's edge, so the client's window becomes that size, and the client
     * is told with the same ConfigureNotify it would get from a window manager. Everything else
     * about the change - the area it stops covering, the repaint, the request a client that
     * redirects its children's configures would get - is the same work as any other configure, which
     * is why this goes through the same path rather than resizing the window behind its back.
     */
    public void resizeWindow(XWindow window, int width, int height) {
        if (width <= 0 || height <= 0 || window.isRoot()) {
            return;
        }
        applyConfigure(window, ConfigureWindow.builder()
                .window(window.id())
                .width(width)
                .height(height)
                .build(), null);
    }

    /**
     * @param client the connection a rejected change is reported to, or null when the server is the
     *     one making it. Only the stacking path ever needs it, and no server-initiated change
     *     restacks anything.
     */
    private void applyConfigure(XWindow window, ConfigureWindow r, ClientConnection client) {
        int mask = Short.toUnsignedInt(r.getValueMask());
        int oldX = window.x();
        int oldY = window.y();
        int oldWidth = window.width();
        int oldHeight = window.height();
        int oldBorder = window.borderWidth();
        // Where it showed before the change, so the parts it stops covering can be handed back to
        // the windows underneath and the parts it starts covering can be drawn. What it *covered*,
        // so a window driven by its children - every toolkit's dialog and toplevel - hands back
        // where its content was rather than the nothing its own region is.
        List<Rectangle> before = coveredRegionOf(window);

        if (ConfigWindow.X.isEnabled(mask)) {
            window.setPosition(r.getX(), window.y());
        }
        if (ConfigWindow.Y.isEnabled(mask)) {
            window.setPosition(window.x(), r.getY());
        }
        if (ConfigWindow.WIDTH.isEnabled(mask)) {
            int width = r.getWidth();
            if (width == 0) {
                throw ProtocolException.badValue(width);
            }
            window.setSize(width, window.height());
        }
        if (ConfigWindow.HEIGHT.isEnabled(mask)) {
            int height = r.getHeight();
            if (height == 0) {
                throw ProtocolException.badValue(height);
            }
            window.setSize(window.width(), height);
        }
        if (ConfigWindow.BORDER_WIDTH.isEnabled(mask)) {
            window.setBorderWidth(r.getBorderWidth());
        }
        if (ConfigWindow.STACK_MODE.isEnabled(mask)) {
            applyStackMode(client, window, r.getSibling(), r.getStackMode());
        }

        boolean moved = window.x() != oldX || window.y() != oldY;
        boolean resized = window.width() != oldWidth || window.height() != oldHeight
                || window.borderWidth() != oldBorder;
        boolean restacked = ConfigWindow.STACK_MODE.isEnabled(mask);

        if (moved || resized || restacked) {
            // A restack changes who is visible where just as a move does, so it is reported and
            // repainted the same way - until now it did neither.
            notifyConfigure(window, r, mask, moved, resized);
            visibilityChanged(window, before, visibleRegionOf(window), moved);
            server.markScreenDirty(window);
        }

        // The children move with a resize of this window, each the way its own win gravity asks for.
        // Done after this window's own repaint rather than before it: the child's move hands back the
        // area it leaves, and the window it leaves it to has just been painted.
        if (window.width() != oldWidth || window.height() != oldHeight) {
            applyWinGravity(window, window.width() - oldWidth, window.height() - oldHeight);
        }
    }

    /**
     * Moves a window's children the way their {@code win_gravity} says they follow a resize of their
     * parent - the second half of A12, and the half that is a *server's* to do.
     *
     * <p>A window's position is relative to its parent, so a parent that grows leaves every child
     * where it was unless something moves it. The gravity names the edge or the axis whose distance
     * from the parent's is to be kept: {@code East} keeps the gap to the parent's right edge, so the
     * child moves right by the whole of the width change, and its vertical half is `Center`, so it
     * moves down by half the height change. {@code Forget} and {@code Static} do not move it at all.
     * The whole table was measured against a reference server, one gravity at a time
     * ({@code tools/GravityProbe}), because the two that are easy to get wrong are exactly the halves:
     * {@code North} moves the child by half the width change and {@code South} by all of it.
     *
     * <p><b>The reference moves the child silently</b> - no {@code ConfigureNotify}, no expose - which
     * was measured too, and it is not as odd as it sounds: the client declared the gravity, so it can
     * predict where the child went. This server cannot copy the child's pixels along (there is no
     * backing store, see the design notes), so it does what it does for any window that moves: hands
     * back the area the child vacated, paints its new area with its own background and asks it to
     * draw. That is the one divergence here, and it is deliberate - the alternative is a child
     * showing whatever used to be under it.
     */
    private void applyWinGravity(XWindow window, int deltaWidth, int deltaHeight) {
        if (deltaWidth == 0 && deltaHeight == 0) {
            return;
        }
        for (XWindow child : List.copyOf(window.children())) {
            int gravity = child.winGravity();
            if (gravity < NORTH_WEST_GRAVITY || gravity > SOUTH_EAST_GRAVITY) {
                // Forget (0) and Static (10): the child stays where it is. Checked as a range so that
                // a gravity this server does not know is treated as "do not move it" rather than as
                // an array index.
                continue;
            }
            // The nine numbered gravities are a three by three grid, north to south and west to
            // east: the row says what happens vertically and the column horizontally.
            int row = (gravity - NORTH_WEST_GRAVITY) / 3;
            int column = (gravity - NORTH_WEST_GRAVITY) % 3;
            int x = child.x() + shift(deltaWidth, column);
            int y = child.y() + shift(deltaHeight, row);
            if (x == child.x() && y == child.y()) {
                continue;
            }
            List<Rectangle> was = coveredRegionOf(child);
            child.setPosition(x, y);
            if (log.isDebugEnabled()) {
                log.debug("child 0x{} moved to {},{} by win gravity {}: the parent grew by {},{}",
                        Integer.toHexString(child.id()), x, y, gravity, deltaWidth, deltaHeight);
            }
            visibilityChanged(child, was, coveredRegionOf(child), true);
            server.markScreenDirty(child);
        }
    }

    /**
     * How far a child moves on one axis, from its gravity's column (or row): the first third of the
     * grid keeps the distance to the near edge, the middle centres it, and the last keeps the distance
     * to the far edge - so the shift is none, half or all of the change.
     */
    private static int shift(int delta, int third) {
        return third == 0 ? 0 : third == 1 ? delta / 2 : delta;
    }

    /** X's gravity numbers: 1 is north-west and 9 is south-east, in a three by three grid. */
    private static final int NORTH_WEST_GRAVITY = 1;
    private static final int SOUTH_EAST_GRAVITY = 9;

    private void notifyConfigure(XWindow window, ConfigureWindow r, int mask, boolean moved,
                                 boolean resized) {
        // The sibling directly above it now, which is how a client follows the stacking order; 0
        // (None) means it is the topmost.
        int sibling = 0;
        XWindow parent = window.parent();
        if (parent != null) {
            List<XWindow> siblings = parent.children();
            int index = siblings.indexOf(window);
            if (index >= 0 && index + 1 < siblings.size()) {
                sibling = siblings.get(index + 1).id();
            }
        }
        // Copied so the event factories can capture it.
        final int aboveSibling = sibling;
        server.events().deliverToWindow(window, EventMasks.STRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> ConfigureNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .event(w.id())
                        .window(window.id())
                        .aboveSibling(aboveSibling)
                        .x((short) window.x())
                        .y((short) window.y())
                        .width((short) window.width())
                        .height((short) window.height())
                        .borderWidth((short) window.borderWidth())
                        .overrideRedirect(window.isOverrideRedirect())
                        .build());
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> ConfigureNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .aboveSibling(aboveSibling)
                            .x((short) window.x())
                            .y((short) window.y())
                            .width((short) window.width())
                            .height((short) window.height())
                            .borderWidth((short) window.borderWidth())
                            .overrideRedirect(window.isOverrideRedirect())
                            .build());
            if (parent.anyClientSelected(EventMasks.SUBSTRUCTURE_REDIRECT)) {
                int stackMode = ConfigWindow.STACK_MODE.isEnabled(mask) ? r.getStackMode()
                        : StackMode.ABOVE.getValue();
                // Built from raw bytes: the generated ConfigureRequestEvent builder does not expose
                // value_mask, and a window manager cannot act on the request without it.
                server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_REDIRECT,
                        (recipient, w, child, x, y) -> RawEvent.builder(recipient,
                                        EventMasks.CONFIGURE_REQUEST_CODE)
                                .byteAt(1, stackMode)
                                .card32(4, w.id())
                                .card32(8, window.id())
                                .card32(12, r.getSibling())
                                .card16(16, r.getX())
                                .card16(18, r.getY())
                                .card16(20, r.getWidth())
                                .card16(22, r.getHeight())
                                .card16(24, r.getBorderWidth())
                                .card16(26, mask)
                                .build());
            }
        }
    }

    private void applyStackMode(ClientConnection client, XWindow window, int siblingId,
                                int stackMode) {
        XWindow parent = window.parent();
        if (parent == null) {
            return;
        }
        XWindow sibling = siblingId == 0 ? null : window(client, siblingId);
        if (sibling != null && sibling.parent() != parent) {
            throw ProtocolException.badMatch();
        }
        List<XWindow> children = parent.children();
        boolean topMost = children.indexOf(window) == children.size() - 1;
        if (stackMode == StackMode.ABOVE.getValue()) {
            children.remove(window);
            if (sibling == null) {
                children.add(window);
            } else {
                children.add(Math.min(children.indexOf(sibling) + 1, children.size()), window);
            }
        } else if (stackMode == StackMode.BELOW.getValue()) {
            children.remove(window);
            if (sibling == null) {
                children.add(0, window);
            } else {
                children.add(Math.max(0, children.indexOf(sibling)), window);
            }
        } else if (stackMode == StackMode.TOP_IF.getValue()) {
            if (window.isMapped()) {
                children.remove(window);
                children.add(window);
            }
        } else if (stackMode == StackMode.BOTTOM_IF.getValue()) {
            if (window.isMapped()) {
                children.remove(window);
                children.add(0, window);
            }
        } else if (stackMode == StackMode.OPPOSITE.getValue()) {
            children.remove(window);
            if (topMost) {
                children.add(0, window);
            } else {
                children.add(window);
            }
        } else {
            throw ProtocolException.badValue(stackMode);
        }
    }

    public void circulate(ClientConnection client, CirculateWindow r) {
        XWindow parent = window(client, r.getWindow());
        int direction = r.getDirection();
        if (direction != 0 && direction != 1) {
            throw ProtocolException.badValue(direction);
        }
        List<XWindow> mapped = new ArrayList<>();
        for (XWindow child : parent.children()) {
            if (child.isMapped()) {
                mapped.add(child);
            }
        }
        if (mapped.size() < 2) {
            return;
        }
        // Circulating is a restack of every mapped child, so what each of them shows changes: their
        // regions are captured before the order moves and compared after, which is what gets the
        // newly covered windows told to stop and the newly revealed ones told to draw. What is
        // captured is what each child covers - its own children included, as everywhere the
        // question is "what does this leave behind" - so a frame is not read as showing nothing.
        Map<XWindow, List<Rectangle>> before = new LinkedHashMap<>();
        for (XWindow child : mapped) {
            before.put(child, coveredRegionOf(child));
        }
        for (XWindow child : mapped) {
            int index = parent.children().indexOf(child);
            parent.children().remove(index);
            if (direction == Circulate.RAISE_LOWEST.getValue()) {
                parent.children().add(child);
            } else {
                parent.children().add(0, child);
            }
            server.events().deliverToWindow(child, EventMasks.STRUCTURE_NOTIFY,
                    (recipient, w, c, x, y) -> CirculateNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(child.id())
                            .place((byte) (direction == Circulate.RAISE_LOWEST.getValue()
                                    ? Place.ON_TOP.getValue() : Place.ON_BOTTOM.getValue()))
                            .build());
        }
        for (XWindow child : mapped) {
            visibilityChanged(child, before.get(child), visibleRegionOf(child));
        }
        server.markScreenDirty(parent);
    }

    public void getGeometry(ClientConnection client, GetGeometry r) {
        XResource drawable = server.resources().requireDrawable(client, r.getDrawable());
        if (drawable instanceof XWindow window) {
            client.sendReply(GetGeometryReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .depth((byte) window.depth())
                    .root(window.root().id())
                    .x((short) window.x())
                    .y((short) window.y())
                    .width((short) window.width())
                    .height((short) window.height())
                    .borderWidth((short) window.borderWidth())
                    .build());
        } else {
            XPixmap pixmap = (XPixmap) drawable;
            client.sendReply(GetGeometryReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .depth((byte) pixmap.depth())
                    .root(pixmap.screen().root().id())
                    .x((short) 0)
                    .y((short) 0)
                    .width((short) pixmap.width())
                    .height((short) pixmap.height())
                    .borderWidth((short) 0)
                    .build());
        }
    }

    public void queryTree(ClientConnection client, QueryTree r) {
        XWindow window = window(client, r.getWindow());
        List<Integer> children = new ArrayList<>();
        for (XWindow child : window.children()) {
            children.add(child.id());
        }
        client.sendReply(QueryTreeReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .root(window.root().id())
                .parent(window.parent() == null ? 0 : window.parent().id())
                .children(IntLists.immutable.ofAll(children))
                .build());
    }

    public void queryBestSize(ClientConnection client, QueryBestSize r) {
        // Every size is acceptable: the framebuffer has no tiling constraints and the cursor is
        // drawn from an ARGB image rather than a hardware sprite.
        server.resources().requireDrawable(client, r.getDrawable());
        client.sendReply(QueryBestSizeReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .width(r.getWidth())
                .height(r.getHeight())
                .build());
    }

    public void translateCoordinates(ClientConnection client, TranslateCoordinates r) {
        XWindow src = window(client, r.getSrcWindow());
        XWindow dst = window(client, r.getDstWindow());
        int screenX = src.absoluteX() + r.getSrcX();
        int screenY = src.absoluteY() + r.getSrcY();
        XWindow child = dst.windowAt(screenX, screenY);
        int childId = child == dst ? 0 : child.id();
        client.sendReply(TranslateCoordinatesReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .sameScreen(true)
                .child(childId)
                .dstX((short) (screenX - dst.absoluteX()))
                .dstY((short) (screenY - dst.absoluteY()))
                .build());
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    /**
     * Fills the window's inner rectangle with its background, clipped to the part of it that is
     * visible.
     *
     * <p>The visible region is applied here rather than left to the caller: a window's background
     * painted over the area a window on top of it occupies is exactly the way a dialog gets erased,
     * and every caller - a map, a configure, a clear, a resize - would have to remember not to.
     */
    public void paintBackground(XWindow window, Rectangle clip) {
        if (window.isInputOnly()) {
            return;
        }
        Framebuffer framebuffer = window.screen().framebuffer();
        for (Rectangle piece : window.visibleRegion()) {
            Rectangle area = window.absoluteBounds().intersection(clip).intersection(piece);
            if (area.isEmpty()) {
                continue;
            }
            if (window.isBackgroundPixmap() && window.backgroundPixmap() instanceof XPixmap tile) {
                tileInto(framebuffer, tile, area, window.absoluteX(), window.absoluteY());
            } else {
                framebuffer.fillRect(area.x, area.y, area.width, area.height,
                        window.backgroundPixel());
            }
        }
    }

    private void paintBorder(XWindow window, Rectangle clip) {
        int border = window.borderWidth();
        if (border == 0) {
            return;
        }
        Rectangle outer = window.absoluteBoundsWithBorder().intersection(clip);
        if (outer.isEmpty()) {
            return;
        }
        Rectangle inner = window.absoluteBounds();
        Framebuffer framebuffer = window.screen().framebuffer();
        XPixmap tile = tileOf(window);
        int colour = window.borderPixel();
        // Four bands around the inner rectangle.
        fill(framebuffer, tile, colour, outer.x, outer.y, outer.width, inner.y - outer.y);
        fill(framebuffer, tile, colour, outer.x, inner.y + inner.height, outer.width,
                outer.y + outer.height - (inner.y + inner.height));
        fill(framebuffer, tile, colour, outer.x, inner.y, inner.x - outer.x, inner.height);
        fill(framebuffer, tile, colour, inner.x + inner.width, inner.y,
                outer.x + outer.width - (inner.x + inner.width), inner.height);
    }

    private XPixmap tileOf(XWindow window) {
        return window.isBorderPixmap() && window.borderPixmap() instanceof XPixmap pixmap
                ? pixmap : null;
    }

    /** Fills one border band, with the border pixmap when there is one and the pixel otherwise. */
    private void fill(Framebuffer framebuffer, XPixmap tile, int colour, int x, int y, int w,
                      int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (tile == null) {
            framebuffer.fillRect(x, y, w, h, colour);
            return;
        }
        tileInto(framebuffer, tile, new Rectangle(x, y, w, h), 0, 0);
    }

    /** Tiles a pixmap across an area, aligned so that (originX, originY) is tile position 0,0. */
    private void tileInto(Framebuffer framebuffer, XPixmap tile, Rectangle area, int originX,
                          int originY) {
        if (tile.width() <= 0 || tile.height() <= 0) {
            return;
        }
        for (int y = area.y; y < area.y + area.height; y++) {
            int ty = Math.floorMod(y - originY, tile.height());
            for (int x = area.x; x < area.x + area.width; x++) {
                int tx = Math.floorMod(x - originX, tile.width());
                framebuffer.setPixel(x, y, tile.pixel(tx, ty));
            }
        }
    }

    /**
     * Tells the clients watching a window that its visibility changed.
     *
     * <p>Toolkits use this to decide when a window is worth drawing: a window that is mapped but
     * never reported as visible can leave an application waiting for a notification that never
     * comes, with its window sitting unpainted. The server keeps no obscured-region tracking, so a
     * viewable window is reported as unobscured - which is right for the common case of a window
     * that is on top.
     */
    public void deliverVisibilityNotify(XWindow window, int state) {
        if (!window.isViewable()) {
            return;
        }
        server.events().deliverToWindow(window, EventMasks.VISIBILITY_CHANGE,
                (recipient, w, child, x, y) -> VisibilityNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .window(w.id())
                        .state((byte) state)
                        .build());
    }

    /**
     * Sends an Expose for the part of {@code rect} that is actually visible.
     *
     * <p>{@code rect} arrives in screen coordinates, which is what every caller works in, but the
     * event carries window relative ones. Without the translation a window away from the origin is
     * told to repaint a rectangle that lies outside it - a window at y=600 is told "repaint from
     * y=600" - and a client that believes the rectangle redraws nothing at all, leaving the window
     * as whatever the framebuffer held before: a black window that never paints.
     */
    public void deliverExpose(XWindow window, Rectangle rect) {
        if (!window.isViewable()) {
            return;
        }
        // A window that is partly covered is told about the visible pieces only, and the count field
        // tells it how many more exposes are on the way.
        List<Rectangle> pieces = ShapeState.intersect(List.of(rect), window.visibleRegion());
        for (int i = 0; i < pieces.size(); i++) {
            Rectangle visible = pieces.get(i);
            int x = visible.x - window.absoluteX();
            int y = visible.y - window.absoluteY();
            short remaining = (short) (pieces.size() - 1 - i);
            server.events().deliverToWindow(window, EventMasks.EXPOSURE,
                    (recipient, w, child, eventX, eventY) -> ExposeEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .window(w.id())
                            .x((short) x)
                            .y((short) y)
                            .width((short) visible.width)
                            .height((short) visible.height)
                            .count(remaining)
                            .build());
        }
    }

    /**
     * Repaints and notifies after a window's visibility changed.
     *
     * <p>{@code before} and {@code after} are the window's visible regions, in screen coordinates,
     * captured around whatever changed its geometry or its place in the stacking order.
     *
     * <p>The parts it gained have to be drawn again: their pixels are whatever was on top of it
     * until now. The parts it lost belong to the windows underneath, which have to be asked to draw
     * as well - nothing of theirs is kept anywhere, so a revealed area is restored by painting the
     * lower window's background and letting its client repaint the content.
     */
    private void visibilityChanged(XWindow window, List<Rectangle> before, List<Rectangle> after) {
        visibilityChanged(window, before, after, false);
    }

    /**
     * @param moved true when the window itself moved rather than something about its stacking: a
     *     move leaves the window's own pixels behind - nothing here copies them along - so the whole
     *     of its new area has to be drawn again, not just the strip it gained. Without that a moved
     *     window shows a mix of its old content and whatever it used to be covering, which is what
     *     makes a dragged window look like it stopped working.
     */
    private void visibilityChanged(XWindow window, List<Rectangle> before, List<Rectangle> after,
                                   boolean moved) {
        for (Rectangle gained : moved ? after : ShapeState.subtract(after, before)) {
            paintBackground(window, gained);
            deliverExpose(window, gained);
        }
        if (window.isViewable()) {
            deliverVisibilityNotify(window, visibilityStateOf(window));
        }
        // The windows below keep what they have where this window still covers them, so only the
        // area it stopped covering is redrawn.
        repaintUnder(ShapeState.subtract(before, after), window);
        // The border comes last: it sits outside the window's content, on top of whatever is below
        // it, so painting it with the content only let the windows underneath - the root included -
        // cover it again. Its own visible region is its clip, because the content region stops at
        // the content rectangle and would leave all four bands zero wide.
        for (Rectangle edge : window.outerVisibleRegion()) {
            paintBorder(window, edge);
        }
    }

    /** The visible region of a window, or nothing when it is not on screen at all. */
    private List<Rectangle> visibleRegionOf(XWindow window) {
        return window != null && window.isViewable() && !window.isInputOnly()
                ? window.visibleRegion() : List.of();
    }

    /**
     * The area a window covers - its children included - or nothing when it is not on screen.
     *
     * <p>This is the region to ask for when the question is "what does this window leave behind
     * when it goes away or moves", and it is deliberately not {@link #visibleRegionOf}: a toolkit's
     * toplevel is a frame whose content window covers it entirely, so the frame's own region is
     * empty and giving back only that restores nothing at all. Read it before the window is
     * unmapped or moved - the answer is about where it was.
     */
    private List<Rectangle> coveredRegionOf(XWindow window) {
        return window != null && window.isViewable() && !window.isInputOnly()
                ? window.coveredRegion() : List.of();
    }

    /**
     * A window's bounding shape changed: hand back the area it no longer covers, and ask it to draw
     * the part it has just gained.
     *
     * <p>A shape set on a window that is already on screen changes what is on it just as surely as a
     * move does, and the two halves are the same as a move's: the area the shape took away belongs to
     * whatever is underneath again (its pixels are still the shaped window's - nothing copies window
     * contents along), and the area it gained has to be drawn by the client, which is told with an
     * {@code Expose}.
     */
    public void shapeChanged(XWindow window, List<Rectangle> lost, List<Rectangle> gained) {
        if (!window.isViewable()) {
            return;
        }
        if (!lost.isEmpty()) {
            repaintUnder(lost, window);
        }
        for (Rectangle area : gained) {
            paintBackground(window, area);
            deliverExpose(window, area);
        }
    }

    /**
     * Repaints the parts of the windows under {@code window} that {@code areas} uncovered.
     *
     * <p>Walked bottom to top so the topmost window of the ones below is painted last, and clipped
     * to each window's own visible region, which is what keeps a window that is itself covered from
     * painting where it does not show.
     */
    private void repaintUnder(List<Rectangle> areas, XWindow window) {
        if (areas.isEmpty()) {
            return;
        }
        for (XWindow candidate : stackingOrder(window)) {
            if (candidate == window || candidate.isInputOnly() || !candidate.isViewable()
                    || candidate.isAbove(window)) {
                // A window above the one that changed was never covered by it.
                continue;
            }
            List<Rectangle> visible = candidate.visibleRegion();
            for (Rectangle area : areas) {
                for (Rectangle piece : visible) {
                    Rectangle part = area.intersection(piece);
                    if (!part.isEmpty()) {
                        paintBackground(candidate, part);
                        // The border ring is outside the candidate's content, so it is the area this
                        // repaint is about that says whether any of it needs drawing - and the ring
                        // already excludes the windows above, which have just been painted.
                        for (Rectangle edge : candidate.outerVisibleRegion()) {
                            if (!edge.intersection(area).isEmpty()) {
                                paintBorder(candidate, edge);
                            }
                        }
                        deliverExpose(candidate, part);
                    }
                }
            }
        }
    }

    /** The windows of the tree, a parent before its children and siblings bottom to top. */
    private List<XWindow> stackingOrder(XWindow below) {
        List<XWindow> order = new ArrayList<>();
        collectStackingOrder(below.root(), order);
        return order;
    }

    private static void collectStackingOrder(XWindow window, List<XWindow> order) {
        order.add(window);
        for (XWindow child : window.children()) {
            collectStackingOrder(child, order);
        }
    }

    /**
     * How much of a window is visible, for {@code VisibilityNotify}: invisible when nothing of it
     * shows, partially obscured when something covers part of it, unobscured otherwise.
     */
    private int visibilityStateOf(XWindow window) {
        List<Rectangle> visible = window.visibleRegion();
        if (visible.isEmpty()) {
            return Visibility.FULLY_OBSCURED.getValue();
        }
        return ShapeState.extents(visible).equals(window.clipRect())
                ? Visibility.UNOBSCURED.getValue()
                : Visibility.PARTIALLY_OBSCURED.getValue();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private XWindow window(ClientConnection client, int id) {
        return (XWindow) server.resources().require(client, id, XResource.CLASS_WINDOW, false);
    }
}
