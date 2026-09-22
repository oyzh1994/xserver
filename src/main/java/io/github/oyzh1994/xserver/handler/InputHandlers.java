package io.github.oyzh1994.xserver.handler;

import com.github.moaxcp.x11.protocol.xproto.AllowEvents;
import com.github.moaxcp.x11.protocol.xproto.ChangeActivePointerGrab;
import com.github.moaxcp.x11.protocol.xproto.ChangeKeyboardMapping;
import com.github.moaxcp.x11.protocol.xproto.GetInputFocus;
import com.github.moaxcp.x11.protocol.xproto.GetInputFocusReply;
import com.github.moaxcp.x11.protocol.xproto.GetKeyboardMapping;
import com.github.moaxcp.x11.protocol.xproto.GetKeyboardMappingReply;
import com.github.moaxcp.x11.protocol.xproto.GetModifierMapping;
import com.github.moaxcp.x11.protocol.xproto.GetModifierMappingReply;
import com.github.moaxcp.x11.protocol.xproto.GetMotionEvents;
import com.github.moaxcp.x11.protocol.xproto.GetMotionEventsReply;
import com.github.moaxcp.x11.protocol.xproto.GetPointerMapping;
import com.github.moaxcp.x11.protocol.xproto.GetPointerMappingReply;
import com.github.moaxcp.x11.protocol.xproto.GrabButton;
import com.github.moaxcp.x11.protocol.xproto.GrabKey;
import com.github.moaxcp.x11.protocol.xproto.GrabKeyboard;
import com.github.moaxcp.x11.protocol.xproto.GrabKeyboardReply;
import com.github.moaxcp.x11.protocol.xproto.GrabPointer;
import com.github.moaxcp.x11.protocol.xproto.GrabPointerReply;
import com.github.moaxcp.x11.protocol.xproto.QueryKeymap;
import com.github.moaxcp.x11.protocol.xproto.QueryKeymapReply;
import com.github.moaxcp.x11.protocol.xproto.QueryPointer;
import com.github.moaxcp.x11.protocol.xproto.QueryPointerReply;
import com.github.moaxcp.x11.protocol.xproto.SetInputFocus;
import com.github.moaxcp.x11.protocol.xproto.SetModifierMapping;
import com.github.moaxcp.x11.protocol.xproto.SetPointerMapping;
import com.github.moaxcp.x11.protocol.xproto.UngrabButton;
import com.github.moaxcp.x11.protocol.xproto.UngrabKey;
import com.github.moaxcp.x11.protocol.xproto.UngrabKeyboard;
import com.github.moaxcp.x11.protocol.xproto.UngrabPointer;
import com.github.moaxcp.x11.protocol.xproto.WarpPointer;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.input.InputState;
import io.github.oyzh1994.xserver.input.Keymap;
import io.github.oyzh1994.xserver.resource.XResource;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.eclipse.collections.impl.factory.primitive.IntLists;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pointer, keyboard, focus and grab requests.
 *
 * <p>Grabs are the mechanism window managers and menus are built on: a client asks to receive
 * pointer or keyboard events even when they happen outside its windows. Active grabs are honoured
 * here; passive grabs are recorded and matched when a button or key is pressed.
 */
public final class InputHandlers {

    /** Grab status values reported to clients. */
    private static final byte GRAB_SUCCESS = 0;
    private static final byte GRAB_ALREADY_GRABBED = 1;
    private static final byte GRAB_NOT_VIEWABLE = 4;

    /** AllowEvents modes. */
    private static final int MODE_ASYNC_POINTER = 0;
    private static final int MODE_SYNC_POINTER = 1;
    private static final int MODE_REPLAY_POINTER = 2;
    private static final int MODE_ASYNC_KEYBOARD = 3;
    private static final int MODE_SYNC_KEYBOARD = 4;
    private static final int MODE_REPLAY_KEYBOARD = 5;
    private static final int MODE_ASYNC_BOTH = 6;
    private static final int MODE_SYNC_BOTH = 7;

    private final XServer server;
    /** Keycodes whose keysyms a client replaced with {@code ChangeKeyboardMapping}. */
    private final Map<Integer, int[]> keymapOverrides = new HashMap<>();
    /** Pointer button remapping; index 0 is button 1. */
    private byte[] pointerMapping = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    /** A modifier row holds two keycodes: the left and right variant of the modifier. */
    private static final int KEYCODES_PER_MODIFIER = 2;

    /** A modifier mapping is always eight rows: Shift, Lock, Control, Mod1 to Mod5. */
    private static final int MODIFIER_ROWS = 8;

    /** Modifier keycodes, eight rows of {@link #KEYCODES_PER_MODIFIER} keycodes. */
    private byte[] modifierMapping = defaultModifierMapping();

    public InputHandlers(XServer server) {
        this.server = server;
    }

    private static byte[] defaultModifierMapping() {
        byte[] result = new byte[MODIFIER_ROWS * KEYCODES_PER_MODIFIER];
        for (int row = 0; row < MODIFIER_ROWS; row++) {
            int[] keycodes = Keymap.modifierRow(row);
            for (int i = 0; i < KEYCODES_PER_MODIFIER && i < keycodes.length; i++) {
                result[row * KEYCODES_PER_MODIFIER + i] = (byte) keycodes[i];
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Pointer and focus queries
    // ------------------------------------------------------------------

    public void queryPointer(ClientConnection client, QueryPointer r) {
        XWindow window = window(client, r.getWindow());
        InputState input = server.inputFor(client);
        XWindow child = window.windowAt(input.pointerX(), input.pointerY());
        int childId = child == window ? 0 : child.id();
        client.sendReply(QueryPointerReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .sameScreen(true)
                .root(input.screen().root().id())
                .child(childId)
                .rootX((short) input.pointerX())
                .rootY((short) input.pointerY())
                .winX((short) (input.pointerX() - window.absoluteX()))
                .winY((short) (input.pointerY() - window.absoluteY()))
                .mask((short) input.pointerState())
                .build());
    }

    public void getMotionEvents(ClientConnection client, GetMotionEvents r) {
        // Pointer history is not retained: reporting no events is legal and keeps the server from
        // growing a buffer per window that almost no client ever reads.
        window(client, r.getWindow());
        client.sendReply(GetMotionEventsReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .events(org.eclipse.collections.impl.factory.Lists.immutable.empty())
                .build());
    }

    public void warpPointer(ClientConnection client, WarpPointer r) {
        InputState input = server.inputFor(client);
        int x;
        int y;
        if (r.getDstWindow() == 0) {
            // A null destination means "do not move", except when the source is also null, in
            // which case the coordinates are relative to the current position.
            if (r.getSrcWindow() == 0) {
                x = input.pointerX() + r.getDstX();
                y = input.pointerY() + r.getDstY();
            } else {
                return;
            }
        } else {
            XWindow destination = window(client, r.getDstWindow());
            int baseX = destination.absoluteX() + r.getDstX();
            int baseY = destination.absoluteY() + r.getDstY();
            if (r.getSrcWindow() == 0) {
                x = baseX;
                y = baseY;
            } else {
                XWindow source = window(client, r.getSrcWindow());
                int srcX = source.absoluteX() + r.getSrcX();
                int srcY = source.absoluteY() + r.getSrcY();
                int srcW = r.getSrcWidth() == 0 ? source.width() : r.getSrcWidth();
                int srcH = r.getSrcHeight() == 0 ? source.height() : r.getSrcHeight();
                if (input.pointerX() < srcX || input.pointerX() >= srcX + srcW
                        || input.pointerY() < srcY || input.pointerY() >= srcY + srcH) {
                    // The pointer is outside the source rectangle, so nothing happens.
                    return;
                }
                x = baseX + (input.pointerX() - srcX);
                y = baseY + (input.pointerY() - srcY);
            }
        }
        input.warpPointer(x, y);
        server.markScreenDirty();
    }

    public void setInputFocus(ClientConnection client, SetInputFocus r) {
        int focus = r.getFocus();
        XWindow window;
        if (focus == 0) {
            // None: the keyboard focus is nowhere.
            window = null;
        } else if (focus == 1) {
            window = server.inputFor(client).windowAtPointer();
        } else if (focus == 3) {
            window = server.inputFor(client).screen().root();
        } else {
            window = window(client, focus);
        }
        server.inputFor(client).setInputFocus(window, r.getRevertTo());
    }

    public void getInputFocus(ClientConnection client, GetInputFocus r) {
        XWindow focus = server.inputFor(client).inputFocus();
        client.sendReply(GetInputFocusReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .revertTo((byte) server.inputFor(client).focusRevertTo())
                .focus(focus == null ? 0
                        : (focus == server.inputFor(client).screen().root() ? 1 : focus.id()))
                .build());
    }

    public void queryKeymap(ClientConnection client, QueryKeymap r) {
        byte[] keys = new byte[32];
        for (int row = 0; row < 8; row++) {
            keys[row] = (byte) modifierRowMask(client, row);
        }
        client.sendReply(QueryKeymapReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .keys(ByteLists.immutable.of(keys))
                .build());
    }

    private int modifierRowMask(ClientConnection client, int row) {
        return (server.inputFor(client).modifierState() & (1 << row)) != 0 ? 0xFF : 0;
    }

    // ------------------------------------------------------------------
    // Pointer grabs
    // ------------------------------------------------------------------

    public void grabPointer(ClientConnection client, GrabPointer r) {
        XWindow window = window(client, r.getGrabWindow());
        client.sendReply(GrabPointerReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status(grabStatus(client, window))
                .build());
        if (grabStatus(client, window) != GRAB_SUCCESS) {
            return;
        }
        server.inputFor(client).setPointerGrab(new InputState.Grab(client, window, r.isOwnerEvents(),
                Short.toUnsignedInt(r.getEventMask()), false));
    }

    public void ungrabPointer(ClientConnection client, UngrabPointer r) {
        InputState.Grab grab = server.inputFor(client).pointerGrab();
        if (grab != null && grab.client() == client) {
            server.inputFor(client).setPointerGrab(null);
        }
    }

    public void changeActivePointerGrab(ClientConnection client, ChangeActivePointerGrab r) {
        InputState.Grab grab = server.inputFor(client).pointerGrab();
        if (grab == null || grab.client() != client) {
            return;
        }
        server.inputFor(client).setPointerGrab(new InputState.Grab(grab.client(), grab.window(),
                grab.ownerEvents(), Short.toUnsignedInt(r.getEventMask()), false));
    }

    public void grabButton(ClientConnection client, GrabButton r) {
        XWindow window = window(client, r.getGrabWindow());
        server.passiveGrabs().add(new PassiveGrab(client, window, r.isOwnerEvents(),
                Short.toUnsignedInt(r.getEventMask()), true, r.getButton(),
                Short.toUnsignedInt(r.getModifiers()), Byte.toUnsignedInt(r.getPointerMode()),
                Byte.toUnsignedInt(r.getKeyboardMode())));
    }

    public void ungrabButton(ClientConnection client, UngrabButton r) {
        int button = Byte.toUnsignedInt(r.getButton());
        int modifiers = Short.toUnsignedInt(r.getModifiers());
        XWindow window = window(client, r.getGrabWindow());
        server.passiveGrabs().removeIf(grab -> grab.client() == client && grab.window() == window
                && grab.pointer() && (button == 0 || grab.detail() == button)
                && (modifiers == 0x8000 || grab.modifiers() == modifiers));
    }

    // ------------------------------------------------------------------
    // Keyboard grabs
    // ------------------------------------------------------------------

    public void grabKeyboard(ClientConnection client, GrabKeyboard r) {
        XWindow window = window(client, r.getGrabWindow());
        byte status = grabStatus(client, window);
        client.sendReply(GrabKeyboardReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status(status)
                .build());
        if (status != GRAB_SUCCESS) {
            return;
        }
        server.inputFor(client).setKeyboardGrab(new InputState.Grab(client, window, r.isOwnerEvents(),
                EventMasks.KEYBOARD_GRAB, true));
    }

    public void ungrabKeyboard(ClientConnection client, UngrabKeyboard r) {
        InputState.Grab grab = server.inputFor(client).keyboardGrab();
        if (grab != null && grab.client() == client) {
            server.inputFor(client).setKeyboardGrab(null);
        }
    }

    public void grabKey(ClientConnection client, GrabKey r) {
        XWindow window = window(client, r.getGrabWindow());
        int modifiers = Short.toUnsignedInt(r.getModifiers());
        server.passiveGrabs().add(new PassiveGrab(client, window, r.isOwnerEvents(),
                EventMasks.KEYBOARD_GRAB, false, Byte.toUnsignedInt(r.getKey()), modifiers,
                Byte.toUnsignedInt(r.getPointerMode()), Byte.toUnsignedInt(r.getKeyboardMode())));
    }

    public void ungrabKey(ClientConnection client, UngrabKey r) {
        int key = Byte.toUnsignedInt(r.getKey());
        int modifiers = Short.toUnsignedInt(r.getModifiers());
        XWindow window = window(client, r.getGrabWindow());
        server.passiveGrabs().removeIf(grab -> grab.client() == client && grab.window() == window
                && !grab.pointer() && (key == 0 || grab.detail() == key)
                && (modifiers == 0x8000 || grab.modifiers() == modifiers));
    }

    public void allowEvents(ClientConnection client, AllowEvents r) {
        int mode = r.getMode();
        if (mode < MODE_ASYNC_POINTER || mode > MODE_SYNC_BOTH) {
            throw ProtocolException.badValue(mode);
        }
        // Events are always delivered asynchronously, so Sync and Replay modes have nothing to
        // release: the events they would hold back have already been sent.
    }

    private byte grabStatus(ClientConnection client, XWindow window) {
        if (!window.isViewable()) {
            return GRAB_NOT_VIEWABLE;
        }
        InputState.Grab pointer = server.inputFor(client).pointerGrab();
        if (pointer != null && pointer.client() != client) {
            return GRAB_ALREADY_GRABBED;
        }
        InputState.Grab keyboard = server.inputFor(client).keyboardGrab();
        if (keyboard != null && keyboard.client() != client) {
            return GRAB_ALREADY_GRABBED;
        }
        return GRAB_SUCCESS;
    }

    // ------------------------------------------------------------------
    // Mappings
    // ------------------------------------------------------------------

    public void setPointerMapping(ClientConnection client, SetPointerMapping r) {
        byte[] requested = r.getMap().toArray();
        for (byte value : requested) {
            if ((value & 0xFF) > 10) {
                throw ProtocolException.badValue(value & 0xFF);
            }
        }
        // A mapping that is not a permutation is only partially applied, per the protocol.
        this.pointerMapping = requested.length == 0 ? new byte[] {1} : requested;
        client.sendReply(com.github.moaxcp.x11.protocol.xproto.SetPointerMappingReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status(mapIsPermutation(requested) ? (byte) 0 : (byte) 1)
                .build());
    }

    private static boolean mapIsPermutation(byte[] map) {
        Set<Integer> seen = new LinkedHashSet<>();
        for (byte value : map) {
            int v = value & 0xFF;
            if (v == 0 || !seen.add(v)) {
                return false;
            }
        }
        return true;
    }

    public void getPointerMapping(ClientConnection client, GetPointerMapping r) {
        client.sendReply(GetPointerMappingReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .map(ByteLists.immutable.of(pointerMapping))
                .build());
    }

    public void setModifierMapping(ClientConnection client, SetModifierMapping r) {
        byte[] keycodes = r.getKeycodes().toArray();
        int maxKeycode = Keymap.MAX_KEYCODE & 0xFF;
        Set<Integer> seen = new LinkedHashSet<>();
        for (byte value : keycodes) {
            int keycode = value & 0xFF;
            if (keycode == 0) {
                continue;
            }
            if (keycode < (Keymap.MIN_KEYCODE & 0xFF) || keycode > maxKeycode) {
                throw ProtocolException.badValue(keycode);
            }
            if (!seen.add(keycode)) {
                throw ProtocolException.badValue(keycode);
            }
        }
        client.sendReply(com.github.moaxcp.x11.protocol.xproto.SetModifierMappingReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status((byte) 0)
                .build());
    }

    /**
     * Answers {@code GetModifierMapping}.
     *
     * <p>The reply is encoded here because the generated writer fills the keycodes-per-modifier byte
     * with the total number of keycodes rather than the number per row. A client that trusts it
     * allocates eight times too much and reads past the reply - xterm segfaults on the spot - so the
     * byte has to be the row length.
     */
    public void getModifierMapping(ClientConnection client, GetModifierMapping r) {
        byte[] bytes = new byte[32 + modifierMapping.length];
        bytes[0] = 1;                                   // reply
        bytes[1] = (byte) (modifierMapping.length / MODIFIER_ROWS);
        int sequence = client.sequenceNumber() & 0xFFFF;
        int length = modifierMapping.length / 4;
        if (client.isBigEndian()) {
            bytes[2] = (byte) (sequence >> 8);
            bytes[3] = (byte) sequence;
            bytes[4] = (byte) (length >> 24);
            bytes[5] = (byte) (length >> 16);
            bytes[6] = (byte) (length >> 8);
            bytes[7] = (byte) length;
        } else {
            bytes[2] = (byte) sequence;
            bytes[3] = (byte) (sequence >> 8);
            bytes[4] = (byte) length;
            bytes[5] = (byte) (length >> 8);
            bytes[6] = (byte) (length >> 16);
            bytes[7] = (byte) (length >> 24);
        }
        System.arraycopy(modifierMapping, 0, bytes, 32, modifierMapping.length);
        try {
            client.output().writeRaw(bytes);
        } catch (java.io.IOException e) {
            client.close();
        }
    }

    public void changeKeyboardMapping(ClientConnection client, ChangeKeyboardMapping r) {
        int count = Byte.toUnsignedInt(r.getKeycodeCount());
        int first = Byte.toUnsignedInt(r.getFirstKeycode());
        int perKeycode = Byte.toUnsignedInt(r.getKeysymsPerKeycode());
        if (count == 0 || perKeycode == 0) {
            throw ProtocolException.badValue(count == 0 ? count : perKeycode);
        }
        int maxKeycode = Keymap.MAX_KEYCODE & 0xFF;
        if (first < (Keymap.MIN_KEYCODE & 0xFF) || first + count - 1 > maxKeycode) {
            throw ProtocolException.badValue(first);
        }
        int[] keysyms = r.getKeysyms().toArray();
        if (keysyms.length < count * perKeycode) {
            throw ProtocolException.badLength();
        }
        for (int i = 0; i < count; i++) {
            int[] row = new int[perKeycode];
            System.arraycopy(keysyms, i * perKeycode, row, 0, perKeycode);
            keymapOverrides.put(first + i, row);
        }
        // Clients are told the mapping changed so they can refresh their caches.
        server.broadcastMappingNotify((byte) 0, (byte) first, (byte) count);
    }

    public void getKeyboardMapping(ClientConnection client, GetKeyboardMapping r) {
        int first = Byte.toUnsignedInt(r.getFirstKeycode());
        int count = Byte.toUnsignedInt(r.getCount());
        if (count == 0) {
            throw ProtocolException.badValue(count);
        }
        int maxKeycode = Keymap.MAX_KEYCODE & 0xFF;
        if (first + count - 1 > maxKeycode) {
            throw ProtocolException.badValue(first);
        }
        int perKeycode = Keymap.KEYSYMS_PER_KEYCODE;
        int[] keysyms = new int[count * perKeycode];
        for (int i = 0; i < count; i++) {
            int keycode = first + i;
            int[] row = keymapOverrides.get(keycode);
            for (int level = 0; level < perKeycode; level++) {
                keysyms[i * perKeycode + level] = row != null && level < row.length
                        ? row[level] : Keymap.keysym(keycode, level);
            }
        }
        client.sendReply(GetKeyboardMappingReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .keysymsPerKeycode((byte) perKeycode)
                .keysyms(IntLists.immutable.of(keysyms))
                .build());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private XWindow window(ClientConnection client, int id) {
        return (XWindow) server.resources().require(client, id, XResource.CLASS_WINDOW, false);
    }

    /** A keyboard or pointer grab that activates when a matching input event arrives. */
    public record PassiveGrab(ClientConnection client, XWindow window, boolean ownerEvents,
                              int eventMask, boolean pointer, int detail, int modifiers,
                              int pointerMode, int keyboardMode) {
    }
}
