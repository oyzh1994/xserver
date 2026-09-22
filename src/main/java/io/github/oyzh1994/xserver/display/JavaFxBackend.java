package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.draw.Framebuffer;
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
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
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
 * changed, never of copying pixels. For the same reason the window is fixed to the screen size -
 * scene coordinates and screen pixels are then the same thing, which is what clients assume.
 *
 * <p>The runtime is started with {@link Platform#startup} rather than {@code Application.launch}:
 * this project loads JavaFX from the classpath, and launching through an {@code Application}
 * subclass is only supported when the jars are on the module path. Starting the toolkit directly
 * also lets the server keep its own main loop instead of handing the main thread to JavaFX.
 */
public final class JavaFxBackend implements DisplayBackend {

    private static final Logger log = LoggerFactory.getLogger(JavaFxBackend.class);

    /** How long the toolkit gets to run a task before it counts as unusable. */
    private static final long STARTUP_TIMEOUT_SECONDS = 10;

    /** True once the toolkit has been started and has run a task for us. */
    private static boolean toolkitStarted;

    private final String title;
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** Guards {@link #pendingDamage} and {@link #flushScheduled}. */
    private final Object damageLock = new Object();

    /** The damage waiting to be flushed, grown by every {@link #present} until the FX thread runs. */
    private Rectangle pendingDamage;
    /** The sprite that goes with that damage; the latest one wins, since only it is on screen. */
    private CursorSprite pendingCursor;
    private boolean flushScheduled;

    private volatile boolean closed;

    private InputSink input;

    /** The screen's framebuffer, used by the diagnostic dump and nothing else. */
    private Framebuffer framebuffer;

    // Everything below is only ever touched on the JavaFX Application Thread.
    private Stage stage;
    private GraphicsContext graphics;
    private WritableImage image;
    private PixelBuffer<IntBuffer> pixelBuffer;

    /** The pointer sprite as a JavaFX image, rebuilt when a client replaces the cursor. */
    private WritableImage cursorImage;
    private int[] cursorPixels;

    /**
     * The image the framebuffer is presented through. Exposed so a test can read it back and prove
     * the pixels reached JavaFX, which a window on a hidden display cannot show.
     */
    public WritableImage image() {
        return image;
    }
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
        log.info("javafx window {}x{} for display :{}", width, height, displayNumber);
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
    public void stop() {
        closed = true;
        if (!stopped.compareAndSet(false, true) || !toolkitStarted) {
            return;
        }
        try {
            runOnFxThreadAndWait(() -> {
                if (stage != null) {
                    stage.close();
                    stage = null;
                }
                // The FX thread is not a daemon, so without this the JVM would stay alive after
                // the server has finished.
                Platform.exit();
            });
        } catch (RuntimeException e) {
            log.debug("JavaFX had already stopped", e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    /** Runs on the FX thread. */
    private void openWindow(int width, int height, IntBuffer pixels) {
        screenWidth = width;
        screenHeight = height;
        // Keyboard events carry the position of the pointer, which starts wherever the pointer is
        // (the platform does not tell us); the middle of the screen is the least misleading guess.
        pointerX = width / 2;
        pointerY = height / 2;

        // Premultiplied is not a preference: a PixelBuffer only accepts INT_ARGB_PRE or
        // BYTE_BGRA_PRE and rejects plain INT_ARGB outright. It makes no difference to the screen
        // itself, whose pixels are opaque, and the framebuffer array is shared rather than
        // converted either way.
        pixelBuffer = new PixelBuffer<>(width, height, pixels, PixelFormat.getIntArgbPreInstance());
        image = new WritableImage(pixelBuffer);
        Canvas canvas = new Canvas(width, height);
        graphics = canvas.getGraphicsContext2D();
        graphics.drawImage(image, 0, 0);

        Scene scene = new Scene(new StackPane(canvas), width, height, Color.BLACK);
        // The platform would otherwise draw its own arrow over the window, on top of the sprite the
        // server renders from the client's cursor: two pointers, and the host one always wins.
        // Leaving the pointer to the X client is also what makes a text cursor, a resize arrow or
        // an application that hides the pointer while it types show up as the client intended.
        scene.setCursor(Cursor.NONE);
        scene.setOnMouseMoved(this::pointerMoved);
        scene.setOnMouseDragged(this::pointerMoved);
        scene.setOnMousePressed(event -> mouseButton(event, true));
        scene.setOnMouseReleased(event -> mouseButton(event, false));
        scene.setOnScroll(this::scrolled);
        scene.setOnKeyPressed(event -> keyChanged(event.getCode(), true));
        scene.setOnKeyReleased(event -> keyChanged(event.getCode(), false));

        stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.sizeToScene();
        // Scene has no focus property of its own, so focus comes from the window.
        stage.focusedProperty().addListener((observable, wasFocused, focused) -> input.focusChanged(focused));
        stage.setOnCloseRequest(event -> closed = true);
        stage.show();
        // The scene coordinates must stay equal to screen coordinates for the pointer mapping to be
        // right, so the window is placed inside the usable area rather than left to the platform,
        // which will happily put a large window under the menu bar or off the edge of the display.
        placeOnScreen(width, height);
    }

    /** Moves and resizes the window so all of it lands inside the usable part of the display. */
    private void placeOnScreen(int width, int height) {
        // Fully qualified: this class is in the same package as the server's own Screen.
        Rectangle2D usable = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.min(width, usable.getWidth());
        double h = Math.min(height, usable.getHeight());
        stage.setX(usable.getMinX() + Math.max(0, (usable.getWidth() - w) / 2));
        stage.setY(usable.getMinY() + Math.max(0, (usable.getHeight() - h) / 2));
    }

    /** Runs on the FX thread. */
    private void flushDamage() {
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
        graphics.drawImage(image, damage.x, damage.y, damage.width, damage.height,
                damage.x, damage.y, damage.width, damage.height);
        drawCursor(cursor);
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
        graphics.drawImage(cursorImage, sprite.x(), sprite.y());
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

    /** Runs on the JavaFX thread. */
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

    /** Runs on the JavaFX thread; reads the framebuffer array, not the shared IntBuffer. */
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

    private void pointerMoved(MouseEvent event) {
        int x = clamp(event.getX(), screenWidth);
        int y = clamp(event.getY(), screenHeight);
        pointerX = x;
        pointerY = y;
        input.pointerMoved(x, y);
    }

    private void mouseButton(MouseEvent event, boolean pressed) {
        int x = clamp(event.getX(), screenWidth);
        int y = clamp(event.getY(), screenHeight);
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
        int x = clamp(event.getX(), screenWidth);
        int y = clamp(event.getY(), screenHeight);
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

    /** Clamps a scene coordinate to the screen; a drag can leave it by a pixel or two. */
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
        return letterKeycode(value);
    }

    /** The keycode of a US layout letter, or 0 when the character is not one. */
    static int letterKeycode(char letter) {
        return switch (Character.toLowerCase(letter)) {
            case 'q' -> 24;
            case 'w' -> 25;
            case 'e' -> 26;
            case 'r' -> 27;
            case 't' -> 28;
            case 'y' -> 29;
            case 'u' -> 30;
            case 'i' -> 31;
            case 'o' -> 32;
            case 'p' -> 33;
            case 'a' -> 38;
            case 's' -> 39;
            case 'd' -> 40;
            case 'f' -> 41;
            case 'g' -> 42;
            case 'h' -> 43;
            case 'j' -> 44;
            case 'k' -> 45;
            case 'l' -> 46;
            case 'z' -> 52;
            case 'x' -> 53;
            case 'c' -> 54;
            case 'v' -> 55;
            case 'b' -> 56;
            case 'n' -> 57;
            case 'm' -> 58;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------
    // Toolkit
    // ------------------------------------------------------------------

    /**
     * Brings the toolkit up if it is not up already. Starting it is asynchronous, so the wait for
     * a task to run on the FX thread is what actually proves that JavaFX works.
     */
    private static synchronized void startupToolkit() {
        if (toolkitStarted) {
            return;
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // Someone else started the runtime first, which is the state we wanted anyway.
        }
        // The server decides when the process is done, not the last window being closed.
        Platform.setImplicitExit(false);
        runOnFxThreadAndWait(() -> { });
        toolkitStarted = true;
    }

    /** Runs the action on the FX thread and waits for it, rethrowing whatever it threw. */
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
