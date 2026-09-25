package io.github.oyzh1994.xserver.input;

import com.github.moaxcp.x11.protocol.XEvent;
import com.github.moaxcp.x11.protocol.xproto.ButtonPressEvent;
import com.github.moaxcp.x11.protocol.xproto.ButtonReleaseEvent;
import com.github.moaxcp.x11.protocol.xproto.KeyPressEvent;
import com.github.moaxcp.x11.protocol.xproto.KeyReleaseEvent;
import com.github.moaxcp.x11.protocol.xproto.MotionNotifyEvent;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.event.EventDispatcher;
import io.github.oyzh1994.xserver.event.EventDispatcher.EventFactory;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.extension.ShapeExtension;
import io.github.oyzh1994.xserver.handler.InputHandlers;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Pointer, keyboard and focus state, and the input half of {@link DisplayBackend.InputSink}.
 *
 * <p>Input arrives from the display backend on the toolkit's thread. Everything here runs under the
 * server lock so the window tree, the pointer position and the event stream stay consistent with
 * request processing.
 */
public final class InputState implements DisplayBackend.InputSink {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InputState.class);

    /** X button state occupies bits 8..12 of the event state field. */
    private static final int BUTTON_MASK_SHIFT = 8;

    /** {@code GrabButton}/{@code GrabKey} modifiers meaning "any": the protocol's own spelling. */
    private static final int ANY_MODIFIER = 0x8000;

    /** Keycodes that only apply while held; everything else with a modifier bit latches. */
    private static final int[] MOMENTARY_KEYCODES = {
            50, 62,     // Shift
            37, 105,    // Control
            64, 108,    // Alt (Mod1)
            133, 134,   // Super (Mod4)
    };

    private final XServer server;
    /**
     * The screen this input state belongs to. Pointer position, focus, grabs and the sprite are all
     * per screen: two clients drawing on two screens have two pointers, and neither can move the
     * other's.
     */
    private final Screen screen;

    private int pointerX;
    private int pointerY;
    private XWindow pointerWindow;
    /**
     * The window the client was last <em>told</em> the pointer is in, by a crossing event. Not the
     * same as {@link #pointerWindow}: that one is recomputed by every button or key event too, with
     * no crossing to report it, so it runs ahead of what the client knows. Crossings have to be
     * reported from here, or a client is handed a LeaveNotify for a window it was never told the
     * pointer entered.
     */
    private XWindow announcedWindow;
    /**
     * False until something has said where the pointer is. A window that opens under a stationary
     * pointer gets no mouse event until that pointer moves, and until then the server has no
     * position to draw a sprite at - drawing one anyway puts a second pointer in the window's
     * corner, next to the platform's own arrow.
     */
    private boolean pointerKnown;

    private int buttons;
    private int modifiers;
    private int latched;

    /**
     * How many motion events this screen remembers. Xorg's own {@code MOTION_HISTORY_SIZE} is 256,
     * and the number is a bound on memory per screen rather than a protocol requirement: a client
     * that asks for more than is kept is told what there is.
     */
    private static final int MOTION_HISTORY = 256;

    /**
     * The pointer's own history, oldest first, for {@code GetMotionEvents}.
     *
     * <p>It holds what the pointer <em>did</em>, not what was delivered: a motion reached through a
     * grab, or while the pointer was over another client's window, is part of the history all the
     * same. The window each one is filed under is the one the pointer was in at the time, which is
     * what the request asks about - and the times are the event clock, so they can be compared with
     * the timestamps a client has already seen.
     */
    private final java.util.ArrayDeque<Motion> motionHistory = new java.util.ArrayDeque<>();

    /** One remembered motion: when, where, and the window the pointer was in. */
    public record Motion(int time, int x, int y, XWindow window) {
    }

    /**
     * How long a key is held before it starts repeating, and how long between repeats, in
     * milliseconds: X's own defaults, which is what a client sees on a stock server (`xset q` reports
     * this delay and a rate of 25 per second).
     */
    private static final int DEFAULT_REPEAT_DELAY_MILLIS = 660;
    private static final int DEFAULT_REPEAT_INTERVAL_MILLIS = 40;

    /**
     * The keys that are held down and when each is due to repeat next.
     *
     * <p>The server generates the repeats itself, as a real one does: a toolkit that types a run of
     * characters from a held key depends on it - `xterm` and a Swing text field both print one
     * character per press and nothing else, so without this a held backspace deletes one character.
     */
    private final java.util.Map<Integer, Long> repeatDue = new java.util.HashMap<>();

    /**
     * Which keycodes may repeat. Every key starts out repeating except the modifiers, and a client
     * changes it with {@code ChangeKeyboardControl}'s {@code AutoRepeatMode} or with XKB's
     * {@code SetControls}, whose {@code perKeyRepeat} is the same bitmap.
     *
     * <p><b>The modifiers repeat for nobody by default, and the bitmap is where that lives</b>, not a
     * rule applied at press time: measured against Xvfb, whose own {@code auto_repeats} list is
     * exactly the keys the modifier map names - {@code Shift_L}/{@code Shift_R}, both Controls, both
     * Alts, both Supers, Caps Lock, Num Lock. A client that puts a modifier back in the bitmap gets it
     * to repeat, which is what a reference server does too; the default is what a stock keymap's
     * compatibility section asks for.
     */
    private final boolean[] autoRepeats = new boolean[256];

    /**
     * Whether the keyboard repeats at all - XKB's {@code XkbRepeatKeysMask}, the one control bit this
     * server honours. Core {@code ChangeKeyboardControl} has no request for it; XKB's
     * {@code SetControls} does, and a client that turns it off means "no key repeats, whatever the
     * bitmap says".
     */
    private boolean repeatKeysEnabled = true;

    private int repeatDelayMillis = DEFAULT_REPEAT_DELAY_MILLIS;
    private int repeatIntervalMillis = DEFAULT_REPEAT_INTERVAL_MILLIS;

    /** Focus revert modes, numbered as {@code SetInputFocus} numbers them. */
    public static final int REVERT_TO_NONE = 0;
    public static final int REVERT_TO_POINTER_ROOT = 1;
    public static final int REVERT_TO_PARENT = 2;

    private XWindow inputFocus;
    /** True when {@link #inputFocus} - always the root window then - means PointerRoot. */
    private boolean focusIsPointerRoot;
    private int focusRevertTo = 1;   // RevertToPointerRoot
    private boolean focusSet;

    private Grab pointerGrab;
    private Grab keyboardGrab;

    /**
     * The button or key a <em>passive</em> grab is being held for, so that its release ends the grab;
     * 0 when the grab is one a client took for itself with {@code GrabPointer}/{@code GrabKeyboard}.
     *
     * <p>A passive grab becomes active when the event it was armed for happens and stops when that
     * event ends - a button grab holds the pointer from the press to the release of the button it
     * matched, and a key grab from the press to the release of the key. That is the whole of its
     * lifetime, and it is the difference between it and an explicit grab, which lasts until the client
     * says otherwise.
     */
    private int passiveGrabDetail;

    /** The modifiers the passive grab matched with, for the crossing pair's state field. */
    private int passiveGrabModifiers;

    /**
     * X11's implicit grab: the window the last button press landed on.
     *
     * <p>While any button is down, every pointer event is reported against that window even once the
     * pointer has moved off it. A client relies on this to drag: the window it is moving follows the
     * pointer, so the pointer is regularly outside it for a moment, and a client that stops being
     * told about motion in the middle of a drag never sees the drag end - it stays in its "moving"
     * state and quietly ignores every later click.
     */
    private XWindow implicitGrabWindow;

    /** The current cursor's pixels, copied once per cursor because the backend asks every frame. */
    private int[] cursorPixels;
    private XCursor cursorPixelsFor;

    public InputState(XServer server) {
        this(server, server.screen());
    }

    public InputState(XServer server, Screen screen) {
        this.server = server;
        this.screen = screen;
        this.inputFocus = screen.root();
        // Every key repeats except the modifiers, which is what a stock server's per-key repeat
        // bitmap holds - see the field. A keycode outside the keyboard's own range repeats for
        // nobody either, so the bitmap a client reads back has no bits set below 8.
        for (int keycode = Keymap.MIN_KEYCODE & 0xFF; keycode <= Keymap.MAX_KEYCODE; keycode++) {
            autoRepeats[keycode] = Keymap.modifierMaskForKeycode(keycode) == 0;
        }
    }

    /** The screen whose pointer, focus and grabs this is. */
    public Screen screen() {
        return screen;
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
        XWindow window = screen.root();
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

    /**
     * Whether the focus is {@code PointerRoot} rather than the window {@link #inputFocus} names.
     *
     * <p>The two are different states and a client can tell them apart: {@code GetInputFocus} answers
     * {@code PointerRoot} for one and the root window's own id for the other, and a key press goes to
     * whatever the pointer is in for one and to the root window for the other. The root window is
     * how both are stored here because it is a real window object; this flag is what separates them.
     */
    public boolean focusIsPointerRoot() {
        return inputFocus != null && focusIsPointerRoot;
    }

    /** Sets the focus to a window, to {@code None} when the window is null, or to PointerRoot when it
     * is the root - the reading every caller inside this class wants. A client's own request says
     * which of the two it meant; see the three argument form. */
    public void setInputFocus(XWindow window, int revertTo) {
        setInputFocus(window, revertTo, window != null && window.isRoot());
    }

    /**
     * The same, with the two states that are not an ordinary window spelled out.
     *
     * @param window the window, or null for None, or the root for either of the states below
     * @param pointerRoot true when the focus follows the pointer rather than naming that window
     */
    public void setInputFocus(XWindow window, int revertTo, boolean pointerRoot) {
        XWindow previous = inputFocus;
        boolean previousFollows = focusIsPointerRoot();
        // The three states are kept apart, because a client can tell them apart: null is None (the
        // keyboard goes nowhere), the root with this flag is PointerRoot (it goes to whatever the
        // pointer is in), the root without it is the root window, and anything else is a window.
        // Folding any two of them together would report a focus the client did not ask for.
        this.inputFocus = window;
        this.focusIsPointerRoot = pointerRoot && window != null && window.isRoot();
        this.focusRevertTo = revertTo;
        this.focusSet = true;
        if (log.isDebugEnabled()) {
            log.debug("focus set to {}, was {}, reverting to {}",
                    describeFocus(inputFocus, focusIsPointerRoot), describeFocus(previous,
                            previousFollows), revertTo);
        }
        // A client that asks for the focus is waiting to be told it got it, and this is the only
        // thing that tells it. While a keyboard grab is held the change is reported as made *by*
        // the grab, which is what a toolkit reads the mode for: measured with tools/FocusProbe, a
        // reference server reports every focus change made inside a grab as NotifyWhileGrabbed.
        server.events().deliverFocusChange(previous, previousFollows, inputFocus,
                focusIsPointerRoot(),
                keyboardGrab == null ? EventDispatcher.NOTIFY_MODE_NORMAL
                        : EventDispatcher.NOTIFY_MODE_WHILE_GRABBED,
                false);
    }

    /** A focus state's name for a log line: the two that are not a window say so. */
    private static String describeFocus(XWindow window, boolean pointerRoot) {
        if (window == null) {
            return "nowhere";
        }
        if (pointerRoot) {
            return "the pointer";
        }
        return "0x" + Integer.toHexString(window.id()) + (window.isRoot() ? " (the root window)" : "");
    }

    private static String describe(XWindow window) {
        return window == null ? "nowhere" : "0x" + Integer.toHexString(window.id());
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

    /**
     * Takes or releases the keyboard grab, reporting the focus it moves the way a reference server
     * does.
     *
     * <p>A keyboard grab is not only a change of where keys are delivered: the client that took it
     * is told the focus is on the window it grabbed, and told again when the grab ends. Both are
     * events, and this is the only place they can be raised, because this is the only thing that
     * changes a grab.
     */
    public void setKeyboardGrab(Grab grab) {
        Grab previous = this.keyboardGrab;
        this.keyboardGrab = grab;
        boolean grabbed = previous != null;
        boolean grabbing = grab != null;
        boolean changed = !grabbed || !grabbing || previous.window() != grab.window();
        if (grabbed && changed) {
            reportKeyboardGrabEnd(previous);
        }
        if (grabbing && changed) {
            reportKeyboardGrabStart(grab);
        }
    }

    /**
     * Reports the focus the grab takes: the window the keyboard was on hears that it left, and the
     * grab window hears that it arrived, both with mode {@code NotifyGrab}.
     *
     * <p>Two details are measured rather than derived from the prose: the pair is sent even when the
     * grab window already had the focus (a client grabs to be told it holds the keyboard, and a
     * server that treats "it is already there" as nothing to report leaves it waiting), and a grab
     * taken while the focus is on nothing at all reports nothing - there is no window to be told it
     * lost anything, and the reference does not tell the grab window either.
     */
    private void reportKeyboardGrabStart(Grab grab) {
        if (inputFocus == null) {
            if (log.isDebugEnabled()) {
                log.debug("keyboard grabbed by 0x{} with the focus nowhere: nothing to report",
                        Integer.toHexString(grab.window().id()));
            }
            return;
        }
        server.events().deliverFocusChange(inputFocus, focusIsPointerRoot(), grab.window(),
                grab.window().isRoot(), EventDispatcher.NOTIFY_MODE_GRAB, true);
    }

    /**
     * Reports the focus the grab gives back: from the grab window to wherever the focus is now, with
     * mode {@code NotifyUngrab}.
     *
     * <p>"Wherever the focus is now" is not the focus the grab took - a client that calls
     * {@code SetInputFocus} while it holds the keyboard has moved it, and the reference reports the
     * way back from the grab window to the new window rather than to the old one.
     */
    private void reportKeyboardGrabEnd(Grab grab) {
        server.events().deliverFocusChange(grab.window(), grab.window().isRoot(), inputFocus,
                focusIsPointerRoot(), EventDispatcher.NOTIFY_MODE_UNGRAB, true);
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
        if (implicitGrabWindow != null && implicitGrabWindow.owner() == client) {
            implicitGrabWindow = null;
        }
    }

    /** The window the implicit grab is holding the pointer on, or null when there is none. */
    public XWindow implicitGrabWindow() {
        return implicitGrabWindow;
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
        server.screenSaver().noteInput();
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
        // The idle countdown is about the user's own input, and a button is input as much as a motion
        // or a key is: a machine whose pointer is still but whose buttons are being pressed is not
        // idle, and MIT-SCREEN-SAVER's countdown has to agree.
        server.screenSaver().noteInput();
        server.lockServer();
        try {
            movePointer(x, y, false);
            int mask = 1 << (button - 1);
            if (pressed) {
                buttons |= mask;
            } else {
                buttons &= ~mask;
            }
            if (log.isDebugEnabled()) {
                // Which window a click is *reported against* is otherwise invisible from the server
                // side, and it is the first thing to check when a client behaves as if it was never
                // clicked: while a button is down that is the window the press landed on, not the one
                // under the pointer, and an active grab overrides both.
                XWindow target = eventTarget();
                log.debug("button {} {} at ({},{}) -> {}{}", button, pressed ? "down" : "up", x, y,
                        target == null ? "no window" : "0x" + Integer.toHexString(target.id()),
                        pointerGrab != null
                                ? " (grab on 0x" + Integer.toHexString(pointerGrab.window().id())
                                        + ", ownerEvents=" + pointerGrab.ownerEvents() + ")"
                                : "");
            }
            if (pressed) {
                // A passive grab is matched before the event is delivered: if one fires, the press
                // belongs to the client that armed it, wherever it landed.
                // The *modifiers*, not the whole pointer state: the state also carries the button
                // that was just pressed, and a grab armed for Shift must not be looking at that.
                InputHandlers.PassiveGrab passive = matchingPassiveGrab(true, pointerWindow, button,
                        modifierState());
                if (passive != null) {
                    activatePassiveGrab(passive, button, modifierState());
                }
            }
            deliverButton(button, pressed, mask);
            if (!pressed && passiveGrabDetail == button) {
                // The release has been delivered through the grab; the grab itself ends here.
                endPassiveGrab();
            }
        } finally {
            server.unlockServer();
        }
    }

    @Override
    /**
     * A character the host platform composed for the user: what an input method produced, or a dead
     * key's accent.
     *
     * <p>This is the half of typing that a keycode cannot carry. A keycode says which key was
     * pressed, and a client turns that into a character with its own copy of the keyboard mapping -
     * which works for everything printed on a keyboard and for nothing else: no keyboard has a key
     * per Chinese character. So the host's input method composes the text, the platform hands it
     * over as a character, and the server types it by giving a keycode it has claimed the character
     * as its keysym and telling the clients the mapping changed - which is the only way an X client
     * learns a character that is on no key at all.
     *
     * <p>Nothing is sent for a character the keyboard already carries: the key press has been
     * delivered for it, and a client would type it twice.
     */
    public void keyTyped(char character) {
        int keysym = Keymap.keysymFor(character);
        int onTheKeyboard = Keymap.keycodeForKeysym(keysym);
        // A keycode the server claimed for an earlier character does not count as "on the keyboard":
        // the same character typed twice is two keystrokes, and the second one has to be sent like
        // the first.
        boolean alreadyOnAKey = onTheKeyboard != 0 && onTheKeyboard != Keymap.syntheticKeycode();
        if (keysym == Keysyms.NONE || alreadyOnAKey) {
            return;
        }
        server.lockServer();
        try {
            int keycode = Keymap.syntheticKeycode();
            // The mapping first, then the key: a client that has not re-read the mapping yet would
            // read the keycode as whatever it used to carry.
            Keymap.assign(keycode, new int[] {keysym, keysym});
            server.broadcastMappingNotify((byte) 0, (byte) keycode, (byte) 1);
            deliverKey(keycode, true);
            deliverKey(keycode, false);
        } finally {
            server.unlockServer();
        }
    }

    /**
     * Files the user dropped on the host window, which the drag bridge turns into an XDND drag for
     * whichever application is under the pointer. Called from the toolkit's thread, so it takes the
     * server lock like the rest of this class.
     */
    @Override
    public void hostDrop(java.util.List<String> paths, int x, int y) {
        io.github.oyzh1994.xserver.drop.DropBridge bridge = server.dropBridge();
        if (bridge == null) {
            return;
        }
        server.lockServer();
        try {
            movePointer(x, y, false);
            bridge.hostDropped(paths, x, y);
        } finally {
            server.unlockServer();
        }
    }

    public void keyChanged(int keycode, boolean pressed, int x, int y) {
        if (keycode < (Keymap.MIN_KEYCODE & 0xFF) || keycode > (Keymap.MAX_KEYCODE & 0xFF)) {
            return;
        }
        server.screenSaver().noteInput();
        server.lockServer();
        try {
            movePointer(x, y, false);
            updateModifiers(keycode, pressed);
            if (pressed && keyboardGrab == null) {
                // A passive key grab fires for the focus window's chain, and it *consumes* the press:
                // the client that armed it gets the event and the focused window does not, which is
                // the whole point of a global shortcut.
                InputHandlers.PassiveGrab passive = matchingPassiveGrab(false, focusWindow(), keycode,
                        modifierState());
                if (passive != null) {
                    activatePassiveGrab(passive, keycode, modifierState());
                }
            }
            if (pressed) {
                armRepeat(keycode);
            } else {
                // A key that comes up stops repeating, which is the whole of what a client has to do
                // to stop a run of characters - and a key that is pressed again starts a fresh delay
                // rather than inheriting the old one.
                repeatDue.remove(keycode);
            }
            deliverKey(keycode, pressed);
            if (!pressed && passiveGrabDetail == keycode) {
                endPassiveGrab();
            }
        } finally {
            server.unlockServer();
        }
    }

    @Override
    public void focusChanged(boolean focused) {
        // Losing the host window's focus does not change the X input focus: clients keep theirs.
    }

    /**
     * A size the user asked for by dragging the host window's edge, waiting for the display tick to
     * apply it.
     *
     * <p>Recorded rather than applied: this arrives on the toolkit's thread, and resizing a client's
     * window means taking the server lock and walking the window tree, which the tick is doing at
     * the same time. The tick picks it up and resizes the client's window itself.
     */
    private volatile java.awt.Dimension requestedSize;
    /** Where the user last dragged the host window, reported by the backend. */
    private volatile java.awt.Point hostWindowPosition;

    @Override
    public void windowResized(int width, int height) {
        if (width > 0 && height > 0) {
            requestedSize = new java.awt.Dimension(width, height);
        }
    }

    /** The size the user last dragged the host window to, clearing it. Null when there is none. */
    public java.awt.Dimension takeRequestedSize() {
        java.awt.Dimension requested = requestedSize;
        requestedSize = null;
        return requested;
    }

    /**
     * The user moved the host window. Called from the toolkit's thread, like
     * {@link #windowResized}, so it only records where the window is: the display tick is what
     * remembers it, because that is where the server's own bookkeeping happens and where a write to
     * a file is not holding anybody up.
     */
    @Override
    public void windowMoved(int x, int y) {
        hostWindowPosition = new java.awt.Point(x, y);
    }

    /** Where the host window is now, or null when it has not been moved since the server started. */
    public java.awt.Point hostWindowPosition() {
        return hostWindowPosition;
    }

    // ------------------------------------------------------------------
    // Pointer
    // ------------------------------------------------------------------

    private void movePointer(int x, int y, boolean generateMotion) {
        pointerKnown = true;
        int clampedX = Math.max(0, Math.min(x, screen.width() - 1));
        int clampedY = Math.max(0, Math.min(y, screen.height() - 1));
        boolean moved = clampedX != pointerX || clampedY != pointerY;
        pointerX = clampedX;
        pointerY = clampedY;

        XWindow window = windowAtPointer();
        pointerWindow = window;
        if (moved) {
            rememberMotion(clampedX, clampedY, window);
        }
        if (moved && window != announcedWindow) {
            // Only a pointer that really moved reports a crossing. A button or key event recomputes
            // the window under the pointer too - that is how a window that appeared underneath is
            // noticed - and reporting the crossing there would tell a client its pointer had moved
            // when it had not, in the middle of handling a button: a menu that is opening takes that
            // for its popup being dismissed. What the client is owed is remembered instead, and
            // reported when the gesture that pinned the pointer is over.
            reportPointerWindow(window);
        }
        if (generateMotion && moved) {
            deliverMotion();
        }
    }

    /**
     * Arms the repeat for a key that has just gone down.
     *
     * <p>Two things decide it, and both are the client's to change: the keyboard's repeat control
     * (XKB's {@code RepeatKeys}) and the key's own bit in the per-key bitmap - which is where "a
     * modifier does not repeat" comes from, rather than a rule applied here. A held Shift repeating
     * would be a run of presses no toolkit expects.
     */
    private void armRepeat(int keycode) {
        if (!repeatKeysEnabled || !autoRepeats[keycode]) {
            return;
        }
        repeatDue.put(keycode, System.currentTimeMillis() + repeatDelayMillis);
    }

    /**
     * Delivers the key repeats that have come due. Called from the display tick, under the server
     * lock, once per frame.
     *
     * <p>One repeat per key per call, never a catch-up burst: a tick that was late - a stalled
     * screen, a machine that slept - would otherwise deliver a run of presses at once, which a
     * terminal prints as a line of characters nobody typed.
     */
    public void pollAutoRepeat(long nowMillis) {
        if (repeatDue.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> entry : new java.util.ArrayList<>(repeatDue.entrySet())) {
            int keycode = entry.getKey();
            long due = entry.getValue();
            if (nowMillis < due) {
                continue;
            }
            long next = due + repeatIntervalMillis;
            if (next <= nowMillis) {
                // Late enough that the tick missed a whole interval: resume from now.
                next = nowMillis + repeatIntervalMillis;
            }
            repeatDue.put(keycode, next);
            deliverKey(keycode, true);
        }
    }

    /** Whether a keycode repeats when it is held, and the keyboard is repeating at all. */
    public boolean autoRepeats(int keycode) {
        return repeatKeysEnabled && keycode >= 0 && keycode < autoRepeats.length
                && autoRepeats[keycode];
    }

    /** Whether the per-key bitmap says this keycode repeats, whatever the keyboard's control is. */
    public boolean autoRepeatBit(int keycode) {
        return keycode >= 0 && keycode < autoRepeats.length && autoRepeats[keycode];
    }

    /** Whether the keyboard repeats at all: XKB's {@code RepeatKeys} control. */
    public boolean repeatKeysEnabled() {
        return repeatKeysEnabled;
    }

    /**
     * Turns the whole keyboard's repeat on or off, which is XKB's {@code RepeatKeys} control.
     *
     * <p>The per-key bitmap is left alone, because a client that disables RepeatKeys is not saying
     * "forget which keys repeat" - it is saying "not now", and a client that enables it again expects
     * the keys it configured.
     */
    public void setRepeatKeysEnabled(boolean on) {
        repeatKeysEnabled = on;
        if (!on) {
            repeatDue.clear();
        }
    }

    /** How long a key is held before it repeats, in milliseconds. */
    public int repeatDelayMillis() {
        return repeatDelayMillis;
    }

    /** How long between repeats, in milliseconds. */
    public int repeatIntervalMillis() {
        return repeatIntervalMillis;
    }

    /**
     * Changes how fast a held key repeats: XKB's {@code XkbSetAutoRepeatRate}, and the only way a
     * client can reach these - the core protocol has no request for either.
     *
     * <p>A key that is already repeating keeps its current due time: the new rate applies from the
     * next repeat on, which is what a reference server does.
     */
    public void setRepeatRate(int delayMillis, int intervalMillis) {
        if (delayMillis > 0) {
            repeatDelayMillis = delayMillis;
        }
        if (intervalMillis > 0) {
            repeatIntervalMillis = intervalMillis;
        }
    }

    /**
     * The per-key repeat bitmap in the wire's own form: 32 bytes, one bit per keycode, the least
     * significant bit of each byte first. This is the same array {@code GetKeyboardControl} and
     * XKB's {@code GetControls} both carry.
     */
    public byte[] autoRepeatBitmap() {
        byte[] bits = new byte[32];
        for (int keycode = 0; keycode < autoRepeats.length; keycode++) {
            if (autoRepeats[keycode]) {
                bits[keycode >> 3] |= (byte) (1 << (keycode & 7));
            }
        }
        return bits;
    }

    /**
     * Replaces the per-key repeat bitmap from the wire's form, which is what XKB's
     * {@code SetControls} carries. A key that has just stopped repeating stops at once rather than
     * finishing its run.
     */
    public void setAutoRepeatBitmap(byte[] bits) {
        for (int keycode = 0; keycode < autoRepeats.length; keycode++) {
            int index = keycode >> 3;
            autoRepeats[keycode] = index < bits.length && (bits[index] & (1 << (keycode & 7))) != 0;
        }
        repeatDue.keySet().removeIf(keycode -> !autoRepeats[keycode]);
    }

    /**
     * Turns repeating on or off, for one keycode or for every key.
     *
     * <p>The two are different things, and that was measured rather than assumed: naming no keycode
     * is the keyboard's own repeat control, not a per-key bit - against Xvfb, {@code xset r off}
     * clears the {@code enabledControls} RepeatKeys bit and leaves the per-key bitmap exactly as it
     * was, so a client that turns repeat off and on again gets back the keys it had configured.
     *
     * @param keycode the key, or -1 for the whole keyboard (which is what a client that names no
     *                keycode means)
     * @param on      true for repeat, false for a key that presses once however long it is held
     */
    public void setAutoRepeat(int keycode, boolean on) {
        if (keycode < 0) {
            setRepeatKeysEnabled(on);
            return;
        }
        if (keycode >= autoRepeats.length) {
            return;
        }
        autoRepeats[keycode] = on;
        if (!on) {
            repeatDue.remove(keycode);
        }
    }

    /**
     * Adds one motion to the pointer's history, dropping the oldest when it is full.
     */
    private void rememberMotion(int x, int y, XWindow window) {
        motionHistory.addLast(new Motion(server.events().time(), x, y, window));
        while (motionHistory.size() > MOTION_HISTORY) {
            motionHistory.removeFirst();
        }
    }

    /**
     * The remembered motions inside {@code window} between two times, oldest first.
     *
     * <p>The times are the client's own - the ones its motion events carried - so they are compared
     * the way X compares {@code Time}: as 32 bit values that wrap, and with a range that wraps with
     * them (a client that asks for a range across the wrap gets it). An empty range - {@code start}
     * equal to {@code stop} - is the "how many are there" question, and answers none.
     */
    public List<Motion> motionHistory(XWindow window, long start, long stop) {
        List<Motion> found = new ArrayList<>();
        for (Motion motion : motionHistory) {
            if (motion.window() == window && inTimeRange(motion.time(), start, stop)) {
                found.add(motion);
            }
        }
        return found;
    }

    /** Whether a 32 bit timestamp falls in the half-open range, allowing for the wrap. */
    private static boolean inTimeRange(int time, long start, long stop) {
        long from = start & 0xFFFFFFFFL;
        long until = stop & 0xFFFFFFFFL;
        long at = time & 0xFFFFFFFFL;
        if (from == until) {
            return false;
        }
        return from < until ? at >= from && at < until : at >= from || at < until;
    }

    /**
     * The crossing events for a window that appeared, moved, was raised or went away under a
     * stationary pointer.
     *
     * <p>The pointer has not moved, but the window it is in has, and a client only ever learns
     * which window the pointer is in from crossing events. A window that goes away under the
     * pointer is the case this exists for: without it the pointer stays pinned to a window that no
     * longer exists, and the next click is reported against nothing at all.
     *
     * <p>A window that <em>appears</em> under the pointer is deliberately not reported here. Its
     * client is usually mid-gesture - a menu popping up as the pointer presses on the item that
     * opens it - and the crossing that arrives out of the middle of that gesture is read as the
     * pointer having left the window the gesture belongs to, which tears the menu down as fast as
     * it went up. The change is picked up by the move that follows instead, which is where a
     * client expects to learn where its pointer is.
     */
    public void refreshPointerWindow() {
        if (!pointerKnown) {
            // Nothing has said where the pointer is yet, and the origin is not a position that a
            // window appeared under.
            return;
        }
        XWindow window = windowAtPointer();
        if (window != announcedWindow && log.isDebugEnabled()) {
            log.debug("the window under the pointer is now 0x{}: the tree changed, not the pointer",
                    window == null ? "none" : Integer.toHexString(window.id()));
        }
        settlePointerWindow();
    }

    /**
     * Reports the crossing a client is owed for a window change that happened without the pointer
     * moving, if one is still outstanding.
     *
     * <p>Two things need this. A window that goes away under the pointer leaves the client pinning
     * events to a window that no longer exists, so the change is reported at once. A window that
     * appears under the pointer - the menu a press just opened - cannot be reported while the button
     * that opened it is still down, because the implicit grab withholds the enter, and the crossing
     * is owed until the button comes up; that is where a reference server reports it too.
     */
    public void settlePointerWindow() {
        if (!pointerKnown) {
            return;
        }
        XWindow window = windowAtPointer();
        pointerWindow = window;
        if (window == announcedWindow) {
            return;
        }
        reportPointerWindow(window);
    }

    /**
     * Delivers the crossing pair that says where the pointer is now, and remembers what the client
     * was actually told.
     *
     * <p>Where the pointer is and what the client knows are not the same thing once a grab is
     * involved: a grab can withhold the enter into the window the pointer reached (see
     * {@link #confinedToGrab}), and a client that hears the leave but not the enter has been told the
     * pointer is in none of its windows. That is remembered as such, so the enter is still owed and
     * goes out when the grab lets go rather than being written off.
     */
    private void reportPointerWindow(XWindow window) {
        XWindow inside = confinedToGrab();
        boolean entered = window != null
                && (inside == null || inside == window || inside.isAncestorOf(window));
        XWindow previous = announcedWindow;
        announcedWindow = entered ? window : null;
        server.events().deliverCrossing(previous, window, pointerX, pointerY, pointerX, pointerY,
                inside);
    }

    /**
     * The window crossing events are confined to while the pointer is grabbed, or null when they go
     * everywhere.
     *
     * <p>A grab that reports events to itself takes the pointer over: only the grab window and what
     * is inside it hear where the pointer went. That covers the implicit grab a press starts as
     * well, which is what keeps a window that merely appears under a dragged pointer from behaving
     * as though the pointer entered it - and it is why a popup that a menu maps under the pointer
     * while the button is still down is not entered until the button comes up.
     *
     * <p>A grab with owner_events confines nothing: the grabbing client goes on receiving the
     * crossings of its own windows, and confining those would leave a menu that grabbed the pointer
     * on its main window never learning that the pointer reached one of the menu's own popups,
     * which is where it takes its highlight from.
     */
    private XWindow confinedToGrab() {
        if (implicitGrabWindow != null) {
            // A button is down: the press pinned the pointer to the window it landed on, and that is
            // where the pointer stays until the button comes up. A menu whose popup is mapped under
            // the pointer by the press that opened it is the case this is about - the reference
            // server reports the leave from the window underneath and withholds the enter until the
            // button is released, and a toolkit that is told the popup was entered in the middle of
            // the gesture tears the menu down.
            return implicitGrabWindow;
        }
        if (pointerGrab != null) {
            return pointerGrab.ownerEvents() ? null : pointerGrab.window();
        }
        return null;
    }

    private void deliverMotion() {
        Grab grab = pointerGrab;
        if (grab != null && !grab.ownerEvents()) {
            deliverToGrab(grab, EventMasks.POINTER_MOTION, motionFactory());
            return;
        }
        if (grab != null && deliverToGrabbingClient(grab, EventMasks.POINTER_MOTION,
                motionFactory())) {
            return;
        }
        server.events().deliverPropagating(grabbedWindow(), EventMasks.POINTER_MOTION, pointerX,
                pointerY, motionFactory());
    }

    /**
     * Delivers an event for a grab that reports the client's own windows, and says whether it was
     * one of them: a motion, click or key that lands anywhere else - on the root, on another
     * client's window - belongs to the grabbing client all the same, and is reported against the
     * window it holds the grab on.
     *
     * <p>A menu with the pointer grabbed depends on it. Without this the motion that takes the
     * pointer off the menu goes nowhere at all, so the menu hears that the pointer left and then
     * nothing more, and a click outside it - which is how it learns to close - is never delivered
     * either.
     */
    private boolean deliverToGrabbingClient(Grab grab, int maskBit, EventFactory factory) {
        XWindow window = grabbedWindow();
        if (window != null && window.owner() == grab.client()) {
            return false;                       // the client's own window: the normal path applies
        }
        if ((grab.eventMask() & maskBit) != 0) {
            deliverToGrab(grab, maskBit, factory);
        }
        return true;
    }

    /**
     * The window pointer events are reported against: the one a button press pinned the pointer to
     * while the implicit grab lasts, otherwise the one under the pointer.
     *
     * <p>An explicit grab that reports events to the client's own windows supersedes the implicit
     * one. A popup that appears while a button is still held is exactly that case: the press pins
     * the pointer to the window underneath, the client then grabs the pointer for the popup it just
     * created, and the release has to be reported against the popup - reporting it against the
     * window the press landed on is a release the menu reads as a click outside itself, which
     * dismisses it.
     */
    private XWindow grabbedWindow() {
        if (pointerGrab != null) {
            return pointerWindow;
        }
        return implicitGrabWindow != null ? implicitGrabWindow : pointerWindow;
    }

    /**
     * The window a pointer event is reported against right now: the active grab's window when the
     * grab reports events as though the client had selected them itself, otherwise the window the
     * pointer is pinned to.
     */
    private XWindow eventTarget() {
        Grab grab = pointerGrab;
        if (grab != null && !grab.ownerEvents()) {
            return grab.window();
        }
        return grabbedWindow();
    }

    /**
     * Releases every grab held on a window that is going away.
     *
     * <p>X11 releases an active grab when its grab window is destroyed, and clients rely on it: one
     * that grabs the pointer for a modal dialog and then lets the dialog be destroyed never sends
     * {@code UngrabPointer}, so the grab has to end here. A grab left pointing at a window that no
     * longer exists is worse than a stuck one - every pointer event is reported against nothing, and
     * the pointer goes dead for every client while the keyboard, which follows the focus instead,
     * keeps working.
     */
    public void forgetGrabWindow(XWindow window) {
        if (implicitGrabWindow == window) {
            implicitGrabWindow = null;
        }
        if (pointerGrab != null && pointerGrab.window() == window) {
            pointerGrab = null;
            if (log.isDebugEnabled()) {
                log.debug("released the pointer grab held on 0x{}: the window went away",
                        Integer.toHexString(window.id()));
            }
        }
        if (keyboardGrab != null && keyboardGrab.window() == window) {
            keyboardGrab = null;
            if (log.isDebugEnabled()) {
                log.debug("released the keyboard grab held on 0x{}: the window went away",
                        Integer.toHexString(window.id()));
            }
        }
    }

    private EventFactory motionFactory() {
        return (client, w, child, x, y) -> MotionNotifyEvent.builder()
                .firstEventOffset((byte) 0)
                .sentEvent(false)
                .sequenceNumber(client.sequenceNumber())
                .detail((byte) 0)                       // Normal
                .time(server.events().time())
                .root(screen.root().id())
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
                        .root(screen.root().id())
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
                        .root(screen.root().id())
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
            updateImplicitGrab(pressed);
            return;
        }
        if (grab != null && deliverToGrabbingClient(grab, eventMask, factory)) {
            updateImplicitGrab(pressed);
            return;
        }
        server.events().deliverPropagating(grabbedWindow(), eventMask, pointerX, pointerY, factory);
        updateImplicitGrab(pressed);
    }

    /**
     * The passive grab a press should fire, or null when none of them matches.
     *
     * <p>{@code GrabButton}/{@code GrabKey} record "when this button (or key) is pressed on this window
     * or anything inside it, I want the input", and this is where that is honoured. The window the
     * event landed on is tried first and its ancestors after it, so a grab on a frame beats one on the
     * root - which is how a window manager's frame buttons work.
     *
     * <p>The rules, and all of them were measured against a reference server with
     * {@code tools/GrabProbe}:
     *
     * <ul>
     *   <li><b>Modifiers</b>: {@code AnyModifier} (0x8000) matches any state; a specific modifier
     *       matches only a state that equals it exactly. Measured: a grab armed with {@code ShiftMask}
     *       fires with Shift held and does not fire without it.
     *   <li><b>The detail</b>: {@code AnyButton}/{@code AnyKey} (0) matches every button or key; a
     *       specific one matches only itself.
     *   <li><b>Two arms that both match</b>: the one with a specific modifier beats {@code AnyModifier}
     *       - the closest match - and among equals the last armed wins, which is what
     *       {@code GrabButton}'s "an existing grab with the same parameters is replaced" amounts to.
     *   <li><b>A window that is not mapped never fires</b>: a grab on a window nobody can see is not
     *       something the user can press.
     * </ul>
     *
     * @param pointer true for a button, false for a key
     * @param from the window the event landed on, or the focus window for a key
     */
    private InputHandlers.PassiveGrab matchingPassiveGrab(boolean pointer, XWindow from, int detail,
                                                          int modifiers) {
        for (XWindow window = from; window != null; window = window.parent()) {
            if (!window.isMapped() && !window.isRoot()) {
                // The window itself is not on screen; its ancestors are still worth trying, which is
                // what continuing the walk does.
                continue;
            }
            InputHandlers.PassiveGrab best = null;
            for (InputHandlers.PassiveGrab grab : server.passiveGrabs()) {
                if (grab.pointer() != pointer || grab.window() != window) {
                    continue;
                }
                if (grab.detail() != 0 && grab.detail() != detail) {
                    continue;
                }
                if (!modifiersMatch(grab.modifiers(), modifiers)) {
                    continue;
                }
                if (best == null || closerMatch(grab.modifiers(), best.modifiers())) {
                    best = grab;
                }
            }
            if (best != null) {
                return best;
            }
            if (window.isRoot()) {
                break;
            }
        }
        return null;
    }

    /** A modifier list matches when it is AnyModifier, or when the state equals it exactly. */
    private static boolean modifiersMatch(int wanted, int state) {
        return wanted == ANY_MODIFIER || wanted == state;
    }

    /**
     * Which of two matching arms is the closer one. A specific modifier beats {@code AnyModifier}, and
     * two of the same kind are equally close - the last one armed then wins, which is the order the
     * list is in.
     */
    private static boolean closerMatch(int candidate, int current) {
        return candidate != ANY_MODIFIER && current == ANY_MODIFIER;
    }

    /**
     * Takes the grab a passive one asks for: it is an ordinary active grab from the press to the
     * release, and the clients are told the crossing pair a reference server sends for it.
     */
    private void activatePassiveGrab(InputHandlers.PassiveGrab passive, int detail, int modifiers) {
        Grab grab = new Grab(passive.client(), passive.window(), passive.ownerEvents(),
                passive.eventMask(), !passive.pointer());
        passiveGrabDetail = detail;
        passiveGrabModifiers = modifiers;
        if (passive.pointer()) {
            pointerGrab = grab;
        } else {
            keyboardGrab = grab;
        }
        if (log.isDebugEnabled()) {
            log.debug("passive {} grab on 0x{} fired by {}: the input belongs to {} until the release",
                    passive.pointer() ? "button" : "key",
                    Integer.toHexString(passive.window().id()), detail, passive.client());
        }
        // The pair a grab's start implies: the pointer leaves the window it was in and enters the
        // grab window, with the mode that says a grab did it. A reference server sends this for a
        // passive grab (measured), and §7's deliberate silence is about *active* ones.
        if (passive.pointer()) {
            server.events().deliverGrabCrossing(pointerWindow, passive.window(), grabCrossing(true));
        }
    }

    /** Gives back the grab a passive one took, and reports the crossing pair that ends it. */
    private void endPassiveGrab() {
        Grab grab = passiveGrabDetail == 0 ? null
                : (pointerGrab != null && !pointerGrab.keyboard() ? pointerGrab
                        : keyboardGrab != null && keyboardGrab.keyboard() ? keyboardGrab : null);
        passiveGrabDetail = 0;
        if (grab == null) {
            return;
        }
        if (grab.keyboard()) {
            keyboardGrab = null;
        } else {
            pointerGrab = null;
            server.events().deliverGrabCrossing(pointerWindow, grab.window(), grabCrossing(false));
        }
    }

    /**
     * The mode of an event a grab's own crossings carry, numbered as the protocol numbers them.
     *
     * <p>{@code NotifyGrab} on the way in and {@code NotifyUngrab} on the way out: a client that reads
     * the mode tells "the pointer entered because somebody grabbed it" from "because the user moved
     * it", and a menu that grabs the pointer depends on the difference.
     */
    private static byte grabCrossing(boolean starting) {
        return starting ? EventDispatcher.NOTIFY_MODE_GRAB : EventDispatcher.NOTIFY_MODE_UNGRAB;
    }

    /**
     * Starts the implicit grab when a press lands with no button yet down, and ends it when the last
     * button comes up - the two moments X11 pins the pointer to a window and lets it go again.
     */
    private void updateImplicitGrab(boolean pressed) {
        if (pressed) {
            if (implicitGrabWindow == null && pointerGrab == null) {
                implicitGrabWindow = pointerWindow;
            }
        } else if (buttons == 0) {
            implicitGrabWindow = null;
            // The pointer is the client's to follow again. A window that appeared over it while the
            // button was down - the menu the press opened - has been waiting for this moment to be
            // reported, because a crossing out of the middle of a gesture is read as the pointer
            // leaving the window the gesture belongs to.
            settlePointerWindow();
        }
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
                        .root(screen.root().id())
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
                    .root(screen.root().id())
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
        if (grab != null) {
            XWindow focus = focusWindow();
            if (focus != null && focus.owner() != grab.client()
                    && (grab.eventMask() & eventMask) != 0) {
                deliverToGrab(grab, eventMask, factory);
                return;
            }
        }
        server.events().deliverPropagating(focusWindow(), eventMask, pointerX, pointerY, factory);
    }

    /**
     * The window key events are reported against.
     *
     * <p>A focus of the root window means {@code PointerRoot}: the focus follows the pointer, so the
     * keys belong to the window the pointer is in. That is the state a toolkit leaves behind when it
     * hands the focus back to the desktop, and without it every keystroke is reported against the
     * root - which no client owns - so the keys are dropped and text fields stop accepting input
     * while everything driven by the pointer keeps working.
     */
    public XWindow focusWindow() {
        if (inputFocus == null) {
            // None: the keys belong to nobody and are dropped. Reporting them against the root would
            // hand them to a client that never asked for them.
            return null;
        }
        if (focusIsPointerRoot) {
            return pointerWindow == null ? screen.root() : pointerWindow;
        }
        // The root window focused explicitly is not PointerRoot: the keys go to the root window and
        // stay there, where a client that focused it (rather than asking for PointerRoot) put them.
        return inputFocus;
    }

    /**
     * Reverts the input focus when the focused window is destroyed.
     *
     * <p>X11 reverts the focus the way the last {@code SetInputFocus} asked for - to the parent, to
     * PointerRoot or to nothing - and clients rely on it: a toolkit moves the focus to a dialog when
     * it opens one and lets the dialog be destroyed rather than putting the focus back itself. A
     * focus left on a window that no longer exists swallows every keystroke it was meant to deliver.
     */
    public void forgetFocusWindow(XWindow window) {
        if (inputFocus == null || inputFocus.isRoot()) {
            // PointerRoot follows the pointer and None is nowhere: neither is a window, so neither is
            // touched by a window going away. Moving the focus as the pointer crosses into another
            // window is the half of PointerRoot that is not implemented - see the design notes.
            return;
        }
        // A window that goes away takes the focus with it even when the focus is on something inside
        // it: the focused window stops being viewable then, and a client left sending keys to a
        // window nobody can see is a client whose typing has quietly stopped working.
        if (inputFocus != window && !window.isAncestorOf(inputFocus)) {
            return;
        }
        XWindow previous = inputFocus;
        inputFocus = revertTarget(previous);
        // Only RevertToPointerRoot leaves the focus following the pointer; RevertToParent landing on
        // the root leaves it on the root *window*, which is a different state - see the field.
        focusIsPointerRoot = focusRevertTo != REVERT_TO_PARENT && inputFocus != null
                && inputFocus.isRoot();
        // The focus moved, so both sides of the move are told. The window that was holding it is
        // named as it was even though it is on its way out: being told the focus left is the half
        // its client can still act on.
        server.events().deliverFocusChange(previous, false, inputFocus, focusIsPointerRoot,
                EventDispatcher.NOTIFY_MODE_NORMAL, false);
        if (log.isDebugEnabled()) {
            log.debug("focus reverted from {} to {}: 0x{} went away", describe(previous),
                    describeFocus(inputFocus, focusIsPointerRoot), Integer.toHexString(window.id()));
        }
    }

    /**
     * Where the focus goes when the window holding it goes away, as the last {@code SetInputFocus}
     * asked for.
     *
     * <p>{@code RevertToParent} means the nearest ancestor that is still <em>viewable</em> rather
     * than the parent itself: a dialog and its parent are often on their way out together, and the
     * focus has to land somewhere a client can be told about. When only the root is left the focus
     * becomes PointerRoot, which is what the root window means throughout this class.
     */
    private XWindow revertTarget(XWindow focused) {
        if (focusRevertTo == REVERT_TO_NONE) {
            return null;
        }
        if (focusRevertTo != REVERT_TO_PARENT) {
            return screen.root();
        }
        for (XWindow window = focused.parent(); window != null; window = window.parent()) {
            if (window.isViewable()) {
                return window;
            }
        }
        return screen.root();
    }

    /** Delivers straight to a grabbing client's window, bypassing normal window selection. */
    private void deliverToGrab(Grab grab, int maskBit, EventFactory factory) {
        if ((grab.eventMask() & maskBit) == 0) {
            return;
        }
        XWindow window = grab.window();
        int x = pointerX - window.absoluteX();
        int y = pointerY - window.absoluteY();
        // The child of the grab window the pointer is in, which is what a press reported to a client
        // that owns the whole screen tells it *where* it was: a reference server names it, and a
        // client that takes a global shortcut uses it to decide which of its own windows was clicked.
        XWindow child = window.childAt(pointerX, pointerY);
        server.events().send(grab.client(), factory.create(grab.client(), window, child, x, y));
    }

    // ------------------------------------------------------------------
    // Cursor sprite
    // ------------------------------------------------------------------

    /**
     * The sprite to draw over the screen at the current pointer position, or null when there is
     * none.
     *
     * <p>It is handed to the display backend rather than painted into the framebuffer - see
     * {@link CursorSprite} for why - so this is a description of what to draw: the top left corner
     * the cursor's hotspot puts the image at, and the image itself.
     *
     * <p>Pixels are cached per cursor. A backend asks for this once a frame, and a cursor's image
     * only changes when a client replaces it.
     */
    public CursorSprite cursorSprite() {
        if (!pointerKnown) {
            return null;
        }
        XCursor cursor = cursorFor();
        if (cursor == null) {
            return null;
        }
        int width = cursor.width();
        int height = cursor.height();
        if (cursorPixelsFor != cursor) {
            int[] pixels = new int[width * height];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    pixels[row * width + col] = cursor.pixel(col, row);
                }
            }
            cursorPixels = pixels;
            cursorPixelsFor = cursor;
        }
        return new CursorSprite(pointerX - cursor.hotspotX(), pointerY - cursor.hotspotY(),
                width, height, cursor.hotspotX(), cursor.hotspotY(), cursorPixels);
    }

    /**
     * The cursor the pointer is showing: the nearest one set on the window it is in or above it.
     *
     * <p>Distinct from {@link #cursorSprite()}, which is where to draw it: this is the identity a
     * client is told about when it changes, before anything is drawn and whether or not a frame goes
     * out, which is why XFixes can use it while the display is idle. Null until the pointer's
     * position is known, for the same reason the sprite is: until then there is no window under it to
     * ask.
     */
    public XCursor displayedCursor() {
        return pointerKnown ? cursorFor() : null;
    }

    private XCursor cursorFor() {
        for (XWindow window = pointerWindow; window != null; window = window.parent()) {
            XCursor cursor = window.cursor();
            if (cursor != null) {
                return cursor;
            }
        }
        return screen.rootCursor();
    }
}
