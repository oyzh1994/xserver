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

import java.awt.*;
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

    /**
     * Client index 0 is reserved for the server, which owns the root window and colormaps.
     */
    public static final int SERVER_CLIENT_INDEX = 0;

    private final ServerOptions options;
    private final ResourceTable resources = new ResourceTable();
    private final AtomTable atoms = new AtomTable();
    private final List<ClientConnection> clients = new CopyOnWriteArrayList<>();
    /**
     * Every screen the server is showing, the master one included.
     */
    private final List<ScreenSession> sessions = new CopyOnWriteArrayList<>();
    private final BitSet usedClientIndices = new BitSet(ClientConnection.MAX_CLIENTS);
    private final Map<String, Integer> selectionOwners = new HashMap<>();
    private final List<io.github.oyzh1994.xserver.handler.InputHandlers.PassiveGrab> passiveGrabs =
            new ArrayList<>();

    /**
     * Held while a request executes; {@code GrabServer} holds it across requests.
     */
    private final ReentrantLock serverLock = new ReentrantLock(true);

    /**
     * The master screen: the one single-window mode uses, and the one in-process tests build on.
     */
    private final Screen screen;
    private final List<Screen> screens = new ArrayList<>();
    /**
     * The visuals every screen offers. Shared, because a client compares them by id.
     */
    private final List<VisualInfo> visuals = new ArrayList<>();
    private final VisualInfo rootVisual;
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
    /**
     * Input for the master screen. Created in the constructor rather than here because it is bound to
     * a screen, and the screen does not exist yet while the fields are being initialised.
     */
    private final InputState input;

    /**
     * How a window is made for a screen. One per screen, because each window shows one screen: the
     * JavaFX toolkit itself is shared, but a stage is not.
     */
    private java.util.function.Supplier<DisplayBackend> backendFactory;

    /**
     * A backend chosen by an embedder or a test, used for the master screen.
     */
    private DisplayBackend sharedBackend;

    private final ScreenSession masterSession;
    private ClientListener listener;

    private ClientConnection serverGrabHolder;
    /**
     * How many times the holder called GrabServer without a matching UngrabServer.
     */
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
        int nextVisualId = 0x0200;
        VisualInfo trueColor24 = VisualInfo.trueColor24(nextVisualId++);
        visuals.add(trueColor24);
        visuals.add(VisualInfo.argb32(nextVisualId++));
        visuals.add(VisualInfo.trueColor16(nextVisualId++));
        visuals.add(VisualInfo.trueColor8(nextVisualId++));
        visuals.add(VisualInfo.pseudoColor8(nextVisualId++));
        visuals.add(VisualInfo.staticGray8(nextVisualId++));
        visuals.add(VisualInfo.staticGray1(nextVisualId++));
        this.rootVisual = trueColor24;

        this.screen = createScreen(0);

        XFont defaultFont = new XFont(resourceIdCounter++, null, "fixed");
        resources.add(defaultFont);
        this.defaultFontId = defaultFont.id();

        this.input = new InputState(this, screen);
        screenInputs.put(screen, input);
        this.masterSession = new ScreenSession(screen, input, null);
        sessions.add(masterSession);

        registerExtensions();
    }

    /**
     * Builds a screen with its own framebuffer, root window, default colormap and root cursor, and
     * registers it.
     *
     * <p>Screens share the visual table on purpose: a client compares visuals by id, and RENDER keys
     * its formats by visual id, so two screens handing out different {@link VisualInfo} objects for
     * the same depth would look like two different displays rather than two of the same.
     */
    private Screen createScreen(int number) {
        Screen created = new Screen(number, options.width(), options.height(), visuals, ROOT_DEPTH,
                rootVisual.id());
        XColormap colormap = new XColormap(resourceIdCounter++, null, created, rootVisual, true);
        resources.add(colormap);
        XWindow root = new XWindow(resourceIdCounter++, null, created, null, ROOT_DEPTH,
                XWindow.INPUT_OUTPUT, rootVisual, 0, 0, options.width(), options.height(), 0);
        root.setMapped(true);
        root.setBackgroundPixel(rootVisual.fromArgb(0xFF000000));
        root.setBorderPixel(rootVisual.fromArgb(0xFF000000));
        root.setColormap(colormap);
        root.takeNeedsFullExpose();
        resources.add(root);
        created.setRoot(root);
        created.setDefaultColormap(colormap);
        created.setBlackPixel(rootVisual.fromArgb(0xFF000000));
        created.setWhitePixel(rootVisual.fromArgb(0xFFFFFFFF));
        XCursor cursor = XCursor.defaultCursor(resourceIdCounter++, null);
        resources.add(cursor);
        created.setRootCursor(cursor);
        root.setCursor(cursor);
        screens.add(created);
        return created;
    }

    /**
     * The root window's depth. The same on every screen, and what the setup reply advertises.
     */
    private static final int ROOT_DEPTH = 24;

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

    public List<ScreenSession> sessions() {
        return sessions;
    }

    /**
     * The input state of every screen the server is showing, the master one included.
     */
    private final Map<Screen, InputState> screenInputs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The input state of a screen: its own pointer, focus and grabs.
     *
     * <p>Two screens have two pointers. A backend never reports a move against one screen and have
     * it applied to another, which is what would happen if every screen shared the master's input.
     */
    public InputState inputFor(Screen screen) {
        return screenInputs.getOrDefault(screen, input);
    }

    /**
     * The input state a client's events belong to: its own screen's, or the master one.
     */
    public InputState inputFor(ClientConnection client) {
        ScreenSession session = client == null ? null : client.session();
        return session == null ? input : session.input();
    }

    /**
     * The screen a client's windows live on: its own when it has one, the master screen otherwise.
     * Everything that answers a client - the setup reply, its requests, its input - resolves the
     * screen through here rather than assuming the shared one.
     */
    public Screen screenOf(ClientConnection client) {
        ScreenSession session = client == null ? null : client.session();
        return session == null ? screen : session.screen();
    }

    /**
     * Gives a connection a screen of its own, with its own framebuffer, root window and input.
     *
     * <p>Called from the handshake, before the setup reply tells the client which root to use, so
     * the client's very first request already lands on its own screen.
     */
    public ScreenSession openClientScreen(ClientConnection client) {
        Screen privateScreen = createScreen(0);
        InputState privateInput = new InputState(this, privateScreen);
        ScreenSession session = new ScreenSession(privateScreen, privateInput, client);
        screenInputs.put(privateScreen, privateInput);
        sessions.add(session);
        client.setSession(session);
        return session;
    }

    /**
     * Frees a client's screen once its connection is gone. The display tick closes the window.
     */
    private void closeClientScreen(ScreenSession session) {
        if (session == null || session.closed()) {
            return;
        }
        session.markClosed();
        resources.free(session.screen().root().id());
        resources.free(session.screen().defaultColormap().id());
        resources.free(session.screen().rootCursor().id());
        screens.remove(session.screen());
        screenInputs.remove(session.screen());
        log.info("screen of display :{} freed", options.displayNumber());
    }

    public List<Screen> screens() {
        return screens;
    }

    public XWindow rootWindow() {
        return screen.root();
    }

    public XColormap rootColormap() {
        return screen.defaultColormap();
    }

    public XCursor rootCursor() {
        return screen.rootCursor();
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

    /**
     * Handlers are shared with the dispatcher so cross-cutting requests can call into them.
     */
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
        return masterSession.backend();
    }

    public Map<String, Integer> selectionOwners() {
        return selectionOwners;
    }

    /**
     * Keyboard and pointer grabs waiting for a matching input event.
     */
    public List<io.github.oyzh1994.xserver.handler.InputHandlers.PassiveGrab> passiveGrabs() {
        return passiveGrabs;
    }

    /**
     * Tells every client the keyboard mapping changed, which invalidates their cached keymaps.
     */
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

    /**
     * Serializes request execution. Callers must pair with {@link #unlockServer()} in a finally.
     */
    public void lockServer() {
        serverLock.lock();
    }

    public void unlockServer() {
        if (serverLock.isHeldByCurrentThread()) {
            serverLock.unlock();
        }
    }

    /**
     * The client currently holding a server grab, or null.
     */
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

    /**
     * Forgets every grab a client held, used when its connection ends.
     */
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
        this.sharedBackend = backend;
    }

    /**
     * How each screen's window is made, for a test that wants a window it can close without one
     * existing. Replaces the backend the options name.
     */
    public void setBackendFactory(java.util.function.Supplier<DisplayBackend> backendFactory) {
        this.backendFactory = backendFactory;
    }

    public void start() throws IOException {
        running = true;
        startTime = System.currentTimeMillis();
        if (backendFactory == null) {
            backendFactory = () -> BackendFactory.create(options.backend());
        }
        if (!options.perClientWindows()) {
            // One window for the whole screen, opened now and kept for the life of the server.
            openSessionBackend(masterSession, new Rectangle(0, 0, screen.width(), screen.height()));
        }
        listener = new ClientListener(this);
        listener.start();
        log.info("xserver listening on display :{} ({}x{}), {}", options.displayNumber(),
                options.width(), options.height(),
                options.perClientWindows() ? "one window per client" : "a single shared window");
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
            for (ScreenSession session : sessions) {
                tick(session);
            }
            if (!options.perClientWindows() && masterSession.closeDeadline() != 0
                    && System.currentTimeMillis() >= masterSession.closeDeadline()) {
                // Single-window mode: the one window was closed and the clients have had their
                // grace period, so the session is over.
                break;
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
     * One frame for one screen: opens its window when there is something to show, keeps the window
     * sized to what the client draws, and presents the damage.
     *
     * <p>Everything the server owns is read under the server lock and the backend is called outside
     * it, because those calls wait on the toolkit's own thread - and holding the lock there is what
     * would freeze every other client.
     */
    private void tick(ScreenSession session) {
        if (session.closed()) {
            closeSessionBackend(session);
            sessions.remove(session);
            return;
        }

        Rectangle wanted;
        Rectangle damage;
        CursorSprite cursor;
        boolean viewportChanged = false;
        serverLock.lock();
        try {
            // The master screen shows all of itself; a client's screen shows the part of it that
            // client's windows occupy, which is what keeps a small application out of a full-screen
            // window.
            wanted = session.client() == null
                    ? new Rectangle(0, 0, session.screen().width(), session.screen().height())
                    : contentBounds(session.screen());
            boolean nowIdle = session.client() == null ? clients.isEmpty() : wanted == null;
            if (nowIdle != session.idle()) {
                session.setIdle(nowIdle);
                if (nowIdle) {
                    // The frame that takes the server's pointer off a window it will not touch again.
                    markSpriteDamage(session, session.lastCursor());
                    session.setLastCursor(null);
                    log.info("nothing to show on display :{}: the window holds its last frame",
                            options.displayNumber());
                }
            }
            if (wanted == null || wanted.equals(session.viewport())) {
                session.setPendingViewport(null);
                session.setPendingSince(0);
            } else if (session.pendingSince() == 0) {
                // The rectangle moved: start the clock, and keep the latest version of it while the
                // clock runs. A client dragging something moves it every frame, and following that
                // frame by frame means resizing the host window through the platform's window server
                // that often, which is what makes the drag stutter.
                session.setPendingViewport(new Rectangle(wanted));
                session.setPendingSince(System.currentTimeMillis());
            } else {
                session.setPendingViewport(new Rectangle(wanted));
                if (System.currentTimeMillis() - session.pendingSince() >= VIEWPORT_SETTLE_MILLIS) {
                    Rectangle settled = session.pendingViewport();
                    session.setPendingViewport(null);
                    session.setPendingSince(0);
                    session.setViewport(settled);
                    session.screen().framebuffer().markAllDirty();
                    viewportChanged = true;
                }
            }
            cursor = session.idle() ? null : session.input().cursorSprite();
            // The sprite belongs to the frame, so its bookkeeping happens before the damage is
            // taken: a pointer that moved has to be in the frame that goes out now rather than the
            // next one, and - because a moved sprite marks damage of its own - a pointer moving
            // over a window whose client is drawing nothing still gets a frame at all. Doing this
            // after the damage was taken made the pointer advance only on the ticks where the
            // client happened to repaint, which reads as a stuttering cursor.
            if (!sameSprite(session.lastCursor(), cursor)) {
                markSpriteDamage(session, session.lastCursor());
                markSpriteDamage(session, cursor);
                session.setLastCursor(cursor);
            }
            damage = session.screen().framebuffer().takeDamage();
        } finally {
            serverLock.unlock();
        }

        if (session.backend() == null) {
            // Nothing mapped yet: the window is opened by the first window a client maps, so a
            // connection that only asks questions never opens one. The master screen has no window
            // at all in per-client mode - it is not shown to anybody, it only exists so the
            // in-process tests and single-window mode have a screen to build on.
            if (wanted == null || (session.client() == null && options.perClientWindows())) {
                return;
            }
            openSessionBackend(session, wanted);
        } else if (viewportChanged) {
            session.backend().setViewport(wanted);
        }
        DisplayBackend window = session.backend();
        if (window == null) {
            return;
        }
        if (session.idle() != session.idleApplied()) {
            session.setIdleApplied(session.idle());
            window.setIdle(session.idle());
        }
        if (window.isClosed()) {
            onWindowClosed(session);
            return;
        }
        if (!session.idle() && damage != null) {
            window.present(session.screen(), damage, cursor);
        }
    }


    /**
     * How long the rectangle a client's windows occupy has to hold still before the host window is
     * resized to it.
     *
     * <p>Every resize goes through the platform's window server, which is far more expensive than
     * painting a frame, and a client that is dragging something - a tooltip that follows the
     * pointer, an animated resize - moves that rectangle every frame. Resizing on each of those
     * makes the drag stutter, so the rectangle is applied once it settles.
     */
    private static final long VIEWPORT_SETTLE_MILLIS = 0;

    /**
     * The part of a screen the client's windows occupy: the union of its mapped top level windows.
     *
     * <p>Their children are inside them, and override-redirect windows - menus, tooltips - are top
     * level too, so a menu that hangs off the side of its parent still lands inside the window.
     */
    private static Rectangle contentBounds(Screen screen) {
        Rectangle union = null;
        for (XWindow child : screen.root().children()) {
            if (!child.isMapped() || child.isInputOnly()) {
                continue;
            }
            Rectangle bounds = child.absoluteBoundsWithBorder();
            union = union == null ? bounds : union.union(bounds);
        }
        return union == null ? null
                : union.intersection(new Rectangle(0, 0, screen.width(), screen.height()));
    }

    /**
     * Opens a screen's window, sized to show {@code viewport}. Never called with the lock held.
     */
    private void openSessionBackend(ScreenSession session, Rectangle viewport) {
        DisplayBackend window = session.client() == null && sharedBackend != null
                ? sharedBackend
                : backendFactory.get();
        window.setViewport(viewport);
        window.start(session.screen(), options.displayNumber(), session.input());
        session.setBackend(window);
        session.setViewport(viewport);
        log.info("opened a window for display :{} showing {}", options.displayNumber(), viewport);
    }

    /**
     * Closes a screen's window. The screen itself was freed when the connection went.
     */
    private void closeSessionBackend(ScreenSession session) {
        DisplayBackend window = session.backend();
        if (window != null) {
            session.setBackend(null);
            window.stop();
        }
    }

    /**
     * The user closed a window.
     *
     * <p>In single-window mode that is the whole session ending, so every client is asked to quit. A
     * client's own window closing ends that client: it is asked to close through
     * {@code WM_DELETE_WINDOW} and cut off once the grace period is up, while the other clients keep
     * their windows and the server keeps running.
     */
    private void onWindowClosed(ScreenSession session) {
        if (session.client() == null) {
            askClientsToClose();
            return;
        }
        if (session.closeDeadline() == 0) {
            session.setCloseDeadline(System.currentTimeMillis() + options.closeGraceMillis());
            closeClientWindows(session.screen(), session.client());
            log.info("the window of {} was closed: asked it to quit, {} ms before it is cut off",
                    session.client().peer(), options.closeGraceMillis());
            return;
        }
        if (System.currentTimeMillis() >= session.closeDeadline()) {
            // Closing the connection is what actually ends it: toolkits often ignore the message
            // and hide instead of quitting.
            session.client().close();
        }
    }

    /**
     * Asks every client to close its windows, once, and starts the clock on the grace period.
     */
    private void askClientsToClose() {
        if (masterSession.closeDeadline() != 0) {
            return;
        }
        masterSession.setCloseDeadline(System.currentTimeMillis() + options.closeGraceMillis());
        for (ClientConnection client : clients) {
            closeClientWindows(screen, client);
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
    private void closeClientWindows(Screen screen, ClientConnection client) {
        int protocols = atoms.intern("WM_PROTOCOLS", true);
        int deleteWindow = atoms.intern("WM_DELETE_WINDOW", true);
        if (protocols == AtomTable.NONE || deleteWindow == AtomTable.NONE) {
            return;
        }
        for (XWindow window : screen.root().children()) {
            if (window.owner() == client && asksToClose(window, protocols, deleteWindow)) {
                log.debug("asking 0x{} to close", Integer.toHexString(window.id()));
                events.send(client, deleteWindowMessage(client, window, protocols, deleteWindow));
            }
        }
    }

    /**
     * True when a window lists {@code WM_DELETE_WINDOW} in its {@code WM_PROTOCOLS}.
     */
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

    /**
     * The {@code ClientMessage} event code, the one thing about it that is not WM specific.
     */
    private static final int CLIENT_MESSAGE = 33;

    /**
     * Marks the area a sprite covers as needing to be painted again.
     *
     * <p>Nothing in the framebuffer changed there - the sprite is drawn over the frame by the
     * backend - but reusing the damage rectangle is what keeps every backend down to one way of
     * being told what to repaint.
     */
    private static void markSpriteDamage(ScreenSession session, CursorSprite sprite) {
        if (sprite != null) {
            session.screen().framebuffer().markDirty(sprite.x(), sprite.y(),
                    sprite.x() + sprite.width(), sprite.y() + sprite.height());
        }
    }

    /**
     * Whether two sprites would draw the same picture in the same place.
     */
    private static boolean sameSprite(CursorSprite previous, CursorSprite current) {
        if (previous == null || current == null) {
            return previous == current;
        }
        return previous.x() == current.x() && previous.y() == current.y()
                && previous.pixels() == current.pixels();
    }

    public void markScreenDirty() {
        markScreenDirty(screen);
    }

    /**
     * Marks a whole screen dirty, for a change that cannot say which pixels moved.
     *
     * <p>Screens are independent: only the screen a window lives on has to be repainted when it
     * changes, and marking the others would repaint windows that did not move.
     */
    public void markScreenDirty(Screen screen) {
        screen.framebuffer().markAllDirty();
    }

    /**
     * Marks the screen a window lives on.
     */
    public void markScreenDirty(XWindow window) {
        markScreenDirty(window.screen());
    }

    public void stop() {
        running = false;
        if (listener != null) {
            listener.stop();
        }
        for (ClientConnection client : clients) {
            client.close();
        }
        for (ScreenSession session : sessions) {
            closeSessionBackend(session);
        }
        // The JavaFX toolkit holds a non-daemon thread, so something has to end it - but only once
        // no window is left, which is now.
        DisplayBackend window = sharedBackend;
        if (window != null) {
            window.releaseToolkit();
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
    }

    public void removeClient(ClientConnection client) {
        boolean registered = clients.remove(client);
        ScreenSession session = client.session();
        serverLock.lock();
        try {
            usedClientIndices.clear(client.index());
            if (session != null) {
                session.input().clientDisconnected(client);
            }
            input.clientDisconnected(client);
            // Windows owned by this client get destroyed, which generates destroy notifications.
            dispatcher.destroyClientResources(client);
        } finally {
            // A client that grabbed the server will never send UngrabServer, so drop every hold it
            // accumulated or the remaining clients would block forever.
            clearServerGrab(client);
            releaseAllLocks();
        }
        // A connection can end before it was ever registered - a handshake that failed after the
        // screen was made - so the screen is freed whether or not the client was in the list.
        closeClientScreen(session);
        if (registered) {
            log.info("client {} disconnected", client.peer());
        }
    }

    /**
     * Reserves a client index, or -1 when the server is full.
     */
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

    /**
     * Allocates a server owned resource id, used for predefined fonts and similar.
     */
    public synchronized int nextServerResourceId() {
        return resourceIdCounter++;
    }
}
