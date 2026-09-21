package io.github.oyzh1994.xserver.input;

import com.github.moaxcp.x11.protocol.XEvent;
import com.github.moaxcp.x11.protocol.xproto.ButtonPressEvent;
import com.github.moaxcp.x11.protocol.xproto.ButtonReleaseEvent;
import com.github.moaxcp.x11.protocol.xproto.KeyPressEvent;
import com.github.moaxcp.x11.protocol.xproto.KeyReleaseEvent;
import com.github.moaxcp.x11.protocol.xproto.MotionNotifyEvent;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.event.EventDispatcher.EventFactory;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.extension.ShapeExtension;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XWindow;

import java.awt.Rectangle;
import java.util.List;

/**
 * Pointer, keyboard and focus state, and the input half of {@link DisplayBackend.InputSink}.
 *
 * <p>Input arrives from the display backend on the toolkit's thread. Everything here runs under the
 * server lock so the window tree, the pointer position and the event stream stay consistent with
 * request processing.
 */
public final class InputState implements DisplayBackend.InputSink {

    /** X button state occupies bits 8..12 of the event state field. */
    private static final int BUTTON_MASK_SHIFT = 8;

    /** Keycodes that only apply while held; everything else with a modifier bit latches. */
    private static final int[] MOMENTARY_KEYCODES = {
            50, 62,     // Shift
            37, 105,    // Control
            64, 108,    // Alt (Mod1)
            133, 134,   // Super (Mod4)
    };

    private final XServer server;

    private int pointerX;
    private int pointerY;
    private XWindow pointerWindow;

    private int buttons;
    private int modifiers;
    private int latched;

    private XWindow inputFocus;
    private int focusRevertTo = 1;   // RevertToPointerRoot
    private boolean focusSet;

    private Grab pointerGrab;
    private Grab keyboardGrab;

    /** Pixels hidden behind the cursor sprite, so it can be removed again. */
    private int[] cursorBackup;
    private Rectangle cursorBackupRect;
    private int cursorDrawX = Integer.MIN_VALUE;
    private int cursorDrawY = Integer.MIN_VALUE;
    private XCursor cursorDrawn;

    public InputState(XServer server) {
        this.server = server;
        this.inputFocus = server.rootWindow();
    }

    /** An active pointer or keyboard grab. */
    public record Grab(ClientConnection client, XWindow window, boolean ownerEvents, int eventMask,
                       boolean keyboard) {
    }

    // ------------------------------------------------------------------
    // State accessors
    // ------------------------------------------------------------------

    public int pointerX() {
        return pointerX;
    }

    public int pointerY() {
        return pointerY;
    }

    public XWindow pointerWindow() {
        return pointerWindow;
    }

    /**
     * The deepest mapped window containing the pointer, or the root as a fallback.
     *
     * <p>Descent is done here rather than with {@code XWindow.windowAt} because a window's input
     * shape can make a corner transparent to the pointer; a point over a shaped out region belongs
     * to whatever is behind it, which is the whole point of the extension.
     */
    public XWindow windowAtPointer() {
        XWindow window = server.rootWindow();
        while (true) {
            XWindow child = null;
            List<XWindow> children = window.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                XWindow candidate = children.get(i);
                if (candidate.isMapped()
                        && candidate.absoluteBoundsWithBorder().contains(pointerX, pointerY)
                        && ShapeExtension.acceptsPointer(server, candidate, pointerX, pointerY)) {
                    child = candidate;
                    break;
                }
            }
            if (child == null) {
                return window;
            }
            window = child;
        }
    }

    public XWindow inputFocus() {
        return inputFocus;
    }

    public void setInputFocus(XWindow window, int revertTo) {
        this.inputFocus = window == null ? server.rootWindow() : window;
        this.focusRevertTo = revertTo;
        this.focusSet = true;
    }

    public int focusRevertTo() {
        return focusRevertTo;
    }

    public boolean isFocusSet() {
        return focusSet;
    }

    /** Modifier and button state packed the way the X event {@code state} field expects. */
    public int pointerState() {
        return modifiers | (buttons << BUTTON_MASK_SHIFT);
    }

    public int modifierState() {
        return modifiers;
    }

    public Grab pointerGrab() {
        return pointerGrab;
    }

    public Grab keyboardGrab() {
        return keyboardGrab;
    }

    public void setPointerGrab(Grab grab) {
        this.pointerGrab = grab;
    }

    public void setKeyboardGrab(Grab grab) {
        this.keyboardGrab = grab;
    }

    public void clientDisconnected(ClientConnection client) {
        releaseGrabs(client);
    }

    /** Releases any grab held by a client, freeing the keyboard or pointer for others. */
    public void releaseGrabs(ClientConnection client) {
        if (pointerGrab != null && pointerGrab.client() == client) {
            pointerGrab = null;
        }
        if (keyboardGrab != null && keyboardGrab.client() == client) {
            keyboardGrab = null;
        }
    }

    /** Moves the pointer without generating events, used by {@code WarpPointer}. */
    public void warpPointer(int x, int y) {
        movePointer(x, y, false);
    }

    /**
     * Sounds the bell. {@code percent} is the volume as a client asked for it; -1 means "use the
     * server default". The host bell is used when one is available.
     */
    public void ringBell(int percent) {
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Throwable e) {
            // Headless or no audio device: a silent bell is an acceptable outcome.
        }
    }

    // ------------------------------------------------------------------
    // InputSink
    // ------------------------------------------------------------------

    @Override
    public void pointerMoved(int x, int y) {
        server.lockServer();
        try {
            movePointer(x, y, true);
        } finally {
            server.unlockServer();
        }
    }

    @Override
    public void buttonChanged(int button, boolean pressed, int x, int y) {
        if (button < 1 || button > 12) {
            return;
        }
        server.lockServer();
        try {
            movePointer(x, y, false);
            int mask = 1 << (button - 1);
            if (pressed) {
                buttons |= mask;
            } else {
                buttons &= ~mask;
            }
            deliverButton(button, pressed, mask);
        } finally {
            server.unlockServer();
        }
    }

    @Override
    public void keyChanged(int keycode, boolean pressed, int x, int y) {
        if (keycode < (Keymap.MIN_KEYCODE & 0xFF) || keycode > (Keymap.MAX_KEYCODE & 0xFF)) {
            return;
        }
        server.lockServer();
        try {
            movePointer(x, y, false);
            updateModifiers(keycode, pressed);
            deliverKey(keycode, pressed);
        } finally {
            server.unlockServer();
        }
    }

    @Override
    public void focusChanged(boolean focused) {
        // Losing the host window's focus does not change the X input focus: clients keep theirs.
    }

    // ------------------------------------------------------------------
    // Pointer
    // ------------------------------------------------------------------

    private void movePointer(int x, int y, boolean generateMotion) {
        int clampedX = Math.max(0, Math.min(x, server.screen().width() - 1));
        int clampedY = Math.max(0, Math.min(y, server.screen().height() - 1));
        boolean moved = clampedX != pointerX || clampedY != pointerY;
        pointerX = clampedX;
        pointerY = clampedY;

        XWindow window = windowAtPointer();
        if (window != pointerWindow) {
            XWindow previous = pointerWindow;
            pointerWindow = window;
            if (pointerGrab == null) {
                server.events().deliverCrossing(previous, window, pointerX, pointerY, pointerX,
                        pointerY);
            }
        }
        if (generateMotion && moved) {
            deliverMotion();
        }
    }

    private void deliverMotion() {
        Grab grab = pointerGrab;
        if (grab != null && !grab.ownerEvents()) {
            deliverToGrab(grab, EventMasks.POINTER_MOTION, motionFactory());
            return;
        }
        server.events().deliverPropagating(pointerWindow, EventMasks.POINTER_MOTION, pointerX,
                pointerY, motionFactory());
    }

    private EventFactory motionFactory() {
        return (client, w, child, x, y) -> MotionNotifyEvent.builder()
                .firstEventOffset((byte) 0)
                .sentEvent(false)
                .sequenceNumber(client.sequenceNumber())
                .detail((byte) 0)                       // Normal
                .time(server.events().time())
                .root(server.rootWindow().id())
                .event(w.id())
                .child(child == null ? 0 : child.id())
                .rootX((short) pointerX)
                .rootY((short) pointerY)
                .eventX((short) x)
                .eventY((short) y)
                .state((short) pointerState())
                .sameScreen(true)
                .build();
    }

    private void deliverButton(int button, boolean pressed, int mask) {
        int eventMask = pressed ? EventMasks.BUTTON_PRESS : EventMasks.BUTTON_RELEASE;
        // The state field reports the buttons held *before* this event.
        short stateBefore = (short) (pointerState() & ~(mask << BUTTON_MASK_SHIFT));
        short stateAfter = (short) (pointerState() | (mask << BUTTON_MASK_SHIFT));
        EventFactory factory = (client, w, child, x, y) -> {
            short state = pressed ? stateBefore : stateAfter;
            int childId = child == null ? 0 : child.id();
            int time = server.events().time();
            XEvent event;
            if (pressed) {
                event = ButtonPressEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(client.sequenceNumber())
                        .detail((byte) button)
                        .time(time)
                        .root(server.rootWindow().id())
                        .event(w.id())
                        .child(childId)
                        .rootX((short) pointerX)
                        .rootY((short) pointerY)
                        .eventX((short) x)
                        .eventY((short) y)
                        .state(state)
                        .sameScreen(true)
                        .build();
            } else {
                event = ButtonReleaseEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(client.sequenceNumber())
                        .detail((byte) button)
                        .time(time)
                        .root(server.rootWindow().id())
                        .event(w.id())
                        .child(childId)
                        .rootX((short) pointerX)
                        .rootY((short) pointerY)
                        .eventX((short) x)
                        .eventY((short) y)
                        .state(state)
                        .sameScreen(true)
                        .build();
            }
            return event;
        };
        Grab grab = pointerGrab;
        if (grab != null && !grab.ownerEvents()) {
            deliverToGrab(grab, eventMask, factory);
            return;
        }
        server.events().deliverPropagating(pointerWindow, eventMask, pointerX, pointerY, factory);
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    private void updateModifiers(int keycode, boolean pressed) {
        int bit = Keymap.modifierMaskForKeycode(keycode);
        if (bit == 0) {
            return;
        }
        if (isMomentary(keycode)) {
            modifiers = pressed ? modifiers | bit : modifiers & ~bit;
            return;
        }
        // Caps Lock, Num Lock and Scroll Lock toggle and stay set after the key is released.
        if (pressed) {
            latched ^= bit;
            modifiers = (modifiers & ~bit) | (latched & bit);
        }
    }

    private static boolean isMomentary(int keycode) {
        for (int candidate : MOMENTARY_KEYCODES) {
            if (candidate == keycode) {
                return true;
            }
        }
        return false;
    }

    private void deliverKey(int keycode, boolean pressed) {
        int eventMask = pressed ? EventMasks.KEY_PRESS : EventMasks.KEY_RELEASE;
        EventFactory factory = (client, w, child, x, y) -> {
            int childId = child == null ? 0 : child.id();
            int time = server.events().time();
            short state = (short) pointerState();
            if (pressed) {
                return KeyPressEvent.builder()
                        .firstEventOffset((byte) 0)
                        .sentEvent(false)
                        .sequenceNumber(client.sequenceNumber())
                        .detail((byte) keycode)
                        .time(time)
                        .root(server.rootWindow().id())
                        .event(w.id())
                        .child(childId)
                        .rootX((short) pointerX)
                        .rootY((short) pointerY)
                        .eventX((short) x)
                        .eventY((short) y)
                        .state(state)
                        .sameScreen(true)
                        .build();
            }
            return KeyReleaseEvent.builder()
                    .firstEventOffset((byte) 0)
                    .sentEvent(false)
                    .sequenceNumber(client.sequenceNumber())
                    .detail((byte) keycode)
                    .time(time)
                    .root(server.rootWindow().id())
                    .event(w.id())
                    .child(childId)
                    .rootX((short) pointerX)
                    .rootY((short) pointerY)
                    .eventX((short) x)
                    .eventY((short) y)
                    .state(state)
                    .sameScreen(true)
                    .build();
        };
        Grab grab = keyboardGrab;
        if (grab != null && !grab.ownerEvents()) {
            deliverToGrab(grab, eventMask, factory);
            return;
        }
        XWindow target = inputFocus == null ? server.rootWindow() : inputFocus;
        server.events().deliverPropagating(target, eventMask, pointerX, pointerY, factory);
    }

    /** Delivers straight to a grabbing client's window, bypassing normal window selection. */
    private void deliverToGrab(Grab grab, int maskBit, EventFactory factory) {
        if ((grab.eventMask() & maskBit) == 0) {
            return;
        }
        XWindow window = grab.window();
        int x = pointerX - window.absoluteX();
        int y = pointerY - window.absoluteY();
        server.events().send(grab.client(), factory.create(grab.client(), window, null, x, y));
    }

    // ------------------------------------------------------------------
    // Cursor sprite
    // ------------------------------------------------------------------

    /**
     * Draws or removes the cursor sprite. Called from the display loop just before the framebuffer
     * is presented, so the sprite never becomes part of what {@code GetImage} reads back.
     */
    public void tickCursor(Screen screen) {
        server.lockServer();
        try {
            XCursor cursor = cursorFor();
            if (pointerX == cursorDrawX && pointerY == cursorDrawY && cursor == cursorDrawn) {
                return;
            }
            restoreCursor(screen);
            drawCursor(screen, cursor);
        } finally {
            server.unlockServer();
        }
    }

    private XCursor cursorFor() {
        for (XWindow window = pointerWindow; window != null; window = window.parent()) {
            XCursor cursor = window.cursor();
            if (cursor != null) {
                return cursor;
            }
        }
        return server.rootCursor();
    }

    private void restoreCursor(Screen screen) {
        if (cursorBackup == null || cursorBackupRect == null) {
            return;
        }
        Framebuffer framebuffer = screen.framebuffer();
        for (int row = 0; row < cursorBackupRect.height; row++) {
            for (int col = 0; col < cursorBackupRect.width; col++) {
                framebuffer.setPixel(cursorBackupRect.x + col, cursorBackupRect.y + row,
                        cursorBackup[row * cursorBackupRect.width + col]);
            }
        }
        cursorBackup = null;
        cursorBackupRect = null;
    }

    private void drawCursor(Screen screen, XCursor cursor) {
        if (cursor == null) {
            return;
        }
        Framebuffer framebuffer = screen.framebuffer();
        int originX = pointerX - cursor.hotspotX();
        int originY = pointerY - cursor.hotspotY();
        int width = cursor.width();
        int height = cursor.height();
        cursorBackup = new int[width * height];
        cursorBackupRect = new Rectangle(originX, originY, width, height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int x = originX + col;
                int y = originY + row;
                if (!framebuffer.contains(x, y)) {
                    continue;
                }
                cursorBackup[row * width + col] = framebuffer.pixel(x, y);
                int argb = cursor.pixel(col, row);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                framebuffer.setPixel(x, y, alpha == 0xFF ? argb
                        : blend(argb, framebuffer.pixel(x, y)));
            }
        }
        cursorDrawX = pointerX;
        cursorDrawY = pointerY;
        cursorDrawn = cursor;
    }

    private static int blend(int src, int dst) {
        int sa = (src >>> 24) & 0xFF;
        int inv = 255 - sa;
        int r = (((src >> 16) & 0xFF) * sa + ((dst >> 16) & 0xFF) * inv) / 255;
        int g = (((src >> 8) & 0xFF) * sa + ((dst >> 8) & 0xFF) * inv) / 255;
        int b = ((src & 0xFF) * sa + (dst & 0xFF) * inv) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
