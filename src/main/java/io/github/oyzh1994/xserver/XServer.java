package io.github.oyzh1994.xserver;

import com.github.moaxcp.x11.protocol.X11Input;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ConnectionSetup;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.client.RequestDispatcher;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.event.EventDispatcher;
import io.github.oyzh1994.xserver.event.RawEvent;
import io.github.oyzh1994.xserver.extension.Extension;
import io.github.oyzh1994.xserver.extension.ExtensionRegistry;
import io.github.oyzh1994.xserver.handler.AtomHandlers;
import io.github.oyzh1994.xserver.handler.DrawHandlers;
import io.github.oyzh1994.xserver.handler.InputHandlers;
import io.github.oyzh1994.xserver.handler.MiscHandlers;
import io.github.oyzh1994.xserver.handler.ResourceHandlers;
import io.github.oyzh1994.xserver.handler.WindowHandlers;
import io.github.oyzh1994.xserver.input.InputState;
import io.github.oyzh1994.xserver.net.ClientListener;
import io.github.oyzh1994.xserver.resource.AtomTable;
import io.github.oyzh1994.xserver.resource.ResourceTable;
import io.github.oyzh1994.xserver.resource.XColormap;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XFont;
import io.github.oyzh1994.xserver.resource.XProperty;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The X server itself: screens, the resource tables, connected clients and the request loop.
 */
public final class XServer {

    private static final Logger log = LoggerFactory.getLogger(XServer.class);

    /** Client index 0 is reserved for the server, which owns the root window and colormaps. */
    public static final int SERVER_CLIENT_INDEX = 0;

    private final ServerOptions options;
    private final ResourceTable resources = new ResourceTable();
    private final AtomTable atoms = new AtomTable();
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
    private final BitSet usedClientIndices = new BitSet(ClientConnection.MAX_CLIENTS);
    private final Map<String, Integer> selectionOwners = new HashMap<>();
    private final List<io.github.oyzh1994.xserver.handler.InputHandlers.PassiveGrab> passiveGrabs =
            new ArrayList<>();

    /** Held while a request executes; {@code GrabServer} holds it across requests. */
    private final ReentrantLock serverLock = new ReentrantLock(true);

    private final Screen screen;
    private final List<Screen> screens = new ArrayList<>();
    private final XWindow rootWindow;
    private final XColormap rootColormap;
    private final XCursor rootCursor;
    private final int defaultFontId;

    private final ConnectionSetup connectionSetup = new ConnectionSetup(this);
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final WindowHandlers windowHandlers = new WindowHandlers(this);
    private final DrawHandlers drawHandlers = new DrawHandlers(this);
    private final ResourceHandlers resourceHandlers = new ResourceHandlers(this);
    private final AtomHandlers atomHandlers = new AtomHandlers(this);
    private final InputHandlers inputHandlers = new InputHandlers(this);
    private final MiscHandlers miscHandlers = new MiscHandlers(this);
    private final RequestDispatcher dispatcher = new RequestDispatcher(this, windowHandlers,
            drawHandlers, resourceHandlers, atomHandlers, inputHandlers, miscHandlers);
    private final EventDispatcher events = new EventDispatcher(this);
    private final InputState input = new InputState(this);

    private DisplayBackend backend;
    private ClientListener listener;

    private ClientConnection serverGrabHolder;
    /** How many times the holder called GrabServer without a matching UngrabServer. */
    private int serverGrabDepth;

    private volatile boolean running;
    private long startTime;
    /**
     * Ids for resources the server creates itself. Starts at 1 because 0 is {@code None} in the
     * protocol and a resource named 0 could never be referenced.
     */
    private int resourceIdCounter = 1;

    public XServer(ServerOptions options) {
        this.options = options;
        // Also applied here, not only in main(), so a server constructed directly - as tests and
        // embedders do - still gets a screen that fits the display it will be shown on.
        options.fitToDisplay();
        this.usedClientIndices.set(SERVER_CLIENT_INDEX);

        // A modern desktop is TrueColor first: 24 bit for the root, 32 bit ARGB for translucency,
        // plus the low depth visuals clients still probe for.
        List<VisualInfo> visuals = new ArrayList<>();
        int nextVisualId = 0x0200;
        VisualInfo trueColor24 = VisualInfo.trueColor24(nextVisualId++);
        VisualInfo argb32 = VisualInfo.argb32(nextVisualId++);
        VisualInfo trueColor16 = VisualInfo.trueColor16(nextVisualId++);
        VisualInfo trueColor8 = VisualInfo.trueColor8(nextVisualId++);
        VisualInfo pseudoColor8 = VisualInfo.pseudoColor8(nextVisualId++);
        VisualInfo staticGray8 = VisualInfo.staticGray8(nextVisualId++);
        VisualInfo staticGray1 = VisualInfo.staticGray1(nextVisualId++);
        visuals.add(trueColor24);
        visuals.add(argb32);
        visuals.add(trueColor16);
        visuals.add(trueColor8);
        visuals.add(pseudoColor8);
        visuals.add(staticGray8);
        visuals.add(staticGray1);

        this.screen = new Screen(0, options.width(), options.height(), visuals, 24,
                trueColor24.id());
        this.screens.add(screen);

        this.rootColormap = new XColormap(resourceIdCounter++, null, screen, trueColor24, true);
        resources.add(rootColormap);

        this.rootWindow = new XWindow(resourceIdCounter++, null, screen, null, 24,
                XWindow.INPUT_OUTPUT, trueColor24, 0, 0, options.width(), options.height(), 0);
        rootWindow.setMapped(true);
        rootWindow.setBackgroundPixel(trueColor24.fromArgb(0xFF000000));
        rootWindow.setBorderPixel(trueColor24.fromArgb(0xFF000000));
        rootWindow.setColormap(rootColormap);
        rootWindow.takeNeedsFullExpose();
        resources.add(rootWindow);
        screen.setRoot(rootWindow);
        screen.setDefaultColormap(rootColormap);
        screen.setBlackPixel(trueColor24.fromArgb(0xFF000000));
        screen.setWhitePixel(trueColor24.fromArgb(0xFFFFFFFF));

        this.rootCursor = XCursor.defaultCursor(resourceIdCounter++, null);
        resources.add(rootCursor);

        XFont defaultFont = new XFont(resourceIdCounter++, null, "fixed");
        resources.add(defaultFont);
        this.defaultFontId = defaultFont.id();

        rootWindow.setCursor(rootCursor);

        registerExtensions();
    }

    /**
     * Registers the extensions this server implements. Order matters: opcodes are handed out in
     * registration order and clients cache the assignment for the life of the connection.
     */
    private void registerExtensions() {
        // The counts are the number of event and error codes each extension defines; the registry
        // uses them to hand out non-overlapping ranges.
        extensions.register(new io.github.oyzh1994.xserver.extension.BigRequestsExtension(), 0, 0);
        extensions.register(new io.github.oyzh1994.xserver.extension.XCMiscExtension(), 0, 0);
        extensions.register(new io.github.oyzh1994.xserver.extension.ShapeExtension(), 1, 1);
        extensions.register(new io.github.oyzh1994.xserver.extension.XTestExtension(), 0, 1);
        extensions.register(new io.github.oyzh1994.xserver.extension.XFixesExtension(), 2, 1);
        // RENDER defines no events and five errors: BadPictFormat, BadPicture, BadPictOp,
        // BadGlyphSet and BadGlyph, in the order the plugin reads them back.
        var render = new io.github.oyzh1994.xserver.extension.RenderExtension();
        extensions.register(render, render.eventCount(), render.errorCount());
        for (Extension extension : extensions.all()) {
            extension.init(this);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public ServerOptions options() {
        return options;
    }

    public ResourceTable resources() {
        return resources;
    }

    public AtomTable atoms() {
        return atoms;
    }

    public Screen screen() {
        return screen;
    }

    public List<Screen> screens() {
        return screens;
    }

    public XWindow rootWindow() {
        return rootWindow;
    }

    public XColormap rootColormap() {
        return rootColormap;
    }

    public XCursor rootCursor() {
        return rootCursor;
    }

    public int defaultFontId() {
        return defaultFontId;
    }

    public ExtensionRegistry extensions() {
        return extensions;
    }

    public ConnectionSetup connectionSetup() {
        return connectionSetup;
    }

    public EventDispatcher events() {
        return events;
    }

    public InputState input() {
        return input;
    }

    /** Handlers are shared with the dispatcher so cross-cutting requests can call into them. */
    public WindowHandlers windows() {
        return windowHandlers;
    }

    public DrawHandlers draw() {
        return drawHandlers;
    }

    public AtomHandlers atomHandlers() {
        return atomHandlers;
    }

    public List<ClientConnection> clients() {
        return clients;
    }

    public boolean isRunning() {
        return running;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }

    public DisplayBackend backend() {
        return backend;
    }

    public Map<String, Integer> selectionOwners() {
        return selectionOwners;
    }

    /** Keyboard and pointer grabs waiting for a matching input event. */
    public List<io.github.oyzh1994.xserver.handler.InputHandlers.PassiveGrab> passiveGrabs() {
        return passiveGrabs;
    }

    /** Tells every client the keyboard mapping changed, which invalidates their cached keymaps. */
    public void broadcastMappingNotify(byte request, byte firstKeycode, byte count) {
        var event = com.github.moaxcp.x11.protocol.xproto.MappingNotifyEvent.builder()
                .firstEventOffset((byte) 0)
                .sentEvent(false)
                .request(request)
                .firstKeycode(firstKeycode)
                .count(count);
        for (ClientConnection client : clients) {
            events.send(client, event.sequenceNumber(client.sequenceNumber()).build());
        }
    }

    /** Serializes request execution. Callers must pair with {@link #unlockServer()} in a finally. */
    public void lockServer() {
        serverLock.lock();
    }

    public void unlockServer() {
        if (serverLock.isHeldByCurrentThread()) {
            serverLock.unlock();
        }
    }

    /** The client currently holding a server grab, or null. */
    public ClientConnection serverGrabHolder() {
        return serverGrabHolder;
    }

    /**
     * Takes a server grab for {@code client}, holding the lock across requests.
     *
     * <p>Grabs nest: Xlib's {@code XGrabServer} is not reference counted, but a client that calls it
     * twice and then ungrabs twice must end up with the server released. Tracking a depth rather
     * than a flag is what makes that work - with a flag the second ungrab sees a null holder, skips
     * the release, and the lock is held forever, which stalls the whole server.
     */
    public void acquireServerGrab(ClientConnection client) {
        serverLock.lock();
        serverGrabHolder = client;
        serverGrabDepth++;
    }

    /**
     * Releases one grab held by {@code client}.
     *
     * @return true when a hold was released
     */
    public boolean releaseServerGrab(ClientConnection client) {
        if (serverGrabHolder != client || serverGrabDepth <= 0) {
            return false;
        }
        serverGrabDepth--;
        if (serverGrabDepth == 0) {
            serverGrabHolder = null;
        }
        serverLock.unlock();
        return true;
    }

    /** Forgets every grab a client held, used when its connection ends. */
    public void clearServerGrab(ClientConnection client) {
        if (serverGrabHolder == client) {
            serverGrabHolder = null;
            serverGrabDepth = 0;
        }
    }

    /**
     * Releases every hold this thread has on the server lock. Used when a client that had grabbed
     * the server disconnects, since nothing will ever send the matching {@code UngrabServer}.
     */
    public void releaseAllLocks() {
        while (serverLock.isHeldByCurrentThread()) {
            serverLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Uses a backend chosen by the caller instead of the one the options name, for a test that needs
     * a window it can close, or an embedder that drives a display of its own. Call it before
     * {@link #start()}.
     */
    public void setBackend(DisplayBackend backend) {
        this.backend = backend;
    }

    public void start() throws IOException {
        running = true;
        startTime = System.currentTimeMillis();
        backend = backend != null ? backend : BackendFactory.create(options.backend());
        backend.start(screen, options.displayNumber(), input);
        listener = new ClientListener(this);
        listener.start();
        log.info("xserver listening on display :{} ({}x{}), backend {}",
                options.displayNumber(), options.width(), options.height(), backend.name());
        System.out.printf("xserver: display :%d ready, run clients with DISPLAY=:%d%n",
                options.displayNumber(), options.displayNumber());
    }

    /**
     * Starts the display loop on its own thread and returns.
     *
     * <p>The loop must not run on the main thread: on macOS the platform's own event loop needs it,
     * and blocking it stops the JavaFX window from ever appearing. The thread is deliberately not a
     * daemon so the server keeps the process alive after {@code main} returns.
     */
    public void startEventLoop() {
        Thread loop = new Thread(this::eventLoop, "xserver-display");
        loop.setDaemon(false);
        loop.start();
    }

    private void eventLoop() {
        long frameMillis = Math.max(1, 1000 / Math.max(1, options.tickRate()));
        while (running) {
            if (backend == null) {
                break;
            }
            if (backend.isClosed()) {
                // The window is gone, so there is nobody left to draw for: hand the session to the
                // clients and give them the grace period to end it themselves.
                askClientsToClose();
                if (System.currentTimeMillis() >= shutdownDeadline) {
                    break;
                }
            } else if (!idle) {
                presentDamage();
            }
            try {
                Thread.sleep(frameMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop();
    }

    /**
     * Follows the client list into and out of the idle state.
     *
     * <p>An idle server stops building frames: a client's windows are destroyed as it disconnects,
     * which leaves the screen showing an empty root, and there is nothing after that to draw until
     * something connects again. The window keeps that last frame and the platform pointer is handed
     * back, because with no X client there is no pointer of the server's to show.
     */
    private void updateIdle(boolean nowIdle) {
        if (nowIdle == idle) {
            return;
        }
        idle = nowIdle;
        if (backend != null) {
            backend.setIdle(idle);
        }
        if (idle) {
            // One last frame, without the sprite: that is what removes the pointer the server was
            // drawing from a window it will not touch again.
            markSpriteDamage(lastCursor);
            lastCursor = null;
            presentDamage();
            log.info("no clients left: the window holds its last frame until one connects");
        } else {
            log.info("client connected: rendering again");
        }
    }

    /** Asks every client to close its windows, once, and starts the clock on the grace period. */
    private void askClientsToClose() {
        if (shutdownDeadline != 0) {
            return;
        }
        shutdownDeadline = System.currentTimeMillis() + options.closeGraceMillis();
        for (ClientConnection client : clients) {
            closeClientWindows(client);
        }
        log.info("the window is closed: asked {} client(s) to close, {} ms before they are cut off",
                clients.size(), options.closeGraceMillis());
    }

    /**
     * Sends {@code WM_DELETE_WINDOW} to a client's windows that asked to be told about it.
     *
     * <p>This is how a window manager asks an application to quit, and the only way one X client can
     * tell another that the session is over: a toolkit repaints nothing and closes nothing unless it
     * is told, so without this the program on the far end of a forwarded display keeps running while
     * the display it was drawing to has gone. A window that never listed the protocol is left out -
     * cutting its connection, which is what happens to it next, is the only notice there is.
     */
    private void closeClientWindows(ClientConnection client) {
        int protocols = atoms.intern("WM_PROTOCOLS", true);
        int deleteWindow = atoms.intern("WM_DELETE_WINDOW", true);
        if (protocols == AtomTable.NONE || deleteWindow == AtomTable.NONE) {
            return;
        }
        for (XWindow window : rootWindow.children()) {
            if (window.owner() == client && asksToClose(window, protocols, deleteWindow)) {
                log.debug("asking 0x{} to close", Integer.toHexString(window.id()));
                events.send(client, deleteWindowMessage(client, window, protocols, deleteWindow));
            }
        }
    }

    /** True when a window lists {@code WM_DELETE_WINDOW} in its {@code WM_PROTOCOLS}. */
    private static boolean asksToClose(XWindow window, int protocols, int deleteWindow) {
        XProperty property = window.properties().get(protocols);
        if (property == null || property.format() != XProperty.FORMAT_32) {
            return false;
        }
        // Property data is stored in the protocol's canonical big endian order, whatever order the
        // client that set it used.
        byte[] data = property.data();
        for (int offset = 0; offset + 4 <= data.length; offset += 4) {
            int atom = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            if (atom == deleteWindow) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code ClientMessage} a window manager sends to ask a window to close: the message type is
     * {@code WM_PROTOCOLS} and its first data item is {@code WM_DELETE_WINDOW}.
     */
    private static RawEvent deleteWindowMessage(ClientConnection client, XWindow window, int protocols,
                                                int deleteWindow) {
        return RawEvent.builder(client, CLIENT_MESSAGE)
                .byteAt(1, 32)
                .card32(4, window.id())
                .card32(8, protocols)
                .card32(12, deleteWindow)
                .build();
    }

    /** The {@code ClientMessage} event code, the one thing about it that is not WM specific. */
    private static final int CLIENT_MESSAGE = 33;

    /**
     * How long a client gets to close itself before its connection is dropped. The countdown starts
     * when the host window closes.
     */
    private volatile long shutdownDeadline;

    /** True while no client is connected: the window holds its last frame and draws nothing. */
    private volatile boolean idle;

    /** Flushes any framebuffer damage to the display backend, with the pointer sprite over it. */
    public void presentDamage() {
        // The sprite is drawn by the backend rather than into the framebuffer, so a pointer move
        // changes what is on screen without changing a pixel of the framebuffer. The areas it left
        // and arrived at are marked as damage instead, so the frame that showed the old position is
        // painted again from underneath it.
        CursorSprite cursor;
        serverLock.lock();
        try {
            // With no client connected there is no X pointer to draw, and the frame that goes out
            // is the one that takes the server's pointer off the window.
            cursor = idle ? null : input.cursorSprite();
        } finally {
            serverLock.unlock();
        }
        if (!sameSprite(lastCursor, cursor)) {
            markSpriteDamage(lastCursor);
            markSpriteDamage(cursor);
            lastCursor = cursor;
        }
        Rectangle damage = screen.framebuffer().takeDamage();
        if (damage != null && backend != null) {
            backend.present(screen, damage, cursor);
        }
    }

    /** The sprite the last frame went out with, so a move can be turned into damage. */
    private CursorSprite lastCursor;

    /**
     * Marks the area a sprite covers as needing to be painted again.
     *
     * <p>Nothing in the framebuffer changed there - the sprite is drawn over the frame by the
     * backend - but reusing the damage rectangle is what keeps every backend down to one way of
     * being told what to repaint.
     */
    private void markSpriteDamage(CursorSprite sprite) {
        if (sprite != null) {
            screen.framebuffer().markDirty(sprite.x(), sprite.y(), sprite.x() + sprite.width(),
                    sprite.y() + sprite.height());
        }
    }

    /** Whether two sprites would draw the same picture in the same place. */
    private static boolean sameSprite(CursorSprite previous, CursorSprite current) {
        if (previous == null || current == null) {
            return previous == current;
        }
        return previous.x() == current.x() && previous.y() == current.y()
                && previous.pixels() == current.pixels();
    }

    public void markScreenDirty() {
        screen.framebuffer().markAllDirty();
    }

    public void stop() {
        running = false;
        if (listener != null) {
            listener.stop();
        }
        for (ClientConnection client : clients) {
            client.close();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    // ------------------------------------------------------------------
    // Request execution
    // ------------------------------------------------------------------

    /**
     * Decodes and runs one request. Exceptions carrying a protocol error are turned into error
     * replies; the connection survives them, exactly as clients expect.
     */
    public void executeRequest(ClientConnection client, byte opcode, byte minorOpcode, X11Input in,
                               int requestBytes) {
        serverLock.lock();
        try {
            if (client.isShuttingDown()) {
                return;
            }
            if (options.logRequests()) {
                log.debug("{} opcode {} minor {}", client, opcode & 0xFF, minorOpcode & 0xFF);
            }
            dispatcher.dispatch(client, opcode, minorOpcode, in, requestBytes);
        } catch (ProtocolException e) {
            log.info("{}: {} rejected the request (error {}): {}", client,
                    dispatcher.currentRequest(), e.errorCode(), e.getMessage());
            client.sendError(e.errorCode(), e.badValue());
        } catch (RuntimeException e) {
            log.error("{} opcode {} failed", client, opcode & 0xFF, e);
            client.sendError(ProtocolException.IMPLEMENTATION, 0);
        } finally {
            serverLock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Clients
    // ------------------------------------------------------------------

    public void registerClient(ClientConnection client) {
        clients.add(client);
        log.info("client {} connected", client.peer());
        updateIdle(false);
    }

    public void removeClient(ClientConnection client) {
        if (!clients.remove(client)) {
            return;
        }
        serverLock.lock();
        try {
            usedClientIndices.clear(client.index());
            input.clientDisconnected(client);
            // Windows owned by this client get destroyed, which generates destroy notifications.
            dispatcher.destroyClientResources(client);
        } finally {
            // A client that grabbed the server will never send UngrabServer, so drop every hold it
            // accumulated or the remaining clients would block forever.
            clearServerGrab(client);
            releaseAllLocks();
        }
        log.info("client {} disconnected", client.peer());
        updateIdle(clients.isEmpty());
    }

    /** Reserves a client index, or -1 when the server is full. */
    public synchronized int allocateClientIndex() {
        int index = usedClientIndices.nextClearBit(1);
        if (index >= ClientConnection.MAX_CLIENTS) {
            return -1;
        }
        usedClientIndices.set(index);
        return index;
    }

    public void releaseClientIndex(int index) {
        if (index > SERVER_CLIENT_INDEX) {
            usedClientIndices.clear(index);
        }
    }

    /** Allocates a server owned resource id, used for predefined fonts and similar. */
    public synchronized int nextServerResourceId() {
        return resourceIdCounter++;
    }
}
