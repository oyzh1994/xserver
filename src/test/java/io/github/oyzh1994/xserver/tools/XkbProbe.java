package io.github.oyzh1994.xserver.tools;

/**
 * Asks a server the XKB questions a client asks as it starts, and prints every reply <b>as bytes</b>.
 *
 * <p>Part of the verification kit ({@code docs/backlog.md} §1), and the harness for item B1 - which
 * is also item A4's fix. {@code xdotool} does not check {@code XkbGetMap}'s NULL, so a server without
 * XKEYBOARD does not make it fall back: it makes it segfault, in {@code xdo_new_with_opened_display},
 * before any subcommand sends a request of its own. That was measured with a gdb backtrace and a
 * trace of the XKB entry points (see the backlog); what this tool adds is the other half - the
 * <em>bytes</em> the reference server answers with, so a reply built here can be compared field by
 * field rather than "the extension is advertised and it did not crash".
 *
 * <pre>
 *   XkbProbe &lt;host&gt; &lt;port&gt;
 * </pre>
 *
 * <p>The requests are the two {@code XkbUseExtension} calls and the {@code XkbGetMap} with
 * {@code which = 0x7} ({@code XkbAllClientInfoMask}) that {@code xdotool} makes, plus {@code
 * XkbGetState} and {@code XkbGetControls}, which the toolkits ask for. The GetMap reply is the big
 * one: a header whose counts say how far each array reaches, then the arrays.
 */
public final class XkbProbe {

    private static final int USE_EXTENSION = 0;
    private static final int SELECT_EVENTS = 1;
    private static final int GET_STATE = 4;
    private static final int GET_CONTROLS = 6;
    private static final int GET_MAP = 8;
    private static final int GET_NAMES = 17;
    private static final int PER_CLIENT_FLAGS = 21;
    private static final int GET_DEVICE_INFO = 24;

    /** {@code XkbUseCoreKbd} - the device every client means when it does not name one. */
    private static final int USE_CORE_KBD = 0x0100;

    /** {@code XkbKeyTypesMask | XkbKeySymsMask | XkbModifierMapMask} - what xdotool asks for. */
    private static final int ALL_CLIENT_INFO = 0x7;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: XkbProbe <host> <port> [which] [dump <file>]");
            return;
        }
        int which = args.length > 2 && !args[2].equals("dump") ? Integer.decode(args[2])
                : ALL_CLIENT_INFO;
        String dump = null;
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equals("dump")) {
                dump = args[i + 1];
            }
        }
        try (Wire wire = Wire.connect(args[0], Integer.parseInt(args[1]))) {
            String name = "XKEYBOARD";
            byte[] request = new byte[(8 + name.length() + 3) & ~3];
            request[0] = 98;                        // QueryExtension
            Wire.writeShort(request, 2, request.length / 4);
            Wire.writeShort(request, 4, name.length());
            for (int i = 0; i < name.length(); i++) {
                request[8 + i] = (byte) name.charAt(i);
            }
            wire.send(request);
            byte[] reply = null;
            while (reply == null) {
                byte[] message = wire.readMessage();
                if (message == null) {
                    System.out.println("XKEYBOARD: no answer to QueryExtension");
                    return;
                }
                if ((message[0] & 0x7F) == 1) {
                    reply = message;
                }
            }
            int opcode = reply[9] & 0xFF;
            System.out.printf("XKEYBOARD present=%d opcode=%d firstEvent=%d firstError=%d%n",
                    reply[8] & 0xFF, opcode, reply[10] & 0xFF, reply[11] & 0xFF);
            if (opcode == 0) {
                return;
            }

            System.out.println("UseExtension: " + hex(useExtension(wire, opcode)));
            System.out.println("GetState:     " + hex(getState(wire, opcode)));
            System.out.println("GetControls:  " + hex(getControls(wire, opcode)));
            System.out.println("PerClientFlags: " + hex(perClientFlags(wire, opcode)));
            System.out.println("GetDeviceInfo:  " + hex(getDeviceInfo(wire, opcode)));
            byte[] map = getMap(wire, opcode, which);
            System.out.println("GetMap<" + which + ">: " + map.length + " bytes");
            if (dump != null) {
                java.nio.file.Files.write(java.nio.file.Path.of(dump), map);
                System.out.println("  dumped to " + dump);
            }
            printMap(map, which);
        }
    }

    /** {@code XkbUseExtension}: the version a client offers, and the one the server has. */
    private static byte[] useExtension(Wire wire, int opcode) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) USE_EXTENSION;
        Wire.writeShort(request, 2, 2);
        Wire.writeShort(request, 4, 1);             // wantedMajor
        Wire.writeShort(request, 6, 0);             // wantedMinor
        return answer(wire, request, "UseExtension");
    }

    /** {@code XkbGetState}: the modifier and group state the keyboard is in. */
    private static byte[] getState(Wire wire, int opcode) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_STATE;
        Wire.writeShort(request, 2, 2);
        Wire.writeShort(request, 4, USE_CORE_KBD);
        return answer(wire, request, "GetState");
    }

    /** {@code XkbGetControls}: the repeat delay/rate and the detectable-auto-repeat flag live here. */
    private static byte[] getControls(Wire wire, int opcode) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_CONTROLS;
        Wire.writeShort(request, 2, 2);
        Wire.writeShort(request, 4, USE_CORE_KBD);
        return answer(wire, request, "GetControls");
    }

    /**
     * {@code XkbGetMap}: the request {@code xdotool} builds. Seven four byte units, and the field
     * order is the one the protocol header declares - device, what to send, what to leave out, then
     * one first/count pair per part.
     */
    private static byte[] getMap(Wire wire, int opcode, int which) throws Exception {
        byte[] request = new byte[28];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_MAP;
        Wire.writeShort(request, 2, 7);
        Wire.writeShort(request, 4, USE_CORE_KBD);
        Wire.writeShort(request, 6, which);         // full
        Wire.writeShort(request, 8, 0);             // partial
        // firstType/nTypes, firstKeySym/nKeySyms, firstKeyAct/nKeyActs, firstKeyBehavior/
        // nKeyBehaviors, virtualMods, firstKeyExplicit/nKeyExplicit, firstModMapKey/nModMapKeys,
        // firstVModMapKey/nVModMapKeys: all zero, meaning "everything".
        return answer(wire, request, "GetMap");
    }

    /**
     * {@code PerClientFlags}: what a toolkit asks to be allowed to change for itself. JavaFX asks
     * this one as EasyShell starts, which is why the probe does too.
     */
    private static byte[] perClientFlags(Wire wire, int opcode) throws Exception {
        byte[] request = new byte[28];
        request[0] = (byte) opcode;
        request[1] = (byte) PER_CLIENT_FLAGS;
        Wire.writeShort(request, 2, 7);
        Wire.writeShort(request, 4, USE_CORE_KBD);
        Wire.writeInt(request, 8, 0);               // change
        Wire.writeInt(request, 12, 0);              // value
        Wire.writeInt(request, 16, 0);              // ctrlsToChange
        Wire.writeInt(request, 20, 0);              // autoCtrls
        Wire.writeInt(request, 24, 0);              // autoCtrlsValues
        return answer(wire, request, "PerClientFlags");
    }

    /** {@code GetDeviceInfo}: the one keyboard, as a device. */
    private static byte[] getDeviceInfo(Wire wire, int opcode) throws Exception {
        byte[] request = new byte[20];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_DEVICE_INFO;
        Wire.writeShort(request, 2, 5);
        Wire.writeShort(request, 4, USE_CORE_KBD);
        Wire.writeShort(request, 6, 0xFFFF);        // wanted: everything
        Wire.writeInt(request, 8, 0);               // all buttons
        Wire.writeInt(request, 12, 0);              // all LEDs
        return answer(wire, request, "GetDeviceInfo");
    }

    private static byte[] answer(Wire wire, byte[] request, String what) throws Exception {
        wire.send(request);
        while (true) {
            byte[] message = wire.readMessage();
            if (message == null) {
                throw new IllegalStateException("no answer to " + what);
            }
            if ((message[0] & 0x7F) == 1) {
                return wire.withExtraData(message);
            }
            System.out.printf("  %s: refused, error %d%n", what, message[1] & 0xFF);
            return new byte[0];
        }
    }

    /**
     * The reply's counts and the arrays they reach. The offsets are the protocol header's own: the
     * GetMap reply is forty bytes before any array starts, not thirty two, because its two tail
     * fields (the modifier map's first/count) are read as part of it.
     */
    private static void printMap(byte[] map, int which) {
        if (map.length < 40) {
            return;
        }
        System.out.printf("  deviceID=%d minKeyCode=%d maxKeyCode=%d present=0x%x length=%d%n",
                map[1] & 0xFF, map[10] & 0xFF, map[11] & 0xFF, Wire.readShort(map, 12),
                Wire.readInt(map, 4));
        System.out.printf("  types: first=%d n=%d total=%d%n",
                map[14] & 0xFF, map[15] & 0xFF, map[16] & 0xFF);
        System.out.printf("  syms:  firstKey=%d n=%d total=%d%n",
                map[17] & 0xFF, map[20] & 0xFF, Wire.readShort(map, 18));
        System.out.printf("  actions: firstKey=%d n=%d total=%d%n",
                map[21] & 0xFF, map[24] & 0xFF, Wire.readShort(map, 22));
        System.out.printf("  behaviors: firstKey=%d n=%d total=%d%n",
                map[25] & 0xFF, map[26] & 0xFF, map[27] & 0xFF);
        System.out.printf("  explicit: firstKey=%d n=%d total=%d%n",
                map[28] & 0xFF, map[29] & 0xFF, map[30] & 0xFF);
        System.out.printf("  modmap: firstKey=%d n=%d total=%d%n",
                map[31] & 0xFF, map[32] & 0xFF, map[33] & 0xFF);
        System.out.printf("  vmodmap: firstKey=%d n=%d total=%d virtualMods=0x%x%n",
                map[34] & 0xFF, map[35] & 0xFF, map[36] & 0xFF, Wire.readShort(map, 38));

        int at = 40;
        // The type descriptors: eight bytes each, and each is followed by its own map entries.
        int types = map[15] & 0xFF;
        for (int i = 0; i < types && at + 8 <= map.length; i++) {
            int mods = map[at] & 0xFF;
            int realMods = map[at + 1] & 0xFF;
            int virtualMods = Wire.readShort(map, at + 2);
            int levels = map[at + 4] & 0xFF;
            int entries = map[at + 5] & 0xFF;
            int preserve = map[at + 6] & 0xFF;
            System.out.printf("  type %d: mask=0x%x realMods=0x%x virtualMods=0x%x levels=%d "
                            + "entries=%d preserve=%d%n",
                    i, mods, realMods, virtualMods, levels, entries, preserve);
            at += 8;
            for (int e = 0; e < entries && at + 8 <= map.length; e++) {
                // A GetMap reply's entries are the eight byte form, and a preserving type adds the
                // four byte preserve mask after each one.
                System.out.printf("    entry: active=%d mask=0x%x level=%d realMods=0x%x "
                                + "virtualMods=0x%x%n",
                        map[at] & 0xFF, map[at + 1] & 0xFF, map[at + 2] & 0xFF, map[at + 3] & 0xFF,
                        Wire.readShort(map, at + 4));
                at += preserve != 0 ? 12 : 8;
            }
        }
        // The sym maps: eight bytes each, then all the keysyms as four byte values.
        int syms = map[20] & 0xFF;
        int firstKeySym = map[17] & 0xFF;
        // Each sym map is eight bytes and its keysyms follow it inline - not a shared pool at the
        // end of the reply, which is what a first reading of the header suggests.
        for (int i = 0; i < syms && at + 8 <= map.length; i++) {
            int width = map[at + 5] & 0xFF;
            int nSyms = Wire.readShort(map, at + 6);
            StringBuilder row = new StringBuilder(String.format(
                    "  symmap kc%d: kt=%d,%d,%d,%d groupInfo=0x%x width=%d nSyms=%d =",
                    firstKeySym + i, map[at] & 0xFF, map[at + 1] & 0xFF, map[at + 2] & 0xFF,
                    map[at + 3] & 0xFF, map[at + 4] & 0xFF, width, nSyms));
            for (int level = 0; level < nSyms; level++) {
                row.append(String.format(" 0x%x", Wire.readInt(map, at + 8 + level * 4)));
            }
            System.out.println(row);
            at += 8 + nSyms * 4;
        }
        // The modifier map is a list of keycode/mods pairs, and its length is the *total*, not the
        // count in this reply's slice.
        int modMapKeys = map[33] & 0xFF;
        if (modMapKeys > 0) {
            System.out.printf("  modmap %d..%d: ", map[31] & 0xFF, (map[31] & 0xFF) + modMapKeys - 1);
            for (int i = 0; i < modMapKeys && at + 2 * i + 2 <= map.length; i++) {
                System.out.printf("%d=0x%x ", map[at + 2 * i] & 0xFF, map[at + 2 * i + 1] & 0xFF);
            }
            System.out.println();
            at += modMapKeys * 2;
        }
        System.out.println("  arrays end at " + at + " of " + map.length);
    }

    private static String hex(byte[] message) {
        StringBuilder text = new StringBuilder();
        for (byte b : message) {
            text.append(String.format("%02x ", b & 0xFF));
        }
        return text.toString();
    }
}
