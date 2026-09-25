package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.input.Keymap;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.nio.IntBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Presents the screen in a JavaFX window.
 *
 * <p>The window shows a {@link WritableImage} whose pixel buffer wraps the framebuffer's own
 * {@code int[]}: sharing the array means presenting a frame is a matter of saying which rectangle
 * changed, never of copying pixels. What the window shows is a *sub-rectangle* of that image - the
 * part of the screen its client's windows occupy - so scene coordinates are screen coordinates
 * shifted by the rectangle's origin, and both the blit and the input path do that shift.
 *
 * <p>The runtime is started with {@link Platform#startup} rather than {@code Application.launch}:
 * this project loads JavaFX from the classpath, and launching through an {@code Application}
 * subclass is only supported when the jars are on the module path. Starting the toolkit directly
 * also lets the server keep its own main loop instead of handing the main thread to JavaFX.
 */
public final class JavaFxBackend implements DisplayBackend {

    private static final Logger log = LoggerFactory.getLogger(JavaFxBackend.class);

    /**
     * How long the toolkit gets to run a task before it counts as unusable.
     */
    private static final long STARTUP_TIMEOUT_SECONDS = 1;

    /**
     * True once the toolkit has been started and has run a task for us.
     */
    private static boolean toolkitStarted;

    private final String title;
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * Guards {@link #pendingDamage} and {@link #flushScheduled}.
     */
    private final Object damageLock = new Object();

    /**
     * The damage waiting to be flushed, grown by every {@link #present} until the FX thread runs.
     */
    private Rectangle pendingDamage;
    /**
     * The sprite that goes with that damage; the latest one wins, since only it is on screen.
     */
    private CursorSprite pendingCursor;
    private boolean flushScheduled;

    private volatile boolean closed;

    private InputSink input;

    /**
     * The screen's framebuffer, used by the diagnostic dump and nothing else.
     */
    private Framebuffer framebuffer;

    // Everything below is only ever touched on the JavaFX Application Thread.
    private Stage stage;
    private Scene scene;
    /**
     * True while the platform pointer is over the window, so {@link #hostCursor} can decide.
     */
    private boolean pointerOverWindow;
    /**
     * True while no client is connected, so the window has no pointer of its own to show.
     */
    private boolean idle;
    /**
     * True while the backend is resizing the window itself, so the size listener does not report
     * the change back as a resize the user made.
     */
    private boolean adjustingWindow;
    /**
     * True while the backend is placing the window itself, so the move listeners do not report a
     * remembered position back as a move the user made.
     */
    private boolean placingWindow;
    /**
     * The part of the screen this window shows, in framebuffer coordinates. Zero-sized until the
     * server sets it, which covers the whole screen: that is what single-window mode wants.
     */
    private Rectangle viewport = new Rectangle();
    /**
     * How many windows are open, so each new one can be placed beside the last.
     */
    private static final java.util.concurrent.atomic.AtomicInteger LIVE_WINDOWS =
            new java.util.concurrent.atomic.AtomicInteger();
    private GraphicsContext graphics;
    private WritableImage image;
    private PixelBuffer<IntBuffer> pixelBuffer;

    /**
     * The pointer sprite as a JavaFX image, rebuilt when a client replaces the cursor.
     */
    private WritableImage cursorImage;
    private int[] cursorPixels;

    /**
     * The image the framebuffer is presented through. Exposed so a test can read it back and prove
     * the pixels reached JavaFX, which a window on a hidden display cannot show.
     */
    public WritableImage image() {
        return image;
    }

    /**
     * How many framebuffer pixels one logical one is worth, from the screen. A display that draws
     * two device pixels per logical one needs a screen of twice the pixels to be sharp in the same
     * space, and the window then shows that larger picture at the smaller logical size - one
     * framebuffer pixel per device pixel, which is the whole point.
     */
    private int scale = 1;
    private int screenWidth;
    private int screenHeight;
    private int pointerX;
    private int pointerY;
    private int heldButtons;

    public JavaFxBackend(String title) {
        this.title = title;
    }

    /**
     * Starts the JavaFX runtime and waits until it is really running, throwing when JavaFX cannot
     * be used at all. {@code BackendFactory} calls this before choosing this backend so the server
     * can fall back to Swing instead of failing; further calls are no-ops.
     */
    public static void verifyAvailable() {
        startupToolkit();
    }

    @Override
    public String name() {
        return "javafx";
    }

    @Override
    public void start(Screen screen, int displayNumber, InputSink input) {
        startupToolkit();
        this.input = input;
        this.scale = screen.scale();
        Framebuffer framebuffer = screen.framebuffer();
        this.framebuffer = framebuffer;
        int width = screen.width();
        int height = screen.height();
        // The framebuffer is row major with stride == width, so its array is already laid out the
        // way the pixel buffer wants it and can be wrapped rather than copied.
        IntBuffer pixels = IntBuffer.wrap(framebuffer.pixels());
        try {
            runOnFxThreadAndWait(() -> openWindow(width, height, pixels));
        } catch (RuntimeException | Error e) {
            // An open toolkit would keep the JVM alive through its non-daemon FX thread even though
            // this backend never got as far as showing anything.
            Platform.exit();
            throw e;
        }
        javafx.stage.Screen display = javafx.stage.Screen.getPrimary();
        log.info("javafx window {}x{} for display :{}, on a {}x{} display at scale {}x{}",
                width, height, displayNumber, display.getBounds().getWidth(),
                display.getBounds().getHeight(), display.getOutputScaleX(), display.getOutputScaleY());
    }

    @Override
    public void present(Screen screen, Rectangle damage, CursorSprite cursor) {
        if (closed || damage == null) {
            return;
        }
        // The pixels live in the buffer the framebuffer draws into, so nothing has to be copied
        // here: only the union of the damage since the last flush is handed to the FX thread.
        synchronized (damageLock) {
            pendingDamage = pendingDamage == null
                    ? new Rectangle(damage)
                    : pendingDamage.union(damage);
            pendingCursor = cursor;
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        if (Platform.isFxApplicationThread()) {
            flushDamage();
            return;
        }
        try {
            Platform.runLater(this::flushDamage);
        } catch (IllegalStateException runtimeStopped) {
            // Platform.exit() raced with the last frame; there is nothing left to show anyway.
            closed = true;
        }
    }

    @Override
    public void setViewport(Rectangle wanted) {
        runOnFxThreadAndWait(() -> {
            if (wanted.equals(viewport)) {
                return;
            }
            viewport = new Rectangle(wanted);
            Stage open = stage;
            if (open == null) {
                // Called before the window exists: openWindow will size itself from the rectangle.
                return;
            }
            Canvas canvas = canvasOf(open.getScene());
            // Sizing the window to the content is the backend's own doing, and the size listener
            // would otherwise read it as the user dragging the edge and ask for that size again.
            adjustingWindow = true;
            try {
                canvas.setWidth(logical(viewport.width));
                canvas.setHeight(logical(viewport.height));
                graphics = canvas.getGraphicsContext2D();
                drawViewport();
                // The window is sized to what it shows, by setting its size rather than by
                // sizeToScene(): the canvas is unmanaged - deliberately, so it cannot put a floor
                // under the window the user cannot drag below - and an unmanaged child contributes
                // nothing to its parent's preferred size, so sizeToScene() leaves the window at the
                // size the scene was built with. A window whose content changed therefore used to
                // keep the size the user had dragged it to.
                //
                // The decoration is not re-measured here: the platform applies a new window size
                // asynchronously, so a measurement taken now would read the old size and be wrong by
                // a title bar's worth - once per viewport change, which walks the window down a
                // title bar at a time. It was measured when the window was opened, where it is known
                // to fit its content, and it does not change while the window lives.
                open.setWidth(canvas.getWidth() + decorationWidth);
                open.setHeight(canvas.getHeight() + decorationHeight);
                if (log.isDebugEnabled()) {
                    log.debug("window sized to {}x{} for content {}x{}",
                            open.getWidth(), open.getHeight(), viewport.width, viewport.height);
                }
                keepOnScreen();
            } finally {
                adjustingWindow = false;
            }
        });
    }

    @Override
    public void setWindowTitle(String title) {
        runOnFxThreadAndWait(() -> {
            if (stage != null) {
                // Null means the client publishes no title: the server's own name is the fallback.
                stage.setTitle(title == null || title.isEmpty() ? this.title : title);
            }
        });
    }

    @Override
    public void setWindowIcon(int[] argb, int width, int height) {
        runOnFxThreadAndWait(() -> {
            if (stage == null) {
                return;
            }
            if (argb == null || width <= 0 || height <= 0) {
                stage.getIcons().clear();
                return;
            }
            WritableImage icon = new WritableImage(width, height);
            icon.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), argb, 0, width);
            stage.getIcons().setAll(icon);
        });
    }

    @Override
    public void setMaxClientArea(int width, int height) {
        runOnFxThreadAndWait(() -> {
            if (stage == null || width <= 0 || height <= 0) {
                return;
            }
            stage.setMaxWidth(logical(width) + decorationWidth);
            stage.setMaxHeight(logical(height) + decorationHeight);
            if (log.isDebugEnabled()) {
                // How far the window may be dragged is otherwise only visible by dragging it, and a
                // limit that is somehow too small cannot be resized out of.
                log.debug("window may be dragged up to {}x{} of content, plus {}x{} of decoration",
                        width, height, decorationWidth, decorationHeight);
            }
        });
    }

    /**
     * What the platform adds around the content - a title bar, and borders where the platform has
     * any - measured while the window fits the content exactly.
     *
     * <p>Measured there rather than when the limit is applied, because by then the window may have
     * been dragged larger than its content, and the difference would read as decoration that does
     * not exist. {@link #setMaxClientArea} limits a window, not the area the client is drawn in, so
     * the decoration has to be given back to it.
     */
    private void rememberDecoration() {
        if (stage == null || scene == null) {
            return;
        }
        decorationWidth = Math.max(0, stage.getWidth() - scene.getWidth());
        decorationHeight = Math.max(0, stage.getHeight() - scene.getHeight());
    }

    private double decorationWidth;
    private double decorationHeight;

    /**
     * The window's content area changed size. Most such changes are the backend's own, sizing the
     * window to the client's window; {@link #isUserResize} tells the user's apart from those, and
     * only the user's are reported.
     *
     * <p>What the client's window should become is the size of the area the client is drawn in, not
     * the size of the window: the window carries a title bar and borders, and a client resized to
     * include those would be a title bar too tall.
     */
    private void clientAreaChanged() {
        if (scene == null || input == null || stage == null) {
            return;
        }
        int width = (int) Math.round(scene.getWidth() * scale);
        int height = (int) Math.round(scene.getHeight() * scale);
        if (!isUserResize(adjustingWindow, width, height, viewport)) {
            return;
        }
        input.windowResized(width, height);
    }

    /** The window was moved by the user, unless this backend was the one moving it. */
    private void windowMovedByUser() {
        if (placingWindow || adjustingWindow || stage == null || input == null) {
            return;
        }
        input.windowMoved((int) Math.round(stage.getX()), (int) Math.round(stage.getY()));
    }

    @Override
    public void setWindowPosition(int x, int y) {
        runOnFxThreadAndWait(() -> {
            if (stage == null) {
                return;
            }
            // Clamped here rather than where the position was remembered, because only the backend
            // knows what the display has room for now: a remembered position from a monitor that is
            // no longer plugged in would put the window somewhere the user cannot reach it.
            javafx.geometry.Rectangle2D usable =
                    javafx.stage.Screen.getPrimary().getVisualBounds();
            java.awt.Point placed = io.github.oyzh1994.xserver.HostWindowStore.clamp(
                    new java.awt.Point(x, y), (int) Math.round(stage.getWidth()),
                    (int) Math.round(stage.getHeight()),
                    new java.awt.Rectangle((int) usable.getMinX(), (int) usable.getMinY(),
                            (int) usable.getWidth(), (int) usable.getHeight()));
            placingWindow = true;
            try {
                stage.setX(placed.x);
                stage.setY(placed.y);
            } finally {
                placingWindow = false;
            }
        });
    }

    /**
     * Whether the size the content area has taken is a resize the user made.
     *
     * <p>Most changes of that size are not: the backend sizes the window to the client's window
     * itself, and every one of those would otherwise be reported back as a resize, which the display
     * tick would answer by resizing the client to the size it already has - for ever. The two that
     * are not the backend's own are told apart by {@code adjusting} while it is working and by the
     * size being one the client has not already been told.
     */
    static boolean isUserResize(boolean adjusting, int width, int height, Rectangle viewport) {
        if (adjusting || width <= 0 || height <= 0) {
            return false;
        }
        return width != viewport.width || height != viewport.height;
    }

    /** The canvas a scene is drawing the screen into. */
    private static Canvas canvasOf(Scene scene) {
        return (Canvas) ((Pane) scene.getRoot()).getChildren().get(0);
    }

    /**
     * Paints the whole window from the framebuffer. Runs on the FX thread.
     */
    private void drawViewport() {
        if (graphics == null || image == null) {
            return;
        }
        graphics.drawImage(image, viewport.x, viewport.y, viewport.width, viewport.height,
                0, 0, logical(viewport.width), logical(viewport.height));
    }

    /**
     * Moves the window so that it stays inside the usable part of the display after a resize, which
     * a window that grew past the bottom right corner needs.
     */
    private void keepOnScreen() {
        if (stage == null) {
            return;
        }
        javafx.geometry.Rectangle2D usable = javafx.stage.Screen.getPrimary().getVisualBounds();
        double x = Math.min(stage.getX(), usable.getMaxX() - stage.getWidth());
        double y = Math.min(stage.getY(), usable.getMaxY() - stage.getHeight());
        stage.setX(Math.max(usable.getMinX(), x));
        stage.setY(Math.max(usable.getMinY(), y));
    }

    @Override
    public void stop() {
        closed = true;
        if (!stopped.compareAndSet(false, true) || !toolkitStarted) {
            return;
        }
        try {
            runOnFxThreadAndWait(() -> {
                releaseHostCursor();
                if (stage != null) {
                    stage.close();
                    stage = null;
                    LIVE_WINDOWS.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            log.debug("JavaFX had already stopped", e);
        }
        // The toolkit is deliberately left running: another client's window may still be open, and
        // Platform.exit() is one-way - it would take every window with it and refuse to start again.
    }

    @Override
    public void releaseToolkit() {
        if (!toolkitStarted) {
            return;
        }
        try {
            // The FX thread is not a daemon, so without this the JVM would stay alive after the
            // server has finished.
            runOnFxThreadAndWait(Platform::exit);
        } catch (RuntimeException e) {
            log.debug("JavaFX had already stopped", e);
        }
    }

    @Override
    public void setFullScreen(boolean fullScreen) {
        runOnFxThreadAndWait(() -> {
            if (stage == null || stage.isFullScreen() == fullScreen) {
                return;
            }
            // On macOS this is the platform's own full screen - another Space, with the animation -
            // rather than a window stretched to cover the menu bar: it is what the system does for
            // an application that asks to fill the display, and the user can leave it the usual way.
            stage.setFullScreen(fullScreen);
            log.debug("window is {}full screen", fullScreen ? "now " : "no longer ");
        });
    }

    @Override
    public void raise() {
        runOnFxThreadAndWait(() -> {
            if (stage != null) {
                // toFront and not requestFocus: a client asking for attention has not asked to take
                // the keyboard away from whatever the user is typing into.
                stage.toFront();
            }
        });
    }

    @Override
    public void screenResized(Screen screen) {
        Framebuffer framebuffer = screen.framebuffer();
        int width = screen.width();
        int height = screen.height();
        // Read the array here, on the tick thread, and hand it to the FX thread: the framebuffer
        // cannot be resized again while the tick is between the two, and the FX thread must not read
        // it while a client is drawing into it.
        IntBuffer pixels = IntBuffer.wrap(framebuffer.pixels());
        runOnFxThreadAndWait(() -> showNewScreenSize(width, height, pixels));
    }

    /**
     * Runs on the FX thread. The canvas is deliberately not rebuilt: it draws the part of the screen
     * the viewport shows, so its own size is the viewport's and does not change with the screen's -
     * only the image it draws from is a different size now.
     */
    private void showNewScreenSize(int width, int height, IntBuffer pixels) {
        if (closed) {
            return;
        }
        screenWidth = width;
        screenHeight = height;
        pixelBuffer = new PixelBuffer<>(width, height, pixels, PixelFormat.getIntArgbPreInstance());
        image = new WritableImage(pixelBuffer);
        drawViewport();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    /**
     * Runs on the FX thread.
     */
    private void openWindow(int width, int height, IntBuffer pixels) {
        screenWidth = width;
        screenHeight = height;
        if (viewport.isEmpty()) {
            viewport = new Rectangle(0, 0, width, height);
        }
        // Keyboard events carry the position of the pointer, which starts wherever the pointer is
        // (the platform does not tell us); the middle of what the window shows is the least
        // misleading guess.
        pointerX = viewport.x + viewport.width / 2;
        pointerY = viewport.y + viewport.height / 2;

        // Premultiplied is not a preference: a PixelBuffer only accepts INT_ARGB_PRE or
        // BYTE_BGRA_PRE and rejects plain INT_ARGB outright. It makes no difference to the screen
        // itself, whose pixels are opaque, and the framebuffer array is shared rather than
        // converted either way.
        pixelBuffer = new PixelBuffer<>(width, height, pixels, PixelFormat.getIntArgbPreInstance());
        image = new WritableImage(pixelBuffer);
        Canvas canvas = new Canvas(logical(viewport.width), logical(viewport.height));
        graphics = canvas.getGraphicsContext2D();
        drawViewport();

        // A Pane rather than a StackPane, and the canvas left unmanaged: a StackPane centres what it
        // holds, and the canvas is only ever the same size as the scene until the user drags the
        // window - at which point centring it would shift every coordinate this backend reports by
        // half the difference. Unmanaged, too, so the canvas cannot set a minimum size on the
        // window: a canvas is not resizable, and one that is 1200 wide would stop the user from
        // ever shrinking the window below that.
        Pane root = new Pane(canvas);
        canvas.setManaged(false);
        Scene scene = new Scene(root, logical(viewport.width), logical(viewport.height),
                Color.BLACK);
        this.scene = scene;
        // The platform would otherwise draw its own arrow over the window, on top of the sprite the
        // server renders from the client's cursor: two pointers, and the host one always wins.
        // Leaving the pointer to the X client is also what makes a text cursor, a resize arrow or
        // an application that hides the pointer while it types show up as the client intended.
        // Every change of pointer or focus re-decides it, because hiding the platform pointer is
        // only ever a loan - see hostCursor for what that costs.
        scene.setOnMouseEntered(event -> {
            enterWindow();
        });
        scene.setOnMouseExited(event -> {
            pointerOverWindow = false;
            applyHostCursor();
        });
        scene.setOnMouseMoved(event -> {
            enterWindow();
            pointerMoved(event);
            mouseButton(event, true);
        });
        scene.setOnMouseDragged(this::pointerMoved);
        scene.setOnMousePressed(event -> {
            hostEvent("pressed", event);
            enterWindow();
            mouseButton(event, true);
        });
        scene.setOnMouseReleased(event -> { hostEvent("released", event); mouseButton(event, false); });
        scene.setOnScroll(this::scrolled);
        // A drag from the host's desktop onto this window. Accepting it on drag-over is what makes
        // the platform report the drop at all, and the files it carries are the whole of what the X
        // side needs - see InputSink.hostDrop.
        scene.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            boolean taken = false;
            if (event.getDragboard().hasFiles()) {
                java.util.List<String> paths = new java.util.ArrayList<>();
                for (java.io.File file : event.getDragboard().getFiles()) {
                    paths.add(file.getAbsolutePath());
                }
                input.hostDrop(paths, screenX(event.getSceneX()), screenY(event.getSceneY()));
                taken = true;
            }
            event.setDropCompleted(taken);
            event.consume();
        });
        scene.setOnKeyPressed(event -> keyChanged(event.getCode(), true));
        scene.setOnKeyReleased(event -> keyChanged(event.getCode(), false));
        // The character the input method or the dead key produced, which is not the key that was
        // pressed: JavaFX reports it here, after the platform's own composition has run.
        scene.setOnKeyTyped(event -> input.keyTyped(event.getCharacter().isEmpty()
                ? 0 : event.getCharacter().charAt(0)));
        // And this is where an input method's *result* arrives - the text the user chose from the
        // candidate list. Measured: KEY_TYPED does not carry it, so a Mac's Chinese input method
        // typed into a forwarded window produced nothing at all until this was handled. The
        // committed text is what the user meant to type; the composition itself is the platform's
        // own window and never reaches the X client.
        scene.setOnInputMethodTextChanged(event -> {
            String committed = event.getCommitted();
            if (committed == null || committed.isEmpty()) {
                return;
            }
            log.debug("input method committed {} characters", committed.length());
            for (int i = 0; i < committed.length(); i++) {
                input.keyTyped(committed.charAt(i));
            }
        });

        stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);
        // The window is the frame around the client's window, so pulling its edge is the gesture a
        // window manager's frame has always had: the client's window is resized to match. A scene
        // listener rather than a stage one, because the content area is the client's size and the
        // stage's includes the decoration the platform adds.
        stage.setResizable(true);
        scene.widthProperty().addListener((observable, was, is) -> clientAreaChanged());
        scene.heightProperty().addListener((observable, was, is) -> clientAreaChanged());
        // Where the user drags the frame to is the one thing about the host window that no client
        // knows: it is remembered per client so the next session opens where this one was left.
        stage.xProperty().addListener((observable, was, is) -> windowMovedByUser());
        stage.yProperty().addListener((observable, was, is) -> windowMovedByUser());
        stage.sizeToScene();
        // Scene has no focus property of its own, so focus comes from the window.
        stage.focusedProperty().addListener((observable, wasFocused, focused) -> {
            input.focusChanged(focused);
            applyHostCursor();
        });
        stage.setOnCloseRequest(event -> {
            closed = true;
            // The window is going away with the pointer possibly hidden; the platform would keep it
            // hidden for the whole process, and a server whose display loop has exited may live on.
            releaseHostCursor();
        });
        stage.show();
        rememberDecoration();
        startPulseProbe();
        // The scene coordinates must stay equal to screen coordinates for the pointer mapping to be
        // right, so the window is placed inside the usable area rather than left to the platform,
        // which will happily put a large window under the menu bar or off the edge of the display.
        placeOnScreen(width, height);
        applyHostCursor();
    }

    /**
     * What the platform pointer should be: hidden over the window, so the sprite the server draws
     * from the client's cursor is the only pointer there, and the platform's own arrow everywhere
     * else - outside the window, and whenever the window is not the active one.
     *
     * <p>Hiding it means {@link Cursor#NONE}, which on macOS is not an empty cursor but a
     * process-wide {@code [NSCursor hide]} ({@code MacCursor.setVisible_impl}): left in place it
     * takes the pointer off the desktop and off every other application until the window's cursor
     * changes again. Handing the arrow back is therefore as much a part of the policy as hiding it,
     * which is why leaving the window and losing the focus both re-decide it.
     *
     * <p>An image cursor would avoid the global hide and does not work here: JavaFX hands the image
     * to the platform without complaint - {@code ImageCursor.getCurrentFrame()} returns an image
     * frame, not the default fallback - and macOS still draws its own arrow over the window. That is
     * true of a fully opaque image as much as a transparent one, so no choice of pixels fixes it:
     * an opaque red 32x32 cursor was measured drawing the standard arrow.
     */
    static Cursor hostCursor(boolean pointerOverWindow) {
        return pointerOverWindow ? Cursor.NONE : Cursor.DEFAULT;
    }

    /**
     * Which window currently holds the platform pointer hidden. There is one platform pointer for
     * the whole process, so "this window wants it hidden" is a claim on shared state: with two
     * windows open, the second one losing focus would otherwise hand the pointer back while it is
     * still over the first.
     */
    private static final AtomicReference<JavaFxBackend> CURSOR_OWNER = new AtomicReference<>();

    /**
     * Applies {@link #hostCursor} to the window. Runs on the FX thread.
     */
    private void applyHostCursor() {
        if (scene == null || stage == null) {
            return;
        }
        boolean wantHidden = !idle && hostCursor(pointerOverWindow) == Cursor.NONE;
        if (wantHidden) {
            CURSOR_OWNER.set(this);
        }
        if (CURSOR_OWNER.get() == this) {
            scene.setCursor(wantHidden ? Cursor.NONE : Cursor.DEFAULT);
            if (!wantHidden) {
                CURSOR_OWNER.compareAndSet(this, null);
            }
        } else {
            // Another window owns the pointer: this one leaves it alone rather than handing it back.
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    /**
     * Hands the platform pointer back, whatever state the window is in. Runs on the FX thread.
     */
    private void releaseHostCursor() {
        if (scene != null) {
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    @Override
    public void setIdle(boolean idle) {
        runOnFxThreadAndWait(() -> {
            this.idle = idle;
            applyHostCursor();
        });
    }

    /**
     * The cursor installed on the window, for a test that has no pointer to check it with.
     */
    public Cursor windowCursor() {
        return scene == null ? null : scene.getCursor();
    }

    /**
     * Reports how fast the toolkit is actually presenting and how long a pulse takes, at debug
     * level. Together with the flush counter this says whether a window that feels slow is missing
     * frames or spending too long on each one - and both are cheaper to read than to guess.
     */
    private void startPulseProbe() {
        long[] last = {System.nanoTime()};
        int[] count = {0};
        long[] total = {0};
        new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                total[0] += now - last[0];
                last[0] = now;
                if (log.isDebugEnabled() && ++count[0] % 120 == 0) {
                    log.debug("pulse {} frames, {} ms/frame, {} fps", count[0],
                            total[0] / 1_000_000.0 / count[0], 1e9 / (total[0] / (double) count[0]));
                }
            }
        }.start();
    }

    /**
     * Notes that the pointer is over the window, and hides the platform's arrow if that is new.
     *
     * <p>Not left to the enter event alone: a window that opens under a pointer which never moves
     * never gets one, and the arrow would sit on top of the client's cursor until it does.
     */
    private void enterWindow() {
        if (!pointerOverWindow) {
            pointerOverWindow = true;
            applyHostCursor();
        }
    }

    /**
     * Moves and resizes the window so all of it lands inside the usable part of the display.
     */
    private void placeOnScreen(int width, int height) {
        // Fully qualified: this class is in the same package as the server's own Screen.
        Rectangle2D usable = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.min(logical(width), usable.getWidth());
        double h = Math.min(logical(height), usable.getHeight());
        double offset = (LIVE_WINDOWS.getAndIncrement() % 8) * 32.0;
        stage.setX(usable.getMinX() + Math.max(0, (usable.getWidth() - w) / 2) + offset);
        stage.setY(usable.getMinY() + Math.max(0, (usable.getHeight() - h) / 2) + offset);
    }

    /**
     * Frames flushed and the time spent blitting them, reported at debug level. A window that feels
     * slow is either not being given frames or taking too long over each one, and this is the line
     * that says which.
     */
    private static final java.util.concurrent.atomic.AtomicLong FRAMES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BLIT_NANOS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Runs on the FX thread.
     */
    private void flushDamage() {
        fitWindowToViewport();
        Rectangle region;
        CursorSprite cursor;
        synchronized (damageLock) {
            region = pendingDamage;
            cursor = pendingCursor;
            pendingDamage = null;
            flushScheduled = false;
        }
        if (region == null || pixelBuffer == null) {
            return;
        }
        region = region.intersection(new Rectangle(0, 0, screenWidth, screenHeight));
        region = region.intersection(viewport);
        if (region.isEmpty()) {
            return;
        }
        Rectangle damage = region;
        // The buffer position must be zero when JavaFX uploads it. Anything that reads through the
        // buffer - a diagnostic, a future feature - can leave it at the end, and the upload then
        // fails with "only 0 elements remain", leaving the window frozen on an old frame.
        pixelBuffer.getBuffer().rewind();
        // updateBuffer repaints exactly the rectangle the callback reports, and the callback has
        // nothing to do because the pixels are already in place.
        pixelBuffer.updateBuffer(pb -> {
            pb.getBuffer().rewind();
            return new Rectangle2D(damage.x, damage.y, damage.width, damage.height);
        });
        if (System.getProperty("xserver.dumpFrame") != null) {
            scheduleDump();
        }
        long tempStart = System.nanoTime();
        graphics.drawImage(image, damage.x, damage.y, damage.width, damage.height,
                logical(damage.x - viewport.x), logical(damage.y - viewport.y),
                logical(damage.width), logical(damage.height));
        drawCursor(cursor);
        BLIT_NANOS.addAndGet(System.nanoTime() - tempStart);
        long frames = FRAMES.incrementAndGet();
        if (log.isDebugEnabled() && frames % 120 == 0) {
            log.debug("{} frames presented, {} ms per frame blitted", frames,
                    BLIT_NANOS.get() / 1_000_000.0 / frames);
        }
    }

    /**
     * Draws the pointer sprite over the part of the frame that was just blitted.
     *
     * <p>The canvas keeps what was drawn into it, so the sprite is painted last and the damage
     * region - which the server grows to cover the sprite's previous position as well - has already
     * been repainted from the framebuffer underneath it. That is what makes a moving sprite leave
     * nothing behind without touching the framebuffer itself.
     *
     * <p>Runs on the FX thread.
     */
    private void drawCursor(CursorSprite sprite) {
        if (sprite == null || graphics == null) {
            return;
        }
        if (sprite.pixels() != cursorPixels) {
            // The cursor was replaced by a client: build the image once for the new one. Its pixels
            // are cached per cursor, so a moving pointer keeps the same array.
            cursorPixels = sprite.pixels();
            cursorImage = new WritableImage(sprite.width(), sprite.height());
            cursorImage.getPixelWriter().setPixels(0, 0, sprite.width(), sprite.height(),
                    PixelFormat.getIntArgbInstance(), sprite.pixels(), 0, sprite.width());
        }
        if (!viewport.intersects(new Rectangle(sprite.x(), sprite.y(), sprite.width(),
                sprite.height()))) {
            // The pointer is outside what this window shows: another window's business, and drawing
            // it here would paint it twice.
            return;
        }
        graphics.drawImage(cursorImage, logical(sprite.x() - viewport.x),
                logical(sprite.y() - viewport.y), logical(sprite.width()),
                logical(sprite.height()));
    }

    /**
     * Diagnostic aid: with {@code -Dxserver.dumpFrame=<prefix>} the window's image and the
     * framebuffer behind it are written to PNG files once a second.
     *
     * <p>This exists because a window that shows nothing is otherwise hard to tell apart from a
     * server that drew nothing: comparing the two images says which side is wrong. It is read only,
     * and rewinds the shared buffer afterwards so the upload that follows is unaffected.
     */
    private void scheduleDump() {
        if (!dumpScheduled.compareAndSet(false, true)) {
            return;
        }
        String prefix = System.getProperty("xserver.dumpFrame");
        Thread thread = new Thread(() -> {
            for (int i = 0; i < DUMP_FRAMES; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                int frame = i;
                Platform.runLater(() -> {
                    dumpImage(prefix + "-image-" + frame + ".png");
                    dumpFramebuffer(prefix + "-buffer-" + frame + ".png");
                    dumpScene(prefix + "-scene-" + frame + ".png");
                });
            }
        }, "xserver-frame-dump");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * How many frames the dump captures, one a second. A client that takes a while to paint its
     * first frame can miss a thirty second window, so {@code -Dxserver.dumpFrames} lengthens it.
     */
    private static final int DUMP_FRAMES = Integer.getInteger("xserver.dumpFrames", 30);

    private final AtomicBoolean dumpScheduled = new AtomicBoolean();

    /**
     * Runs on the JavaFX thread.
     */
    private void dumpImage(String path) {
        if (image == null) {
            return;
        }
        int w = screenWidth;
        int h = screenHeight;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        write(out, path);
    }

    /**
     * Runs on the JavaFX thread; renders the scene the window is showing into an image.
     *
     * <p>The image dump above reads the {@code WritableImage} the framebuffer is wrapped in, which
     * is what this backend hands to the scene - and so it says nothing about what the window makes
     * of it. A scene that failed to repaint its canvas, or a canvas whose pixels never reached it,
     * leaves both of the other dumps correct while the window on screen shows something else
     * entirely, which is the one failure a window on a display nobody can look at hides.
     */
    private void dumpScene(String path) {
        Scene current = stage == null ? null : stage.getScene();
        if (current == null) {
            return;
        }
        javafx.scene.image.WritableImage shot = current.snapshot(null);
        int w = (int) shot.getWidth();
        int h = (int) shot.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader reader = shot.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        write(out, path);
    }

    /**
     * Keeps the window the size of what it shows. Runs on the FX thread, once a frame.
     *
     * <p>{@link #setViewport} sets the size when the content changes, but the platform applies a
     * window size asynchronously and a size the user is in the middle of dragging with wins. A drag
     * that the client refuses is the case that matters: the content does not grow with it, so the
     * viewport stops changing and nothing else would ever bring the frame back to what it frames -
     * the window would sit there larger than its content, with a band of nothing around it. This is
     * also what a window manager's frame does, and it is why the comparison has to be made every
     * frame rather than only when the viewport moves.
     */
    private void fitWindowToViewport() {
        Stage open = stage;
        if (open == null || adjustingWindow || viewport.isEmpty() || open.isFullScreen()) {
            // Full screen is the one time the window is deliberately not the size of its content:
            // the client asked to fill the display, and the platform is the one sizing the window.
            return;
        }
        double width = logical(viewport.width) + decorationWidth;
        double height = logical(viewport.height) + decorationHeight;
        if (Math.abs(open.getWidth() - width) < 1 && Math.abs(open.getHeight() - height) < 1) {
            return;
        }
        adjustingWindow = true;
        try {
            open.setWidth(width);
            open.setHeight(height);
        } finally {
            adjustingWindow = false;
        }
    }

    /**
     * Runs on the JavaFX thread; reads the framebuffer array, not the shared IntBuffer.
     */
    private void dumpFramebuffer(String path) {
        int[] source = framebuffer == null ? null : framebuffer.pixels();
        if (source == null) {
            return;
        }
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(screenWidth,
                screenHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, screenWidth, screenHeight, source, 0, screenWidth);
        write(out, path);
    }

    private static void write(java.awt.image.BufferedImage image, String path) {
        try {
            javax.imageio.ImageIO.write(image, "png", new java.io.File(path));
        } catch (Exception e) {
            log.warn("frame dump to {} failed", path, e);
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Reports a mouse event the host window itself received, before anything is done with it.
     *
     * <p>This exists because "the click did nothing" has two very different causes that look the same
     * from the client: the host window never passed the event on (the platform took it, or the
     * toolkit did not deliver it), or it passed on an event the client then ignored. The client side
     * is logged too, by {@code InputState} and the request log; this is the other end of the wire.
     */
    private void hostEvent(String what, MouseEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("host window {} {} at scene ({},{}), stage focused {}", what, event.getButton(),
                    event.getX(), event.getY(), stage != null && stage.isFocused());
        }
    }

    private void pointerMoved(MouseEvent event) {
        int x = screenX(event.getX());
        int y = screenY(event.getY());
        pointerX = x;
        pointerY = y;
        input.pointerMoved(x, y);
    }

    private void mouseButton(MouseEvent event, boolean pressed) {
        int x = screenX(event.getX());
        int y = screenY(event.getY());
        pointerX = x;
        pointerY = y;
        int button = xButton(event.getButton());
        if (pressed) {
            if (button == 0) {
                return;
            }
            heldButtons |= 1 << button;
            input.buttonChanged(button, true, x, y);
            return;
        }
        if (button != 0) {
            heldButtons &= ~(1 << button);
            input.buttonChanged(button, false, x, y);
            return;
        }
        // A release reports no button when the gesture started outside the window. Let go of
        // everything rather than leave a button stuck down in the server.
        for (int held = 1; held <= 3; held++) {
            if ((heldButtons & (1 << held)) != 0) {
                heldButtons &= ~(1 << held);
                input.buttonChanged(held, false, x, y);
            }
        }
    }

    private void scrolled(ScrollEvent event) {
        int x = screenX(event.getX());
        int y = screenY(event.getY());
        pointerX = x;
        pointerY = y;
        // X has no notion of a partial wheel: every notch is a press and release of 4/5, or of 6/7
        // for horizontal scrolling.
        if (event.getDeltaY() != 0) {
            wheel(event.getDeltaY() > 0 ? 4 : 5, x, y);
        } else if (event.getDeltaX() != 0) {
            wheel(event.getDeltaX() > 0 ? 7 : 6, x, y);
        }
    }

    private void wheel(int button, int x, int y) {
        input.buttonChanged(button, true, x, y);
        input.buttonChanged(button, false, x, y);
    }

    private void keyChanged(KeyCode code, boolean pressed) {
        int keycode = xKeycode(code);
        if (keycode == 0) {
            // No mapping means no keycode: reporting one would invent a key the client never has.
            return;
        }
        input.keyChanged(keycode, pressed, pointerX, pointerY);
    }

    /**
     * Turns a coordinate in the window into one on the screen.
     *
     * <p>The window shows a sub-rectangle of the screen - the part the client's windows occupy - so
     * the top left of the scene is the top left of that rectangle, not of the screen. Without the
     * offset every pointer position is reported as far off as that corner is from the origin, which
     * is nothing when the client's window reaches the origin and twenty pixels or more when it does
     * not: a menu then stops recognising the pointer near its edges and closes as soon as the
     * pointer is moved, because the position the client is told about has left the menu even though
     * the pointer has not.
     */
    static int screenCoordinate(double sceneCoordinate, int viewportOrigin, int scale,
                                int screenSize) {
        return clamp(viewportOrigin + sceneCoordinate * scale, screenSize);
    }

    /** A framebuffer measurement in the logical units the window is laid out in. */
    private double logical(double framebufferPixels) {
        return framebufferPixels / scale;
    }

    private int screenX(double sceneX) {
        return screenCoordinate(sceneX, viewport.x, scale, screenWidth);
    }

    private int screenY(double sceneY) {
        return screenCoordinate(sceneY, viewport.y, scale, screenHeight);
    }

    /**
     * Clamps a scene coordinate to the screen; a drag can leave it by a pixel or two.
     */
    private static int clamp(double value, int limit) {
        return Math.max(0, Math.min(limit - 1, (int) value));
    }

    private static int xButton(MouseButton button) {
        return switch (button) {
            case PRIMARY -> 1;
            case MIDDLE -> 2;
            case SECONDARY -> 3;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------
    // Key codes
    // ------------------------------------------------------------------

    /**
     * The X keycode a JavaFX key produces, or 0 when it has none.
     *
     * <p>The values are the standard X11 keycodes for a US layout, which is the mapping the
     * server's {@code Keymap} describes. Letters are not contiguous there, so they are looked up
     * by character: {@code a} is 38 and {@code z} is 52, with the punctuation keys of the home row
     * in between.
     *
     * <p>JavaFX reports one key code for both copies of a modifier, so Shift, Control and Alt
     * always produce the left variant (50, 37, 64) and the right handed keycodes are never sent.
     */
    static int xKeycode(KeyCode code) {
        int keycode = switch (code) {
            case ESCAPE -> 9;
            case DIGIT0 -> 19;
            case DIGIT1 -> 10;
            case DIGIT2 -> 11;
            case DIGIT3 -> 12;
            case DIGIT4 -> 13;
            case DIGIT5 -> 14;
            case DIGIT6 -> 15;
            case DIGIT7 -> 16;
            case DIGIT8 -> 17;
            case DIGIT9 -> 18;
            case MINUS -> 20;
            case EQUALS -> 21;
            case BACK_SPACE -> 22;
            case TAB -> 23;
            case OPEN_BRACKET -> 34;
            case CLOSE_BRACKET -> 35;
            case ENTER -> 36;
            case CONTROL -> 37;
            case SEMICOLON -> 47;
            case QUOTE -> 48;
            case BACK_QUOTE -> 49;
            case SHIFT -> 50;
            case BACK_SLASH -> 51;
            case COMMA -> 59;
            case PERIOD -> 60;
            case SLASH -> 61;
            case MULTIPLY -> 63;
            case ALT -> 64;
            case SPACE -> 65;
            case CAPS -> 66;
            case F1 -> 67;
            case F2 -> 68;
            case F3 -> 69;
            case F4 -> 70;
            case F5 -> 71;
            case F6 -> 72;
            case F7 -> 73;
            case F8 -> 74;
            case F9 -> 75;
            case F10 -> 76;
            case NUM_LOCK -> 77;
            case SCROLL_LOCK -> 78;
            case NUMPAD7 -> 79;
            case NUMPAD8 -> 80;
            case NUMPAD9 -> 81;
            case SUBTRACT -> 82;
            case NUMPAD4 -> 83;
            case NUMPAD5 -> 84;
            case NUMPAD6 -> 85;
            case ADD -> 86;
            case NUMPAD1 -> 87;
            case NUMPAD2 -> 88;
            case NUMPAD3 -> 89;
            case NUMPAD0 -> 90;
            case DECIMAL -> 91;
            case F11 -> 95;
            case F12 -> 96;
            case DIVIDE -> 106;
            case PRINTSCREEN -> 107;
            case ALT_GRAPH -> 108;
            case HOME -> 110;
            case UP -> 111;
            case PAGE_UP -> 112;
            case LEFT -> 113;
            case RIGHT -> 114;
            case END -> 115;
            case DOWN -> 116;
            case PAGE_DOWN -> 117;
            case INSERT -> 118;
            case DELETE -> 119;
            case PAUSE -> 127;
            case META -> 133;
            case CONTEXT_MENU -> 135;
            default -> 0;
        };
        // Keys the table does not name are still usable when they are a plain letter or digit.
        return keycode != 0 ? keycode : xKeycodeOfChar(code.getChar());
    }

    private static int xKeycodeOfChar(String character) {
        if (character == null || character.length() != 1) {
            return 0;
        }
        char value = character.charAt(0);
        if (value >= '0' && value <= '9') {
            return value == '0' ? 19 : 9 + (value - '0');
        }
        return Keymap.keycodeForLetter(value);
    }

    // ------------------------------------------------------------------
    // Toolkit
    // ------------------------------------------------------------------

    /**
     * How long the toolkit is given to come up before the server decides it cannot show anything.
     *
     * <p>Generous, because a cold JavaFX startup on a loaded machine is seconds rather than
     * milliseconds, and the cost of waiting is a delay while the cost of giving up too early is a
     * server that refuses to run on a machine that is merely slow.
     */
    private static final long TOOLKIT_START_TIMEOUT_MILLIS = 10_000;

    /**
     * Brings the toolkit up if it is not up already, and gives up if it will not come up.
     *
     * <p><b>The wait has to be bounded, and this is what the platform does without one.</b>
     * {@code Platform.startup} blocks until the toolkit is running, and where there is no display it
     * can block *for ever*: measured with {@code -Djava.awt.headless=true} on macOS,
     * {@code QuantumToolkit.startup} parks on a latch whose count never reaches zero, because the
     * render thread failed first ({@code ArrayIndexOutOfBoundsException: Index 0 out of bounds for
     * length 0}, an empty screen list). Nothing in the API reports that - no exception, no return
     * value - so the server used to come up, answer clients, and never show a frame, with a stack
     * trace from the render thread as its only explanation. The wait here is what turns that into a
     * failure the caller can act on.
     */
    private static synchronized void startupToolkit() {
        if (toolkitStarted) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                // The runnable is what proves it: Platform.startup calls it on the FX thread once
                // the toolkit is up, so counting it down is the difference between "the call
                // returned" and "JavaFX works".
                Platform.startup(ready::countDown);
            } catch (IllegalStateException alreadyRunning) {
                // Someone else started the runtime first, which is the state we wanted anyway.
                ready.countDown();
            } catch (Throwable t) {
                failure.set(t);
                ready.countDown();
            }
        }, "javafx-startup");
        starter.setDaemon(true);
        starter.start();
        try {
            if (!ready.await(TOOLKIT_START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the JavaFX toolkit did not start within "
                        + (TOOLKIT_START_TIMEOUT_MILLIS / 1000) + "s: this machine cannot show a"
                        + " window, so nothing would be forwarded. Run with --backend headless to"
                        + " serve clients without one.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting the JavaFX toolkit", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the JavaFX toolkit could not start: " + failure.get(),
                    failure.get());
        }
        // The server decides when the process is done, not the last window being closed.
        Platform.setImplicitExit(false);
        toolkitStarted = true;
    }

    /**
     * Runs the action on the FX thread and waits for it, rethrowing whatever it threw.
     */
    private static void runOnFxThreadAndWait(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the JavaFX toolkit did not start within "
                        + STARTUP_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the JavaFX thread", e);
        }
        Throwable thrown = failure.get();
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new IllegalStateException("the JavaFX thread failed", thrown);
        }
    }
}
