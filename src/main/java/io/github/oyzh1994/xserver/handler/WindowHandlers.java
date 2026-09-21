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
import com.github.moaxcp.x11.protocol.xproto.WindowClass;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.event.RawEvent;
import io.github.oyzh1994.xserver.resource.ResourceTable;
import io.github.oyzh1994.xserver.resource.XColormap;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XPixmap;
import io.github.oyzh1994.xserver.resource.XResource;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.impl.factory.primitive.IntLists;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

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
            visual = server.screen().visualById(r.getVisual());
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

        XWindow window = new XWindow(wid, client, server.screen(), parent, depth, windowClass,
                visual, r.getX(), r.getY(), Short.toUnsignedInt(r.getWidth()),
                Short.toUnsignedInt(r.getHeight()), borderWidth);
        window.setBackgroundArgb(0xFF000000);
        window.setBorderArgb(0xFFFFFFFF);

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
                window.setBackgroundArgb(0xFF000000);
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
                    window.setBorderArgb(0xFF000000);
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
                .visual(window.visual() == null ? server.screen().rootVisual().id()
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
        server.resources().free(window.id());
        server.markScreenDirty();
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
        server.markScreenDirty();
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
            window.markNeedsFullExpose();
            paintWindow(window);
            deliverExpose(window, window.absoluteBounds());
            for (XWindow child : window.children()) {
                if (child.isMapped()) {
                    mapWindow(child);
                }
            }
        }
        server.markScreenDirty();
    }

    public void unmap(ClientConnection client, UnmapWindow r) {
        XWindow window = window(client, r.getWindow());
        if (!window.isMapped()) {
            return;
        }
        unmapWindow(window, true);
        server.markScreenDirty();
    }

    public void unmapSubwindows(ClientConnection client, UnmapSubwindows r) {
        XWindow window = window(client, r.getWindow());
        for (XWindow child : new ArrayList<>(window.children())) {
            if (child.isMapped()) {
                unmapWindow(child, true);
            }
        }
        server.markScreenDirty();
    }

    /** Unmaps a window, optionally reporting it to the clients that selected for it. */
    public void unmapWindow(XWindow window, boolean notify) {
        window.setMapped(false);
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
        XWindow window = window(client, r.getWindow());
        int mask = Short.toUnsignedInt(r.getValueMask());
        int oldX = window.x();
        int oldY = window.y();
        int oldWidth = window.width();
        int oldHeight = window.height();
        int oldBorder = window.borderWidth();

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

        if (moved || resized) {
            notifyConfigure(window, r, mask, moved, resized);
            if (window.isViewable()) {
                paintWindow(window);
                deliverExpose(window, window.absoluteBounds());
            }
            server.markScreenDirty();
        }
    }

    private void notifyConfigure(XWindow window, ConfigureWindow r, int mask, boolean moved,
                                 boolean resized) {
        server.events().deliverToWindow(window, EventMasks.STRUCTURE_NOTIFY,
                (recipient, w, child, x, y) -> ConfigureNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .event(w.id())
                        .window(window.id())
                        .aboveSibling(0)
                        .x((short) window.x())
                        .y((short) window.y())
                        .width((short) window.width())
                        .height((short) window.height())
                        .borderWidth((short) window.borderWidth())
                        .overrideRedirect(window.isOverrideRedirect())
                        .build());
        XWindow parent = window.parent();
        if (parent != null) {
            server.events().deliverToWindow(parent, EventMasks.SUBSTRUCTURE_NOTIFY,
                    (recipient, w, child, x, y) -> ConfigureNotifyEvent.builder()
                            .firstEventOffset((byte) 0)
                            .sentEvent(false)
                            .sequenceNumber(recipient.sequenceNumber())
                            .event(w.id())
                            .window(window.id())
                            .aboveSibling(0)
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
        server.markScreenDirty();
    }

    public void getGeometry(ClientConnection client, GetGeometry r) {
        XResource drawable = server.resources().requireDrawable(client, r.getDrawable());
        if (drawable instanceof XWindow window) {
            client.sendReply(GetGeometryReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .depth((byte) window.depth())
                    .root(server.rootWindow().id())
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
                    .root(server.rootWindow().id())
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
                .root(server.rootWindow().id())
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

    /** Paints a window's background and border into the framebuffer, honouring its clip. */
    public void paintWindow(XWindow window) {
        if (window.isInputOnly() || !window.isViewable()) {
            return;
        }
        Rectangle clip = window.clipRect();
        if (clip.isEmpty()) {
            return;
        }
        paintBackground(window, clip);
        // The border is clipped only by the ancestors: using the content clip here would remove
        // the border entirely, which is exactly what happened until a client with bordered child
        // windows - xcalc's buttons - was run against the server.
        paintBorder(window, window.outerClipRect());
    }

    /** Fills the window's inner rectangle with its background, clipped. */
    public void paintBackground(XWindow window, Rectangle clip) {
        if (window.isInputOnly()) {
            return;
        }
        Rectangle area = window.absoluteBounds().intersection(clip);
        if (area.isEmpty()) {
            return;
        }
        Framebuffer framebuffer = server.screen().framebuffer();
        if (window.isBackgroundPixmap() && window.backgroundPixmap() instanceof XPixmap tile) {
            tileInto(framebuffer, tile, area, window.absoluteX(), window.absoluteY());
        } else {
            framebuffer.fillRect(area.x, area.y, area.width, area.height, window.backgroundPixel());
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
        Framebuffer framebuffer = server.screen().framebuffer();
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

    /** Sends an Expose for the part of {@code rect} that is actually visible. */
    public void deliverExpose(XWindow window, Rectangle rect) {
        if (!window.isViewable()) {
            return;
        }
        Rectangle visible = rect.intersection(window.clipRect());
        if (visible.isEmpty()) {
            return;
        }
        server.events().deliverToWindow(window, EventMasks.EXPOSURE,
                (recipient, w, child, x, y) -> ExposeEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(recipient.sequenceNumber())
                        .window(w.id())
                        .x((short) visible.x)
                        .y((short) visible.y)
                        .width((short) visible.width)
                        .height((short) visible.height)
                        .count((short) 0)
                        .build());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private XWindow window(ClientConnection client, int id) {
        return (XWindow) server.resources().require(client, id, XResource.CLASS_WINDOW, false);
    }
}
