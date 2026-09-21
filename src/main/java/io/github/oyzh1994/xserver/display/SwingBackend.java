package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.draw.Framebuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;

/**
 * Presents the screen in a Swing window.
 *
 * <p>This is the fallback for machines where JavaFX cannot start - no jars, no natives, no display
 * for the toolkit - so it deliberately uses nothing but the JDK. The window keeps a
 * {@link BufferedImage} of the screen and copies the damaged rectangle into it when the server
 * presents a frame; the panel then repaints exactly that rectangle.
 *
 * <p>{@code present} is called from the server's own thread, so every touch of the image is moved
 * onto the event dispatch thread.
 */
public final class SwingBackend implements DisplayBackend {

    private static final Logger log = LoggerFactory.getLogger(SwingBackend.class);

    private final String title;
    private volatile boolean closed;
    private volatile JFrame frame;
    private volatile ScreenPanel panel;
    /** The pointer sprite that goes with the last presented frame. */
    private volatile CursorSprite cursor;

    private InputSink input;

    // Only touched on the event dispatch thread.
    private int screenWidth;
    private int screenHeight;
    private int pointerX;
    private int pointerY;
    private int heldButtons;

    public SwingBackend(String title) {
        this.title = title;
    }

    @Override
    public String name() {
        return "swing";
    }

    @Override
    public void start(Screen screen, int displayNumber, InputSink input) {
        this.input = input;
        Framebuffer framebuffer = screen.framebuffer();
        runOnEdtAndWait(() -> openWindow(framebuffer));
        log.info("swing window {}x{} for display :{}", framebuffer.width(), framebuffer.height(),
                displayNumber);
    }

    @Override
    public void present(Screen screen, Rectangle damage, CursorSprite sprite) {
        ScreenPanel target = panel;
        if (target == null || closed || damage == null) {
            return;
        }
        // The panel draws the sprite itself, on top of the image it copies out of the framebuffer.
        this.cursor = sprite;
        // The image copy and the repaint both belong to the event dispatch thread; the server
        // calls this from its render loop, so the work is posted rather than done here.
        int x = damage.x;
        int y = damage.y;
        int width = damage.width;
        int height = damage.height;
        if (SwingUtilities.isEventDispatchThread()) {
            target.blit(x, y, width, height);
        } else {
            SwingUtilities.invokeLater(() -> target.blit(x, y, width, height));
        }
    }

    @Override
    public void stop() {
        closed = true;
        JFrame window = frame;
        frame = null;
        panel = null;
        if (window == null) {
            return;
        }
        runOnEdtAndWait(() -> {
            window.setVisible(false);
            window.dispose();
        });
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------

    /** Runs on the event dispatch thread. */
    private void openWindow(Framebuffer framebuffer) {
        screenWidth = framebuffer.width();
        screenHeight = framebuffer.height();
        pointerX = screenWidth / 2;
        pointerY = screenHeight / 2;

        ScreenPanel view = new ScreenPanel(framebuffer);
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                // A panel does not take the focus by itself, and without it no key event arrives.
                view.requestFocusInWindow();
                mouseButton(event, true);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                mouseButton(event, false);
            }
        });
        view.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                pointerMoved(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                pointerMoved(event);
            }
        });
        view.addMouseWheelListener(this::scrolled);
        view.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                keyChanged(event, true);
            }

            @Override
            public void keyReleased(KeyEvent event) {
                keyChanged(event, false);
            }
        });

        JFrame window = new JFrame(title);
        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.setResizable(false);
        window.add(view);
        window.pack();
        window.setLocationRelativeTo(null);
        window.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                input.focusChanged(true);
                view.requestFocusInWindow();
            }

            @Override
            public void windowLostFocus(WindowEvent event) {
                input.focusChanged(false);
            }
        });
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                // The server notices through isClosed() and shuts itself down.
                closed = true;
            }
        });
        panel = view;
        frame = window;
        window.setVisible(true);
        view.requestFocusInWindow();
    }

    /**
     * The window's content: it owns the screen image and forwards whatever the window receives.
     *
     * <p>The framebuffer array is shared rather than copied so that blitting is a single
     * {@code setRGB} over the damaged rectangle.
     */
    private final class ScreenPanel extends JPanel {

        private final int[] pixels;
        private final int stride;
        private final BufferedImage image;

        /** The pointer sprite as an image, rebuilt when a client replaces the cursor. */
        private BufferedImage cursorImage;
        private int[] cursorPixels;

        ScreenPanel(Framebuffer framebuffer) {
            this.pixels = framebuffer.pixels();
            this.stride = framebuffer.stride();
            this.image = new BufferedImage(framebuffer.width(), framebuffer.height(),
                    BufferedImage.TYPE_INT_ARGB);
            blit(0, 0, framebuffer.width(), framebuffer.height());
            setPreferredSize(new Dimension(framebuffer.width(), framebuffer.height()));
            setBackground(Color.BLACK);
            setFocusable(true);
        }

        /** Copies a rectangle out of the framebuffer image. Must run on the EDT. */
        void blit(int x, int y, int width, int height) {
            int x0 = Math.max(0, x);
            int y0 = Math.max(0, y);
            int x1 = Math.min(image.getWidth(), x + width);
            int y1 = Math.min(image.getHeight(), y + height);
            if (x0 >= x1 || y0 >= y1) {
                return;
            }
            image.setRGB(x0, y0, x1 - x0, y1 - y0, pixels, y0 * stride + x0, stride);
            repaint(x0, y0, x1 - x0, y1 - y0);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // The clip is the damage rectangle: drawing image to image at the same coordinates
            // keeps a frame proportional to what actually changed.
            Rectangle clip = g.getClipBounds();
            if (clip == null) {
                return;
            }
            int x0 = Math.max(0, clip.x);
            int y0 = Math.max(0, clip.y);
            int x1 = Math.min(image.getWidth(), clip.x + clip.width);
            int y1 = Math.min(image.getHeight(), clip.y + clip.height);
            if (x0 < x1 && y0 < y1) {
                g.drawImage(image, x0, y0, x1, y1, x0, y0, x1, y1, null);
            }
            // Over the frame, never in it: the sprite is what the framebuffer must not hold.
            drawCursor(g);
        }

        /**
         * Draws the pointer sprite, rebuilding the image only when a client replaced the cursor.
         *
         * <p>The sprite's pixels are cached per cursor, so a moving pointer keeps handing over the
         * same array and only the position changes.
         */
        private void drawCursor(Graphics g) {
            CursorSprite sprite = cursor;
            if (sprite == null) {
                return;
            }
            if (sprite.pixels() != cursorPixels) {
                cursorPixels = sprite.pixels();
                cursorImage = new BufferedImage(sprite.width(), sprite.height(),
                        BufferedImage.TYPE_INT_ARGB);
                cursorImage.setRGB(0, 0, sprite.width(), sprite.height(), sprite.pixels(), 0,
                        sprite.width());
            }
            g.drawImage(cursorImage, sprite.x(), sprite.y(), null);
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
        // AWT numbers its buttons the way X does: 1 left, 2 middle, 3 right.
        int button = event.getButton();
        if (pressed) {
            if (button < MouseEvent.BUTTON1 || button > MouseEvent.BUTTON3) {
                return;
            }
            heldButtons |= 1 << button;
            input.buttonChanged(button, true, x, y);
            return;
        }
        if (button >= MouseEvent.BUTTON1 && button <= MouseEvent.BUTTON3) {
            heldButtons &= ~(1 << button);
            input.buttonChanged(button, false, x, y);
            return;
        }
        // A release reports no button when the gesture started elsewhere; let go of everything
        // rather than leave a button stuck down in the server.
        for (int held = 1; held <= 3; held++) {
            if ((heldButtons & (1 << held)) != 0) {
                heldButtons &= ~(1 << held);
                input.buttonChanged(held, false, x, y);
            }
        }
    }

    private void scrolled(MouseWheelEvent event) {
        int x = clamp(event.getX(), screenWidth);
        int y = clamp(event.getY(), screenHeight);
        pointerX = x;
        pointerY = y;
        int rotation = event.getWheelRotation();
        if (rotation == 0) {
            return;
        }
        // X has no notion of a partial wheel: every notch is a press and release of 4/5.
        wheel(rotation < 0 ? 4 : 5, x, y);
    }

    private void wheel(int button, int x, int y) {
        input.buttonChanged(button, true, x, y);
        input.buttonChanged(button, false, x, y);
    }

    private void keyChanged(KeyEvent event, boolean pressed) {
        int keycode = xKeycode(event);
        if (keycode == 0) {
            // No mapping means no keycode: reporting one would invent a key the client never has.
            return;
        }
        input.keyChanged(keycode, pressed, pointerX, pointerY);
    }

    /** Clamps a component coordinate to the screen; a drag can leave it by a pixel or two. */
    private static int clamp(double value, int limit) {
        return Math.max(0, Math.min(limit - 1, (int) value));
    }

    // ------------------------------------------------------------------
    // Key codes
    // ------------------------------------------------------------------

    /**
     * The X keycode an AWT key event produces, or 0 when it has none.
     *
     * <p>The values are the standard X11 keycodes for a US layout, which is the mapping the
     * server's {@code Keymap} describes. Letters are not contiguous there - {@code a} is 38 and
     * {@code z} is 52, with the punctuation keys of the home row in between - so they are looked
     * up by character rather than by their position in the alphabet.
     */
    static int xKeycode(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return letterKeycode((char) ('a' + code - KeyEvent.VK_A));
        }
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
            int digit = code - KeyEvent.VK_0;
            return digit == 0 ? 19 : 9 + digit;
        }
        return switch (code) {
            case KeyEvent.VK_ESCAPE -> 9;
            case KeyEvent.VK_MINUS -> 20;
            case KeyEvent.VK_EQUALS -> 21;
            case KeyEvent.VK_BACK_SPACE -> 22;
            case KeyEvent.VK_TAB -> 23;
            case KeyEvent.VK_OPEN_BRACKET -> 34;
            case KeyEvent.VK_CLOSE_BRACKET -> 35;
            case KeyEvent.VK_ENTER -> 36;
            // AWT knows which side a modifier is on, so both copies are reported as X has them.
            case KeyEvent.VK_CONTROL -> isRight(event) ? 105 : 37;
            case KeyEvent.VK_SEMICOLON -> 47;
            case KeyEvent.VK_QUOTE -> 48;
            case KeyEvent.VK_BACK_QUOTE -> 49;
            case KeyEvent.VK_SHIFT -> isRight(event) ? 62 : 50;
            case KeyEvent.VK_BACK_SLASH -> 51;
            case KeyEvent.VK_COMMA -> 59;
            case KeyEvent.VK_PERIOD -> 60;
            case KeyEvent.VK_SLASH -> 61;
            case KeyEvent.VK_MULTIPLY -> 63;
            case KeyEvent.VK_ALT -> isRight(event) ? 108 : 64;
            case KeyEvent.VK_SPACE -> 65;
            case KeyEvent.VK_CAPS_LOCK -> 66;
            case KeyEvent.VK_F1 -> 67;
            case KeyEvent.VK_F2 -> 68;
            case KeyEvent.VK_F3 -> 69;
            case KeyEvent.VK_F4 -> 70;
            case KeyEvent.VK_F5 -> 71;
            case KeyEvent.VK_F6 -> 72;
            case KeyEvent.VK_F7 -> 73;
            case KeyEvent.VK_F8 -> 74;
            case KeyEvent.VK_F9 -> 75;
            case KeyEvent.VK_F10 -> 76;
            case KeyEvent.VK_NUM_LOCK -> 77;
            case KeyEvent.VK_SCROLL_LOCK -> 78;
            case KeyEvent.VK_NUMPAD7 -> 79;
            case KeyEvent.VK_NUMPAD8 -> 80;
            case KeyEvent.VK_NUMPAD9 -> 81;
            case KeyEvent.VK_SUBTRACT -> 82;
            case KeyEvent.VK_NUMPAD4 -> 83;
            case KeyEvent.VK_NUMPAD5 -> 84;
            case KeyEvent.VK_NUMPAD6 -> 85;
            case KeyEvent.VK_ADD -> 86;
            case KeyEvent.VK_NUMPAD1 -> 87;
            case KeyEvent.VK_NUMPAD2 -> 88;
            case KeyEvent.VK_NUMPAD3 -> 89;
            case KeyEvent.VK_NUMPAD0 -> 90;
            case KeyEvent.VK_DECIMAL -> 91;
            case KeyEvent.VK_F11 -> 95;
            case KeyEvent.VK_F12 -> 96;
            case KeyEvent.VK_DIVIDE -> 106;
            case KeyEvent.VK_PRINTSCREEN -> 107;
            case KeyEvent.VK_ALT_GRAPH -> 108;
            case KeyEvent.VK_HOME -> 110;
            case KeyEvent.VK_UP -> 111;
            case KeyEvent.VK_PAGE_UP -> 112;
            case KeyEvent.VK_LEFT -> 113;
            case KeyEvent.VK_RIGHT -> 114;
            case KeyEvent.VK_END -> 115;
            case KeyEvent.VK_DOWN -> 116;
            case KeyEvent.VK_PAGE_DOWN -> 117;
            case KeyEvent.VK_INSERT -> 118;
            case KeyEvent.VK_DELETE -> 119;
            case KeyEvent.VK_PAUSE -> 127;
            case KeyEvent.VK_WINDOWS -> 133;
            case KeyEvent.VK_META -> 134;
            case KeyEvent.VK_CONTEXT_MENU -> 135;
            default -> 0;
        };
    }

    private static boolean isRight(KeyEvent event) {
        return event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT;
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
    // Threading
    // ------------------------------------------------------------------

    /** Runs the action on the event dispatch thread and waits for it. */
    private static void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the Swing thread", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("the Swing window could not be opened", cause);
        }
    }
}
