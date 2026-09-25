package io.github.oyzh1994.xserver;

import com.github.moaxcp.x11.protocol.X11Input;
import com.github.moaxcp.x11.protocol.xproto.ConfigureNotifyEvent;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ConnectionSetup;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.clipboard.ClipboardBridge;
import io.github.oyzh1994.xserver.clipboard.HostClipboard;
import io.github.oyzh1994.xserver.client.RequestDispatcher;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.event.EventDispatcher;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.event.RawEvent;
import io.github.oyzh1994.xserver.extension.Extension;
import io.github.oyzh1994.xserver.extension.ExtensionRegistry;
import io.github.oyzh1994.xserver.extension.XFixesExtension;
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
    private final Map<String, Selection> selections = new HashMap<>();
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
    /** The host clipboard bridge, or null when there is no host to bridge to. */
    private ClipboardBridge clipboard;
    /** The host drag bridge, or null when this server has no window to drop on. */
    private io.github.oyzh1994.xserver.drop.DropBridge drops;
    /**
     * Where the host window was per client, or null until the first window opens. Lazy because a
     * server that never opens a window - every test, and a headless one - has nothing to remember
     * and no reason to touch the user's home directory.
     */
    private HostWindowStore windows = null;
    /** Set by {@link #setHostWindowStore}; the store actually used, once something needs one. */
    private HostWindowStore hostWindowStore;
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
    /** The loopback command port, or null when the server was started without one (backlog D1). */
    private io.github.oyzh1994.xserver.net.ControlListener control;

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
        // The screen is the pixel size, not the size the window looks: with --scale 2 a client is
        // told about twice as many pixels and draws into them, and the host window shows that twice
        // as large a picture at the same size on screen.
        Screen created = new Screen(number, options.pixelWidth(), options.pixelHeight(),
                options.scale(), visuals, ROOT_DEPTH, rootVisual.id());
        XColormap colormap = new XColormap(resourceIdCounter++, null, created, rootVisual, true);
        resources.add(colormap);
        XWindow root = new XWindow(resourceIdCounter++, null, created, null, ROOT_DEPTH,
                XWindow.INPUT_OUTPUT, rootVisual, 0, 0, options.pixelWidth(),
                options.pixelHeight(), 0);
        root.setMapped(true);
        root.setBackgroundPixel(rootVisual.fromArgb(Screen.DEFAULT_BACKGROUND_ARGB));
        root.setBorderPixel(rootVisual.fromArgb(Screen.DEFAULT_BACKGROUND_ARGB));
        root.setColormap(colormap);
        root.takeNeedsFullExpose();
        resources.add(root);
        created.setRoot(root);
        created.setDefaultColormap(colormap);
        created.setBlackPixel(rootVisual.fromArgb(0xFF000000));
        created.setWhitePixel(rootVisual.fromArgb(0xFFFFFFFF));
        if (options.scale() > 1) {
            publishDpi(created);
        }
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
     * The display's idle state: the screen saver's settings and state, and DPMS's.
     *
     * <p>One per server rather than one per screen, because what would blank is the host window and
     * there is one of them - see {@link io.github.oyzh1994.xserver.display.ScreenSaverState}.
     */
    private final io.github.oyzh1994.xserver.display.ScreenSaverState screenSaver =
            new io.github.oyzh1994.xserver.display.ScreenSaverState();

    private io.github.oyzh1994.xserver.extension.ScreenSaverExtension screenSaverExtension;

    private io.github.oyzh1994.xserver.extension.RandRExtension randrExtension;

    /** The display's idle state, which the core screen-saver requests and MIT-SCREEN-SAVER share. */
    public io.github.oyzh1994.xserver.display.ScreenSaverState screenSaver() {
        return screenSaver;
    }

    /** The MIT-SCREEN-SAVER extension, which the core {@code ForceScreenSaver} notifies through. */
    public io.github.oyzh1994.xserver.extension.ScreenSaverExtension screenSaverExtension() {
        return screenSaverExtension;
    }

    /** The RandR extension, which the screen-growth path notifies through. */
    public io.github.oyzh1994.xserver.extension.RandRExtension randrExtension() {
        return randrExtension;
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
        // One screen is Xinerama's whole answer here, and it is an answer a client needs rather than
        // an extension it uses: xdotool segfaults in its own startup without it.
        extensions.register(new io.github.oyzh1994.xserver.extension.XineramaExtension(), 0, 1);
        // XKEYBOARD defines one error (the keyboard error) and many events this server never sends,
        // so none are reserved. It is here for the same reason Xinerama is: xdotool asks the
        // keyboard for its keymap through XKB and dereferences a null pointer when told there is no
        // such extension - see the class comment, and A4.
        extensions.register(new io.github.oyzh1994.xserver.extension.XkbExtension(), 0, 1);
        // MIT-SCREEN-SAVER defines one event - the state-change notification - and no errors; DPMS
        // is requests and replies only. The screen saver extension is kept by name because the core
        // ForceScreenSaver is what raises its notification.
        this.screenSaverExtension =
                extensions.register(new io.github.oyzh1994.xserver.extension.ScreenSaverExtension(),
                        1, 0);
        extensions.register(new io.github.oyzh1994.xserver.extension.DpmsExtension(), 0, 0);
        // RandR defines two events (the screen change and the generic notify) and four errors
        // (BadCrtc, BadMode, BadOutput, BadProvider, in the order the plugin reads them back). It is
        // kept by name because the screen-growth path is what raises its notification.
        this.randrExtension = extensions.register(
                new io.github.oyzh1994.xserver.extension.RandRExtension(), 2, 4);
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
    /**
     * Says so when this display is scaled and the server is not drawing for it.
     *
     * <p>On a Retina panel the default of 1 is the wrong answer - everything is drawn at half the
     * resolution the display has and then enlarged by the platform - but it stays the default
     * because the other answer is not free: a client that does not follow the resolution lays itself
     * out for a screen half the size it is given, so making 2 the default would shrink every
     * application that has not been started with a scale setting of its own. A line in the log is
     * what the user has instead of a guess made for them.
     */
    private void suggestScale() {
        int host = ServerOptions.hostScale();
        if (host > 1 && options.scale() == 1) {
            log.info("this display is {}x: '--scale auto' draws the screen at {}x pixels per logical "
                            + "pixel, which a client that reads Xft.dpi follows by itself - one that "
                            + "does not has to be started with its own scale setting, or it will lay "
                            + "itself out for a screen half this size (see the README)",
                    host, host);
        }
    }

    /**
     * Publishes the display's resolution to the clients that read it from the resource manager.
     *
     * <p>{@code Xft.dpi} on the root window is what `xrdb -merge` sets and what a toolkit that
     * renders through Xft - GTK, Qt, an xterm with Xft fonts - sizes its interface from. A client
     * that reads it draws at the resolution the screen really has, which is exactly what a forwarded
     * application needs on a Retina panel and what it cannot be told any other way: the server
     * cannot set a client's own toolkit flags. A client that never looks at the resource manager is
     * not affected by it either, which is what makes it safe to publish whenever the screen is
     * scaled.
     *
     * <p>It is only half of HiDPI, and the other half is not a server's to do - see
     * {@code ServerOptions.hostScale} and the README.
     */
    private void publishDpi(Screen screen) {
        int atom = atoms.intern("RESOURCE_MANAGER", true);
        int string = atoms.intern("STRING", true);
        if (atom == AtomTable.NONE || string == AtomTable.NONE) {
            return;
        }
        // A line of resource database, as xrdb would write it: the value is what a client multiplies
        // its points by, and 96 is the resolution a 1x display is assumed to have.
        byte[] value = ("Xft.dpi:\t" + (96 * options.scale()) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        screen.root().properties().put(atom, new XProperty(atom, string, 8, value));
        log.info("display :{} is scaled {}x: clients that read Xft.dpi will draw at {} dpi",
                options.displayNumber(), options.scale(), 96 * options.scale());
    }

    /**
     * The host clipboard bridge, or null when this server has none.
     *
     * <p>A server with no window has no host to bridge to, so it has no bridge; neither has one that
     * was started with {@code --no-clipboard}. Callers must expect null: the bridge is a convenience
     * for a user sitting in front of the machine, not something the protocol depends on.
     */
    public ClipboardBridge clipboard() {
        return clipboard;
    }

    /**
     * The bridge that turns a drop from the host's desktop into an XDND drag for the application
     * under the pointer, or null when this server has no window to be dropped on.
     */
    public io.github.oyzh1994.xserver.drop.DropBridge dropBridge() {
        return drops;
    }

    /**
     * Starts the bridge if this server has somewhere to bridge to.
     *
     * <p>Only a server that presents a window does: a headless one is either a test or a display
     * nobody is looking at, and taking the machine's clipboard in either case would be a side effect
     * nobody asked for.
     */
    private void startClipboardBridge() {
        if (options.noClipboard()) {
            return;
        }
        // A window on this machine has the Mac's own pasteboard as its other end. A VNC server (D2)
        // has no window but does have viewers, and they want the same thing in the other direction -
        // so it gets the bridge too, wired up in both directions below.
        DisplayBackend window = masterSession.backend();
        boolean vnc = window instanceof io.github.oyzh1994.xserver.display.VncBackend;
        if (presentsNoWindowHere() && !vnc) {
            return;
        }
        HostClipboard host = HostClipboard.open();
        if (host.isAvailable()) {
            startClipboardBridge(host);
        }
    }

    /**
     * True when this server's screen is not shown in a window on this machine.
     *
     * <p>The clipboard and drag bridges are the host's own desktop, reached through a window the
     * user is looking at; a headless server has none, and neither has one served over VNC (D2) - a
     * viewer's clipboard and the Mac's pasteboard are two different bridges, and the RFB one is not
     * written yet.
     */
    private boolean presentsNoWindowHere() {
        return options.backend() == ServerOptions.Backend.HEADLESS
                || options.backend() == ServerOptions.Backend.VNC;
    }

    /**
     * Starts the drag bridge, and returns it. Public for the same reason the clipboard bridge's is:
     * a test drives a drop itself, because the platform's half of one cannot be produced in-process.
     */
    public io.github.oyzh1994.xserver.drop.DropBridge startDropBridge() {
        if (drops != null) {
            return drops;
        }
        drops = new io.github.oyzh1994.xserver.drop.DropBridge(this);
        drops.start();
        return drops;
    }

    /** Starts the drag bridge when this server has a window for the host to drop onto. */
    private void startDropBridgeForWindow() {
        if (options.noClipboard() || presentsNoWindowHere()) {
            // The same rule as the clipboard: a server nobody is looking at has no desktop to be
            // dragged from.
            return;
        }
        startDropBridge();
    }

    /**
     * Starts the bridge over a host clipboard the caller supplies, and returns it.
     *
     * <p>Public for the same reason {@code --no-clipboard} exists: which clipboard a server bridges
     * to is a decision about the machine it runs on, and a test that exercised the real one would be
     * a test that rearranges whatever the person running it had copied.
     */
    public ClipboardBridge startClipboardBridge(HostClipboard host) {
        if (clipboard != null) {
            return clipboard;
        }
        clipboard = new ClipboardBridge(this, host);
        clipboard.start();
        // A VNC server has no window but does have viewers, and they are the other end of the same
        // clipboard: X -> viewers is the bridge's listener, viewers -> X is its remoteCopy. Wired
        // here rather than in the caller so that a test (or an embedder) that supplies its own host
        // clipboard gets the same behaviour.
        DisplayBackend window = masterSession.backend();
        if (window instanceof io.github.oyzh1994.xserver.display.VncBackend vncBackend) {
            clipboard.setListener(vncBackend::clipboardTextChanged);
            vncBackend.setClipboardSink(clipboard::remoteCopy);
        }
        return clipboard;
    }


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

    /**
     * Lets every screen deliver the key repeats that have come due, at {@code nowMillis}.
     *
     * <p>The keyboard's repeats are the server's own work - a client that holds a key down is not
     * expected to press it again, and a toolkit that types a run of characters from it would type
     * one - so they are driven from the display tick, under the server lock, once per frame. Taking
     * the time as an argument keeps the whole thing testable, and one call covers every screen
     * because each has its own keyboard.
     */
    public void pollAutoRepeat(long nowMillis) {
        serverLock.lock();
        try {
            for (Screen screen : screens) {
                inputFor(screen).pollAutoRepeat(nowMillis);
            }
        } finally {
            serverLock.unlock();
        }
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

    /**
     * A selection's current owner - zero for none - and when it last changed hands.
     *
     * <p>The timestamp is the one the XFixes selection notification carries as its
     * {@code selectionTimestamp}, which a client compares against the time it used in its own
     * {@code SetSelectionOwner} to decide whether a notification is news or an echo. Only
     * {@code SetSelectionOwner} moves it: a selection whose owner window is destroyed or whose owner
     * disconnects keeps the time it was taken, which is what a reference server was measured doing.
     */
    public record Selection(int owner, int timestamp) {
    }

    /** A selection's current owner, or 0 when nobody owns it. */
    public int selectionOwner(String name) {
        Selection selection = selections.get(name);
        return selection == null ? 0 : selection.owner();
    }

    /** When a selection last changed hands, or the current time when it never has. */
    public int selectionTimestamp(String name) {
        Selection selection = selections.get(name);
        return selection == null ? events.time() : selection.timestamp();
    }

    /**
     * Changes a selection's owner and tells the clients that subscribed to hear about it, in that
     * order: a notification sent before the owner is recorded makes the client query the old one.
     *
     * <p>This is the only way ownership moves, so it is also the only place that has to remember
     * that every {@code SetSelectionOwner} is announced - including the ones that name the owner
     * already there and the ones that name nobody, which a reference server was measured treating as
     * a change and moving the timestamp for.
     *
     * @param selectionTime when the selection changed hands, which becomes the selection's own time
     * @param subtype the XFixes subtype that says <em>why</em> it moved - the three do not carry the
     *     same times. When the selection changes hands the notification <em>is</em> the change, and
     *     a reference server reports both times as that one instant; when an owner goes away the
     *     notification happens now and the selection keeps the time it was taken.
     */
    public void setSelectionOwner(String name, int owner, int selectionTime, byte subtype) {
        selections.put(name, new Selection(owner, selectionTime));
        int atom = atoms.intern(name, true);
        if (atom != AtomTable.NONE) {
            int timestamp = subtype == XFixesExtension.SELECTION_SET_OWNER
                    ? selectionTime : events.time();
            XFixesExtension.notifySelection(this, atom, owner, timestamp, selectionTime, subtype);
            if (clipboard != null && subtype == XFixesExtension.SELECTION_SET_OWNER) {
                // The bridge watches the selection the same way a clipboard manager would, except
                // that it is the one that takes over when it is left free.
                clipboard.selectionOwnerChanged(name, owner);
            }
        }
    }

    /**
     * The names of the selections {@code windowId} currently owns, so the code that destroys a
     * window or loses a client can release them and say why.
     */
    public List<String> selectionsOwnedBy(int windowId) {
        List<String> owned = new ArrayList<>();
        for (Map.Entry<String, Selection> entry : selections.entrySet()) {
            if (entry.getValue().owner() == windowId) {
                owned.add(entry.getKey());
            }
        }
        return owned;
    }

    /**
     * Releases every selection a window owns and tells the subscribers, keeping the time each was
     * taken: this is the owner going away rather than the selection changing hands, and a reference
     * server reports it that way.
     *
     * @param subtype {@code XFixesExtension.SELECTION_WINDOW_DESTROY} when the window is being
     *     destroyed, {@code SELECTION_CLIENT_CLOSE} when its client is disconnecting
     */
    public void releaseSelectionsOwnedBy(int windowId, byte subtype) {
        for (String name : selectionsOwnedBy(windowId)) {
            setSelectionOwner(name, 0, selectionTimestamp(name), subtype);
        }
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
            backendFactory = () -> BackendFactory.create(options);
        }
        if (!options.perClientWindows()) {
            // One window for the whole screen, opened now and kept for the life of the server.
            openSessionBackend(masterSession, new Rectangle(0, 0, screen.width(), screen.height()));
        }
        startClipboardBridge();
        startDropBridgeForWindow();
        suggestScale();
        listener = new ClientListener(this);
        listener.start();
        if (options.controlPort() >= 0) {
            // Useful with or without --detach-on-close: detaching on demand is how a user gets a
            // window out of the way without ending the session, and attaching is the only way back
            // to one that was closed while the flag was on.
            control = new io.github.oyzh1994.xserver.net.ControlListener(this, options.controlPort());
            control.start();
        }
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
            // A resize the user asked for goes first: it changes the windows the rectangle below is
            // the union of, so doing it after would put the window and the frame one tick apart.
            applyRequestedSize(session);
            // A held key repeats from here rather than from the client: the tick is where the
            // server's own periodic work lives, and the lock is already held.
            inputFor(session.screen()).pollAutoRepeat(System.currentTimeMillis());
            XWindow source = identitySource(session.screen());
            if (source != session.identitySource()) {
                // The window the host window is named after, which is the largest one the client has
                // mapped - so it arrives with the first window the client maps and follows along if
                // the client replaces it.
                boolean firstTopLevel = session.identitySource() == null && source != null;
                session.setIdentitySource(source);
                session.setIdentity(source == null
                        ? WindowIdentity.NONE
                        : WindowIdentity.of(atoms, source));
                if (firstTopLevel) {
                    // The client's first top level window has been mapped, and the host window is
                    // the frame around it: without this it stays behind whatever the user was
                    // looking at when they started the application, grey and unfocused. The raise
                    // itself waits for a frame - see the end of this tick - and happens once per
                    // session: a client's later windows are its own business, and a popup is not a
                    // reason to pull the window out from under the user's hands.
                    session.setRaisePending(true);
                }
            }
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
                    Rectangle was = session.viewport();
                    session.setViewport(settled);
                    session.screen().framebuffer().markAllDirty();
                    viewportChanged = true;
                    // The window following its content is otherwise invisible from the server side,
                    // and a window that jumps or shrinks is exactly what this line accounts for.
                    log.debug("display :{} window now shows {}, was {}", options.displayNumber(),
                            settled, was);
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
            // The cursor a client subscribed to hearing about, from here rather than from the
            // pointer's own path: it is the display that knows which window the pointer is in, and
            // the answer is the same whoever asks.
            XCursor displayed = session.input().displayedCursor();
            if (!session.cursorBaselineTaken()) {
                // Nothing is announced until there is a previous value to have changed from.
                session.setCursorBaselineTaken(true);
                session.setNotifiedCursor(displayed);
            } else if (displayed != session.notifiedCursor()) {
                session.setNotifiedCursor(displayed);
                if (displayed != null) {
                    XFixesExtension.notifyCursor(this, session.screen(), displayed.serial(),
                            events.time());
                }
            }
            if (drops != null && session == masterSession) {
                // A drag that is waiting for the target to answer is advanced here, next to the rest
                // of the work that happens on the server's own tick.
                drops.pump();
            }
            rememberWindowPosition(session);
            damage = session.screen().framebuffer().takeDamage();
        } finally {
            serverLock.unlock();
        }

        if (session.detached()) {
            // A session with no window on purpose (backlog D1): whatever window it still had is
            // closed here, where the lock is not held and the toolkit's thread may be waited on,
            // and no new one is opened. Everything above this line still happened - the client's
            // requests were answered, its keys repeat, its damage was taken - so the session is
            // alive; the frame it drew is simply not shown to anybody until one attaches.
            closeSessionBackend(session);
            return;
        }
        if (session.backend() == null) {
            // Nothing mapped yet: the window is opened by the first window a client maps, so a
            // connection that only asks questions never opens one. The master screen has no window
            // at all in per-client mode - it is not shown to anybody, it only exists so the
            // in-process tests and single-window mode have a screen to build on.
            if (wanted == null || (session.client() == null && options.perClientWindows())) {
                return;
            }
            try {
                openSessionBackend(session, wanted);
            } catch (RuntimeException e) {
                // A window that cannot be opened means nothing this server does will ever be seen.
                // Carrying on would be a display server that answers clients and shows nobody
                // anything - measured: with the JavaFX toolkit on a machine that cannot show a
                // window the tick thread parked for ever and the server went on serving, its only
                // explanation a stack trace from a render thread. Stop, and say why in one line.
                log.error("cannot show display :{} with the {} backend: {}",
                        options.displayNumber(), options.backend(), e.getMessage());
                System.err.println("xserver: cannot show display :" + options.displayNumber() + ": "
                        + e.getMessage());
                stop();
                return;
            }
        } else {
            if (session.takeScreenResized()) {
                // The screen grew while the lock was held; a backend that wrapped the old pixels has
                // to be told before it is asked to present them.
                session.backend().screenResized(session.screen());
            }
            if (viewportChanged) {
                session.backend().setViewport(wanted);
            }
        }
        DisplayBackend window = session.backend();
        if (window == null) {
            return;
        }
        // How large the window may be dragged, which is not the screen any more: the screen grows to
        // fit what the user asks for, so a limit at the screen's current edge would stop the drag
        // that was supposed to grow it. What is left is `--max-screen` - the size beyond which a
        // client could not be described the screen at all - less where the content starts on it.
        java.awt.Dimension maxArea = new java.awt.Dimension(
                options.maxScreenWidth() - session.viewport().x,
                options.maxScreenHeight() - session.viewport().y);
        if (maxArea.width > 0 && maxArea.height > 0 && !maxArea.equals(session.maxClientArea())) {
            session.setMaxClientArea(maxArea);
            window.setMaxClientArea(maxArea.width, maxArea.height);
        }
        if (session.identity() != session.appliedIdentity()) {
            // Only when it is a new value: a session whose client is not retitling itself produces
            // one identity object, and this is a reference comparison against it.
            WindowIdentity identity = session.identity();
            session.setAppliedIdentity(identity);
            window.setWindowTitle(identity.title());
            window.setWindowIcon(identity.iconArgb(), identity.iconWidth(), identity.iconHeight());
            // What the client asked the window manager for, applied to the frame: full screen is
            // shown as the display, and the urgency hint brings the window forward once - the two
            // things a forwarded application cannot do for itself when the frame is not its own.
            if (identity.fullScreen() != session.appliedFullScreen()) {
                session.setAppliedFullScreen(identity.fullScreen());
                window.setFullScreen(identity.fullScreen());
            }
            if (identity.urgent() != session.appliedUrgent()) {
                session.setAppliedUrgent(identity.urgent());
                if (identity.urgent()) {
                    window.raise();
                }
            }
            // Which window a client's title ends up on is otherwise only visible in the host
            // window's title bar, which nobody looking at a log has.
            log.debug("display :{} window is now \"{}\"{}", options.displayNumber(),
                    identity.title(), identity.iconArgb() == null ? ""
                            : " with a " + identity.iconWidth() + "x" + identity.iconHeight()
                                    + " icon");
        }
        if (session.idle() != session.idleApplied()) {
            session.setIdleApplied(session.idle());
            window.setIdle(session.idle());
        }
        if (window.isClosed()) {
            onWindowClosed(session);
            return;
        }
        if (session.raisePending() && session.framePresented()) {
            // A frame has been on the screen since the client's first window appeared, so bringing
            // the host window forward now shows that window rather than an empty frame. The wait is
            // for *a* frame and not for damage in this tick: the tick a window is mapped in consumes
            // the damage that drew it, and a raise that waited for another one would wait for the
            // client to draw again, which a static window never does.
            session.setRaisePending(false);
            window.raise();
            log.debug("display :{} host window brought to the front for a new client window",
                    options.displayNumber());
        }
        if (session.takeFullFrame()) {
            // A window has just attached to a screen that was drawing with nobody watching: the
            // pixels are all still in the framebuffer, but the damage they were drawn with was
            // consumed frame by frame while there was no window, so waiting for more of it would
            // show the client's background until the client happened to draw again.
            damage = new Rectangle(session.viewport());
        }
        if (!session.idle() && damage != null) {
            session.setFramePresented(true);
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
     * How long the host window has to hold still before where it is gets written to the store. A
     * drag reports a position every frame; only where it came to rest is worth keeping.
     */
    private static final long WINDOW_POSITION_SETTLE_MILLIS = 400;

    /**
     * The part of a screen the client's windows occupy: the union of its mapped top level windows.
     *
     * <p>Their children are inside them without being counted, and so are override-redirect windows -
     * menus, tooltips, drag icons. Those are transient overlays, and a window whose size followed
     * them would grow the moment a context menu opened and shrink back the moment it closed, which
     * is a window that jumps every time the user right-clicks. A window manager sizes the frame to
     * the client's window and lets a popup be clipped by the screen; this is the same rule with the
     * frame standing in for the screen.
     *
     * <p>The exception is a client that has mapped <em>nothing but</em> popups, which would otherwise
     * have no size at all: those are counted after all, so whatever it is showing is shown.
     */
    private static Rectangle contentBounds(Screen screen) {
        Rectangle windows = null;
        Rectangle popups = null;
        for (XWindow child : screen.root().children()) {
            if (!child.isMapped() || child.isInputOnly()) {
                continue;
            }
            Rectangle bounds = child.absoluteBoundsWithBorder();
            if (child.isOverrideRedirect()) {
                popups = popups == null ? bounds : popups.union(bounds);
            } else {
                windows = windows == null ? bounds : windows.union(bounds);
            }
        }
        Rectangle content = windows != null ? windows : popups;
        return content == null ? null
                : content.intersection(new Rectangle(0, 0, screen.width(), screen.height()));
    }

    /**
     * A window's properties changed. A host window takes its title and icon from one client window,
     * so if that is the one that changed, the host window has to be renamed.
     *
     * <p>Called from the request thread while the property is being written, which is what makes a
     * retitle show up as promptly as the client made it.
     */
    public void windowPropertyChanged(XWindow window, int property) {
        if (!WindowIdentity.isIdentityProperty(atoms.name(property))) {
            // A toolkit writes properties constantly - normal hints, the state, the class - and
            // rebuilding the identity for each of those would copy the icon of a large one for
            // nothing.
            return;
        }
        serverLock.lock();
        try {
            for (ScreenSession session : sessions) {
                if (session.identitySource() == window) {
                    session.setIdentity(WindowIdentity.of(atoms, window));
                }
            }
        } finally {
            serverLock.unlock();
        }
    }

    /**
     * The window a session shows in its title bar: the largest top level window its client has
     * mapped.
     *
     * <p>Largest rather than first, because the first is often a hidden 1x1 window a toolkit keeps
     * for its own use, and override-redirect windows are left out entirely - a menu has no title of
     * its own, and an application that retitles itself does not retitle its menus.
     */
    private static XWindow identitySource(Screen screen) {
        XWindow best = null;
        long bestArea = 0;
        for (XWindow child : screen.root().children()) {
            if (!child.isMapped() || child.isInputOnly() || child.isOverrideRedirect()) {
                continue;
            }
            long area = (long) child.width() * child.height();
            if (best == null || area > bestArea) {
                best = child;
                bestArea = area;
            }
        }
        return best;
    }

    /**
     * Resizes the client's windows to the size the user dragged the host window to.
     *
     * <p>The host window is the frame around the client's window, so stretching it is the gesture a
     * window manager's frame has always had: the client's window becomes that size and is told so
     * with a ConfigureNotify. A client that refuses - one whose window has a fixed size - keeps its
     * own size instead, and the frame is then simply larger than what it frames.
     *
     * <p>The size is capped at the screen: the framebuffer behind it is not resized, so a window
     * stretched past the screen would have nothing to show in the extra area.
     */
    private void applyRequestedSize(ScreenSession session) {
        java.awt.Dimension requested = session.input().takeRequestedSize();
        if (requested == null) {
            return;
        }
        Screen screen = session.screen();
        // Before anything is measured: a drag that asked for more than the screen can show grows the
        // screen to fit it, so the extents below are worked out against the size there will be rather
        // than the one there was.
        growScreenToFit(screen, requested);
        boolean resized = false;
        boolean capped = false;
        for (XWindow window : screen.root().children()) {
            if (!window.isMapped() || window.isInputOnly() || window.isOverrideRedirect()) {
                continue;
            }
            // A dimension the drag did not change is left alone rather than set to the same number
            // arrived at differently: a window that hangs off the left of the screen and one that
            // starts inside it need different widths to show the same amount, and rewriting the one
            // the user did not touch would resize the client's window for nothing - which its own
            // layout then reacts to.
            int width = visibleExtent(window.x(), window.width(), screen.width())
                    == requested.width
                    ? window.width()
                    : windowExtent(requested.width, window.x(), screen.width());
            int height = visibleExtent(window.y(), window.height(), screen.height())
                    == requested.height
                    ? window.height()
                    : windowExtent(requested.height, window.y(), screen.height());
            capped |= requested.width != visibleExtent(window.x(), width, screen.width())
                    || requested.height != visibleExtent(window.y(), height, screen.height());
            if (width == window.width() && height == window.height()) {
                continue;
            }
            windows().resizeWindow(window, width, height);
            resized = true;
        }
        if (capped) {
            // Debug rather than info: a drag delivers a resize a frame at a time, so holding the
            // pointer past the edge would write this line sixty times a second.
            log.debug("a window on display :{} was dragged to {}x{}, which the {}x{} screen cannot "
                            + "show all of", options.displayNumber(), requested.width,
                    requested.height, screen.width(), screen.height());
        }
        if (resized) {
            screen.framebuffer().markAllDirty();
        }
    }

    /**
     * Grows the screen when a drag asked for more than it can show.
     *
     * <p>This is what "the screen follows the window" means here: the client's window can only be
     * told to be as large as the framebuffer behind it, so the framebuffer has to be the thing that
     * moves first. The growth is bounded by {@code --max-screen}: a screen is described to clients in
     * sixteen bit fields, and every pixel costs four bytes.
     *
     * <p>A screen with no mapped window on it is left alone - the drag would have had nothing to
     * resize - and so is one that is already large enough, which is every drag but the one that
     * reaches the edge.
     *
     * @return true when the screen grew, in which case the backend has to rebuild what it holds
     */
    private boolean growScreenToFit(Screen screen, java.awt.Dimension requested) {
        int wantedWidth = screen.width();
        int wantedHeight = screen.height();
        boolean anything = false;
        for (XWindow window : screen.root().children()) {
            if (!window.isMapped() || window.isInputOnly() || window.isOverrideRedirect()) {
                continue;
            }
            anything = true;
            // The window's own far edge has to be on the screen for this many pixels of it to be
            // visible, and a window that starts off the left or the top needs no more room than the
            // pixels it is showing.
            wantedWidth = Math.max(wantedWidth, requested.width + Math.max(0, window.x()));
            wantedHeight = Math.max(wantedHeight, requested.height + Math.max(0, window.y()));
        }
        if (!anything) {
            return false;
        }
        int width = Math.min(wantedWidth, options.maxScreenWidth());
        int height = Math.min(wantedHeight, options.maxScreenHeight());
        if (width == screen.width() && height == screen.height()) {
            if (wantedWidth > width || wantedHeight > height) {
                log.debug("display :{} asked for a {}x{} screen, which --max-screen holds at {}x{}",
                        options.displayNumber(), wantedWidth, wantedHeight, width, height);
            }
            return false;
        }
        log.info("growing the screen of display :{} to {}x{}: a client's window was dragged past it",
                options.displayNumber(), width, height);
        screen.resize(width, height);
        tellClientsTheScreenChanged(screen);
        for (ScreenSession session : sessions) {
            if (session.screen() == screen) {
                session.setScreenResized();
            }
        }
        return true;
    }

    /**
     * Tells the clients that asked to hear it that the root is a different size.
     *
     * <p>Two ways, because there are two kinds of client: the root window's own {@code ConfigureNotify}
     * - the root is a window and its geometry changed - and RandR's {@code ScreenChangeNotify}, which
     * is the one a modern toolkit, browser or terminal listens for. Until RandR was implemented the
     * first was all there was, which is why a client that had asked RandR about the display was never
     * told it had changed.
     */
    private void tellClientsTheScreenChanged(Screen screen) {
        randrExtension.screenChanged(this, screen);
        events.deliverToWindow(screen.root(), EventMasks.STRUCTURE_NOTIFY,
                (client, w, child, x, y) -> ConfigureNotifyEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(client.sequenceNumber())
                        .event(w.id())
                        .window(screen.root().id())
                        .x((short) 0)
                        .y((short) 0)
                        .width((short) screen.width())
                        .height((short) screen.height())
                        .borderWidth((short) 0)
                        .aboveSibling(0)
                        .overrideRedirect(false)
                        .build());
    }

    /** How much of a window of {@code size} at {@code origin} lies on a screen of {@code screenSize}. */
    private static int visibleExtent(int origin, int size, int screenSize) {
        return Math.max(0, Math.min(screenSize, origin + size) - Math.max(0, origin));
    }

    /**
     * The window size that puts {@code wanted} pixels of a window at {@code origin} on the screen.
     *
     * <p>Not the same number: the part of the window that hangs off the start of the screen is not
     * visible and has to be added back for the visible part to be what was asked for, and the window
     * cannot run past the far edge either. A window twenty pixels down the screen can therefore be
     * made 780 pixels tall where the screen is 800 - the other twenty are under the window's own top
     * edge, which no resize can bring back.
     */
    private static int windowExtent(int wanted, int origin, int screenSize) {
        return Math.min(wanted + Math.max(0, -origin), screenSize - Math.max(0, origin));
    }

    /**
     * Opens a screen's window, sized to show {@code viewport}. Never called with the lock held.
     */
    private void openSessionBackend(ScreenSession session, Rectangle viewport) {
        DisplayBackend window = session.client() == null && sharedBackend != null
                ? sharedBackend
                : backendFactory.get();
        session.setClientKey(clientKey(session));
        java.awt.Point remembered = rememberedWindowPosition(session);
        window.setViewport(viewport);
        window.start(session.screen(), options.displayNumber(), session.input());
        if (remembered != null) {
            // After start, because a backend has no window to move before it has one. The backend
            // clamps it: a position from a display that is no longer attached is not one this one
            // can show.
            window.setWindowPosition(remembered.x, remembered.y);
        }
        session.setBackend(window);
        session.setViewport(viewport);
        log.info("opened a window for display :{} showing {}{}", options.displayNumber(), viewport,
                remembered == null ? "" : " at " + remembered.x + "," + remembered.y);
    }

    /**
     * What a client is remembered by, or null when it publishes nothing to remember it by.
     *
     * <p>{@code WM_CLASS} and not the title: a title is what the user is looking at and changes with
     * the document, while the class is the application's own name for itself. It is read off the same
     * window the host window's title comes from - the largest mapped top level - and only at the
     * moment the window opens, which is before any title can have changed it.
     */
    private String clientKey(ScreenSession session) {
        XWindow source = session.identitySource();
        if (source == null) {
            return null;
        }
        int atom = atoms.intern("WM_CLASS", true);
        if (atom == AtomTable.NONE) {
            return null;
        }
        XProperty property = source.properties().get(atom);
        if (property == null || property.data().length == 0) {
            return null;
        }
        // Two NUL separated strings, instance then class, and it is the class that names the
        // application: "navigator", "Navigator" for the same browser, and a second window of the
        // same application is the same window as far as this is concerned.
        String[] parts = new String(property.data(),
                java.nio.charset.StandardCharsets.ISO_8859_1).split("\0");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].isEmpty()) {
                return parts[i];
            }
        }
        return null;
    }

    /** Where this session's window was the last time, or null when it has not been seen before. */
    private java.awt.Point rememberedWindowPosition(ScreenSession session) {
        return session.clientKey() == null ? null : windowStore().position(session.clientKey());
    }

    private synchronized HostWindowStore windowStore() {
        if (windows == null) {
            windows = hostWindowStore != null ? hostWindowStore : HostWindowStore.open();
        }
        return windows;
    }

    /** The store to remember windows in, for a test or an embedder that wants its own. */
    public void setHostWindowStore(HostWindowStore store) {
        this.hostWindowStore = store;
        this.windows = null;
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
     * Where the user is holding a client's window, written out once it has held still.
     *
     * <p>Written from the tick rather than from the backend that reported the move, for two reasons:
     * the backend is on the toolkit's thread, where writing a file would be work done between the
     * user's drag and the next frame, and a drag reports a position per frame - a file per frame is
     * a file that is never read afterwards. The settle is the same trick the viewport uses.
     */
    private void rememberWindowPosition(ScreenSession session) {
        java.awt.Point moved = session.input().hostWindowPosition();
        if (moved == null || session.clientKey() == null) {
            return;
        }
        if (session.windowPosition() == null) {
            // The first position a session hears about is where the window opened, not a move: it is
            // the platform choosing a spot, or this server putting the window back where it was last
            // time. Recording it would overwrite what was remembered with the very place the restore
            // was about to change - measured happening, on the first live run.
            session.setWindowPosition(moved);
            return;
        }
        if (!moved.equals(session.windowPosition())) {
            // A position this server has not seen before, which is where a drag starts from.
            session.setWindowPosition(moved);
            session.setPositionHeldSince(System.currentTimeMillis());
            return;
        }
        if (moved.equals(session.persistedWindowPosition()) || session.positionHeldSince() == 0
                || System.currentTimeMillis() - session.positionHeldSince()
                        < WINDOW_POSITION_SETTLE_MILLIS) {
            return;
        }
        // The same position, and it has held still: this is where the user left the window.
        session.setPersistedWindowPosition(moved);
        session.setPositionHeldSince(0);
        windowStore().rememberPosition(session.clientKey(), moved.x, moved.y);
        log.debug("display :{} window for {} is at {},{}", options.displayNumber(),
                session.clientKey(), moved.x, moved.y);
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
        if (options.detachOnClose()) {
            // The window is gone and the session is not (backlog D1): the clients keep running with
            // nobody showing them, and the control port can put a window back. Only the flags are
            // set here - the tick is what closes the backend, because closing one waits on the
            // toolkit's thread and this is called from the tick with the lock already released.
            detachSession(session);
            log.info("display :{} window closed and --detach-on-close is on: {} client(s) keep"
                            + " running with no window; attach again with the control port",
                    options.displayNumber(), clients.size());
            return;
        }
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
     * A session with no window, and none to be opened until something asks for one (backlog D1).
     *
     * <p>Only the state is set here. Closing the window that may still be up, and opening the next
     * one, are the display tick's work: both reach into a toolkit's own thread, which must not
     * happen while the server lock is held (this is called from a control connection's thread, or
     * from the tick itself after it has released the lock).
     */
    public void detachSession(ScreenSession session) {
        session.setDetached(true);
        // Nothing to bring forward, and a raise that stayed pending would fire at whatever window
        // came back - the trap C2's raise has when there is no window at all.
        session.setRaisePending(false);
        session.setFullFrame(false);
    }

    /** Puts a window back on a detached session, showing the whole of what it has been drawing. */
    public void attachSession(ScreenSession session) {
        if (!session.detached()) {
            return;
        }
        session.setDetached(false);
        // The damage those pixels were drawn with went with the frames that had no window to present
        // it to, so the frame this one comes back with is the whole viewport - see takeFullFrame.
        session.setFullFrame(true);
    }

    /**
     * The port the control commands are answered on, or 0 when the server has none: the bound one,
     * which is what an embedder that asked for port 0 needs, or the configured one.
     */
    public int controlPort() {
        return control == null ? 0 : control.boundPort();
    }

    /**
     * One command from the control port, answered in one line (backlog D1).
     *
     * <p>The commands are the three things a user with no window can want: put a window back
     * ({@code attach}), take the window away without ending the session ({@code detach}), and ask
     * what is going on ({@code status}). They set state and return - the tick is what opens and
     * closes windows - so the answer says what was asked for rather than what has already happened,
     * which is the honest thing for a mechanism whose work happens on the next frame.
     */
    public String controlCommand(String command) {
        String name = command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
        serverLock.lock();
        try {
            return switch (name) {
                case "attach" -> {
                    int attached = 0;
                    for (ScreenSession session : sessions) {
                        if (session.detached() && canHaveAWindow(session)) {
                            attachSession(session);
                            attached++;
                        }
                    }
                    yield attached == 0
                            ? "nothing to attach: " + clients.size() + " client(s) connected"
                            : "opening a window for " + attached + " session(s); "
                                    + clients.size() + " client(s) connected";
                }
                case "detach" -> {
                    int detached = 0;
                    for (ScreenSession session : sessions) {
                        if (!session.detached() && canHaveAWindow(session)) {
                            detachSession(session);
                            detached++;
                        }
                    }
                    yield detached == 0
                            ? "nothing to detach: " + clients.size() + " client(s) connected"
                            : "closing the window of " + detached + " session(s); "
                                    + clients.size() + " client(s) keep running";
                }
                case "status" -> {
                    int windows = 0;
                    int detached = 0;
                    for (ScreenSession session : sessions) {
                        if (session.backend() != null) {
                            windows++;
                        }
                        if (session.detached()) {
                            detached++;
                        }
                    }
                    yield "display :" + options.displayNumber() + " sessions=" + sessions.size()
                            + " windows=" + windows + " detached=" + detached
                            + " clients=" + clients.size();
                }
                case "" -> "say attach, detach or status";
                default -> "unknown command \"" + name + "\": attach, detach or status";
            };
        } finally {
            serverLock.unlock();
        }
    }

    /**
     * Whether a session is one a window can be opened for at all.
     *
     * <p>The master screen of per-client mode is not: it exists so the tests and single-window mode
     * have a screen to build on, and its clients have windows of their own.
     */
    private boolean canHaveAWindow(ScreenSession session) {
        return session.client() != null || !options.perClientWindows();
    }

    /**
     * Asks every client to close its windows, once, and starts the clock on the grace period.
     */    private void askClientsToClose() {
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

    /**
     * Marks the part of a screen that a request drew into, in the drawable's own coordinates.
     *
     * <p>The rectangle comes from the rasterizer, which is the only thing that knows which pixels a
     * request touched; see {@code Rasterizer.drawnBounds}. A request that drew nothing marks
     * nothing, which is why this takes a rectangle rather than a screen.
     */
    public void markScreenDirty(Screen screen, Rectangle drawn) {
        screen.framebuffer().markDirty(drawn.x, drawn.y, drawn.x + drawn.width,
                drawn.y + drawn.height);
    }

    public void stop() {
        running = false;
        if (clipboard != null) {
            clipboard.stop();
        }
        if (drops != null) {
            drops.stop();
        }
        if (control != null) {
            control.stop();
        }
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
