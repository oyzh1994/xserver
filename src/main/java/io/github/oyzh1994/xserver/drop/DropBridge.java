package io.github.oyzh1994.xserver.drop;

import com.github.moaxcp.x11.protocol.xproto.SelectionNotifyEvent;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.resource.XProperty;
import io.github.oyzh1994.xserver.resource.XResource;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A drag from the host's desktop into a forwarded application, which is XDND.
 *
 * <p>X's drag and drop is client to client, and it is the *source* that drives it: it takes the
 * {@code XdndSelection}, sends {@code XdndEnter}, {@code XdndPosition} and {@code XdndDrop} to the
 * window under the pointer, and hands the data over when that window asks for it. A forwarded
 * application is a target that works perfectly well - it has been dragging and dropping on real
 * desktops all along - so what is missing is a source on the X side that speaks for the host's drag.
 * This class is that source.
 *
 * <p>The awkward part is which side of the protocol runs where. The user drags a file from Finder
 * onto the host window; the platform reports the drop to the backend, and from then on nothing is
 * interactive - the X side is a short conversation that has to look exactly like a drag a client
 * started itself. So the bridge runs it in one go: take the selection, tell the window under the
 * pointer where the drag is and what it carries, drop, and answer the {@code ConvertSelection} that
 * comes back with a {@code text/uri-list}.
 *
 * <p>Dragging *out* of a forwarded application is deliberately not here. A JavaFX window cannot
 * start a platform drag - the platform's drag is the user's gesture and the toolkit only reports
 * one - so there is nothing to hand a client's drag to. That half needs a native drag source, and it
 * is recorded as the open part of this item rather than half-built.
 *
 * <p>Threading follows {@link io.github.oyzh1994.xserver.clipboard.ClipboardBridge}: the bridge's
 * state is guarded by the server lock, which request threads already hold, and the one thing that
 * could block - the platform's side of a drop - happens before any of it.
 */
public final class DropBridge {

    private static final Logger log = LoggerFactory.getLogger(DropBridge.class);

    /** The selection a drag's data is served through, by the convention every toolkit implements. */
    private static final String XDND_SELECTION = "XdndSelection";

    /** The property a window publishes to say it takes drops, and the version it speaks. */
    private static final String XDND_AWARE = "XdndAware";

    /** What the drag carries: one URI per line, which is what a file drag is. */
    private static final String URI_LIST = "text/uri-list";

    /** The XDND version claimed: 5 is what toolkits have spoken for twenty years. */
    private static final int VERSION = 5;

    /** How long to wait for the target to answer the position before dropping anyway. */
    private static final long STATUS_TIMEOUT_MILLIS = 700;

    private final XServer server;
    /** The bridge's own window: the drag's source, and what the selection is owned by. */
    private final XWindow window;

    private final int selectionAtom;
    private final int uriListAtom;
    private final int textAtom;
    private final int awareAtom;
    private final int enterAtom;
    private final int positionAtom;
    private final int dropAtom;
    private final int finishedAtom;
    private final int actionCopyAtom;

    // Guarded by the server lock.
    /** What the host handed over, as URIs, while a drag is in flight. */
    private List<String> uris = List.of();
    /** The window being dragged onto, or 0. */
    private int target;
    private long droppedAt;
    private boolean waitingForStatus;

    public DropBridge(XServer server) {
        this.server = server;
        window = new XWindow(server.nextServerResourceId(), null, server.screen(),
                server.screen().root(), server.screen().root().depth(), XWindow.INPUT_ONLY,
                server.screen().root().visual(), 0, 0, 1, 1, 0);
        selectionAtom = server.atoms().intern(XDND_SELECTION);
        uriListAtom = server.atoms().intern(URI_LIST);
        textAtom = server.atoms().intern("text/plain");
        awareAtom = server.atoms().intern(XDND_AWARE);
        enterAtom = server.atoms().intern("XdndEnter");
        positionAtom = server.atoms().intern("XdndPosition");
        dropAtom = server.atoms().intern("XdndDrop");
        finishedAtom = server.atoms().intern("XdndFinished");
        actionCopyAtom = server.atoms().intern("XdndActionCopy");
    }

    /**
     * The window the drag's selection is owned by, and the one clients answer to.
     *
     * <p>Never mapped, and {@code InputOnly}: it is a protocol participant, not a piece of user
     * interface - the drag the user sees is the platform's own.
     */
    public void start() {
        server.resources().add(window);
        // The bridge is a source, not a target: nothing drops onto its own window, so it does not
        // publish XdndAware for itself.
        log.info("host drags into forwarded applications are bridged to XDND");
    }

    public void stop() {
    }

    /** The window the bridge owns the drag's selection with. */
    public int windowId() {
        return window.id();
    }

    public boolean ownsWindow(int windowId) {
        return window.id() == windowId;
    }

    // ------------------------------------------------------------------
    // From the host, under the server lock
    // ------------------------------------------------------------------

    /**
     * The user dropped files from the host's desktop onto a host window.
     *
     * @param paths the dropped files, as paths the host understands
     * @param x     where on the screen it happened, in framebuffer coordinates - the same ones
     *     {@code InputSink} reports, and what XDND wants as root coordinates
     */
    public void hostDropped(List<String> paths, int x, int y) {
        if (paths.isEmpty()) {
            return;
        }
        XWindow under = server.inputFor(server.screen()).pointerWindow();
        XWindow dropTarget = awareTarget(under);
        if (dropTarget == null) {
            // Nothing under the pointer takes drops, which is not an error: a drag onto an
            // application that does not do drag and drop is a drag that did nothing, the same as it
            // would be on a real desktop.
            log.debug("a drop of {} file(s) landed on a window that does not take drops", paths.size());
            return;
        }
        uris = toUris(paths);
        target = dropTarget.id();
        droppedAt = System.currentTimeMillis();
        // The source owns the selection before anything is told about the drag: the target asks for
        // the data through it, and a target that asks in the middle of the conversation has to find
        // an owner.
        server.setSelectionOwner(XDND_SELECTION, window.id(), server.events().time(),
                io.github.oyzh1994.xserver.extension.XFixesExtension.SELECTION_SET_OWNER);
        send(enterAtom, VERSION << 24, uriListAtom, textAtom, 0);
        // The pointer's position and not the drop's: the target tracks a drag by the positions it is
        // sent, and this is the one the drag ended at.
        send(positionAtom, 0, (x << 16) | (y & 0xFFFF), server.events().time(), actionCopyAtom);
        // A target answers the position with XdndStatus and is dropped on afterwards. Toolkits that
        // answer promptly get their answer honoured; one that says nothing is dropped on anyway once
        // the wait is up, because a drag the user has already finished cannot hang on it.
        waitingForStatus = true;
        log.debug("dropping {} file(s) on 0x{}", paths.size(), Integer.toHexString(target));
    }

    /**
     * Advances a drag that is waiting for the target's answer. Called from the display tick, so a
     * target that never answers is still dropped on.
     */
    public void pump() {
        if (!waitingForStatus || System.currentTimeMillis() - droppedAt < STATUS_TIMEOUT_MILLIS) {
            return;
        }
        waitingForStatus = false;
        send(dropAtom, 0, server.events().time());
    }

    /** A client answered something the bridge sent it. */
    public void clientMessage(int messageType, int[] data, ClientConnection from) {
        if (!waitingForStatus || data.length == 0 || data[0] != window.id()) {
            return;
        }
        if (messageType == finishedAtom) {
            // The target has taken the data (or refused it) and the conversation is over.
            waitingForStatus = false;
            target = 0;
            uris = List.of();
            server.setSelectionOwner(XDND_SELECTION, 0, server.events().time(),
                    io.github.oyzh1994.xserver.extension.XFixesExtension.SELECTION_SET_OWNER);
            return;
        }
        // XdndStatus: bit 0 of the flags says the target will take this drop.
        boolean willTake = (data[1] & 1) != 0;
        waitingForStatus = false;
        if (willTake) {
            send(dropAtom, 0, server.events().time());
        } else {
            log.debug("0x{} refused the drop", Integer.toHexString(target));
            target = 0;
            uris = List.of();
        }
    }

    /**
     * Answers a target's {@code ConvertSelection} for the drag's data - which is the only way the
     * files get to it. Lazy, like every X selection: the target asks when it is ready.
     */
    public void serve(XWindow requestor, int selection, int target, int property, int time) {
        if (selection != selectionAtom || property == 0) {
            refuse(requestor, selection, target, time);
            return;
        }
        if (target != uriListAtom && target != textAtom) {
            // TARGETS and anything else: this bridge carries files and nothing else.
            refuse(requestor, selection, target, time);
            return;
        }
        // One URI per line, CRLF separated, which is what text/uri-list is - and what every
        // file-manager drag looks like to the application that receives it.
        StringBuilder list = new StringBuilder();
        for (String uri : uris) {
            list.append(uri).append("\r\n");
        }
        byte[] value = list.toString().getBytes(StandardCharsets.UTF_8);
        int type = server.atoms().intern(URI_LIST, true);
        requestor.properties().put(property, new XProperty(property, type, 8, value));
        server.atomHandlers().notifyProperty(requestor, property,
                io.github.oyzh1994.xserver.handler.AtomHandlers.NEW_VALUE);
        sendNotify(requestor, selection, target, property, time);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * The window under the pointer that takes drops, or null.
     *
     * <p>Looked for up the tree, because a toolkit puts the property on the window that should
     * receive the drag and a child of it is usually what the pointer is actually over.
     */
    private XWindow awareTarget(XWindow window) {
        for (XWindow candidate = window; candidate != null; candidate = candidate.parent()) {
            XProperty aware = candidate.properties().get(awareAtom);
            if (aware != null && aware.format() == 32) {
                return candidate;
            }
        }
        return null;
    }

    /** A {@code ClientMessage} to the target, with no event mask - the only form XDND uses. */
    private void send(int messageType, int... data) {
        if (target == 0) {
            return;
        }
        XResource resource = server.resources().get(target);
        if (!(resource instanceof XWindow destination) || destination.owner() == null) {
            target = 0;
            return;
        }
        ClientConnection owner = destination.owner();
        server.events().send(owner, com.github.moaxcp.x11.protocol.xproto.ClientMessageEvent.builder()
                .firstEventOffset((byte) 0)
                .sentEvent(false)
                .sequenceNumber(owner.sequenceNumber())
                .window(destination.id())
                .type(messageType)
                .format((byte) 32)
                .data(new com.github.moaxcp.x11.protocol.xproto.ClientMessageData32(pad(data)))
                .build());
    }

    /** The message's five data words, with the source window first as every XDND message has it. */
    private int[] pad(int[] data) {
        int[] words = new int[5];
        words[0] = window.id();
        for (int i = 0; i < Math.min(4, data.length); i++) {
            words[i + 1] = data[i];
        }
        return words;
    }

    private void refuse(XWindow requestor, int selection, int target, int time) {
        sendNotify(requestor, selection, target, 0, time);
    }

    private void sendNotify(XWindow requestor, int selection, int target, int property, int time) {
        ClientConnection owner = requestor.owner();
        if (owner == null) {
            return;
        }
        server.events().send(owner, SelectionNotifyEvent.builder()
                .firstEventOffset((byte) 0)
                .sentEvent(false)
                .sequenceNumber(owner.sequenceNumber())
                .time(time)
                .requestor(requestor.id())
                .selection(selection)
                .target(target)
                .property(property)
                .build());
    }

    /** The dropped paths as URIs, which is what a target understands. */
    private static List<String> toUris(List<String> paths) {
        List<String> uris = new ArrayList<>(paths.size());
        for (String path : paths) {
            uris.add(java.io.File.separatorChar == '\\'
                    ? "file:///" + path.replace('\\', '/').replace(" ", "%20")
                    : "file://" + path.replace(" ", "%20"));
        }
        return uris;
    }
}
