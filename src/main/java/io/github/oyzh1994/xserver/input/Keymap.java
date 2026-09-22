package io.github.oyzh1994.xserver.input;

/**
 * The server's keyboard mapping: which keysyms each keycode produces, and which keycodes act as
 * modifiers.
 *
 * <p>A US layout is used, which is the same mapping the display backends translate physical keys
 * into: they ask {@link #keycodeForLetter} for a letter's keycode rather than carrying a copy of
 * the table, so the keysym a client reads back and the keycode the host sends cannot disagree.
 * Clients read the mapping with {@code GetKeyboardMapping} and {@code GetModifierMapping}, so the
 * two tables below are the single source of truth for keyboard behaviour.
 */
public final class Keymap {

    public static final byte MIN_KEYCODE = 8;
    /**
     * The highest keycode, 255. Bare, not cast to a byte, because a keycode is used as an array
     * bound far more often than as a wire value.
     */
    public static final int MAX_KEYCODE = 255;

    /** Keysyms per keycode as reported to clients: unshifted then shifted. */
    public static final int KEYSYMS_PER_KEYCODE = 2;

    /** Modifier rows in the order clients receive them: Shift, Lock, Control, Mod1..Mod5. */
    public static final int MODIFIER_COUNT = 8;

    private static final int[][] KEYSYMS = new int[MAX_KEYCODE + 1][];
    private static final int[][] MODIFIERS = new int[MODIFIER_COUNT][];

    static {
        // Letters. Their keycodes are not in alphabet order, so each one names its own keycode
        // through keycodeForLetter instead of being counted off from 'a'.
        for (char letter = 'a'; letter <= 'z'; letter++) {
            KEYSYMS[keycodeForLetter(letter)] = new int[] {letter, letter - 32};
        }
        // Digits 1..9 then 0.
        int[] digitKeys = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19};
        char[] digits = {'1', '2', '3', '4', '5', '6', '7', '8', '9', '0'};
        for (int i = 0; i < digitKeys.length; i++) {
            KEYSYMS[digitKeys[i]] = new int[] {digits[i], Keysyms.toUpper(digits[i])};
        }

        KEYSYMS[9] = pair(Keysyms.ESCAPE);
        KEYSYMS[20] = pair('-', '_');
        KEYSYMS[21] = pair('=', '+');
        KEYSYMS[22] = pair(Keysyms.BACK_SPACE);
        KEYSYMS[23] = pair(Keysyms.TAB);
        // The letters below are already filled in, at 24..33, 38..46 and 52..58; the gaps between
        // them are the punctuation of the home row.
        KEYSYMS[34] = pair('[', '{');
        KEYSYMS[35] = pair(']', '}');
        KEYSYMS[36] = pair(Keysyms.RETURN);
        KEYSYMS[37] = pair(Keysyms.CONTROL_L);
        KEYSYMS[47] = pair(';', ':');
        KEYSYMS[48] = pair('\'', '"');
        KEYSYMS[49] = pair('`', '~');
        KEYSYMS[50] = pair(Keysyms.SHIFT_L);
        KEYSYMS[51] = pair('\\', '|');
        KEYSYMS[59] = pair(',', '<');
        KEYSYMS[60] = pair('.', '>');
        KEYSYMS[61] = pair('/', '?');
        KEYSYMS[62] = pair(Keysyms.SHIFT_R);
        KEYSYMS[63] = pair(Keysyms.KP_MULTIPLY);
        KEYSYMS[64] = pair(Keysyms.ALT_L);
        KEYSYMS[65] = pair(' ');
        KEYSYMS[66] = pair(Keysyms.CAPS_LOCK);
        for (int i = 0; i < 10; i++) {
            KEYSYMS[67 + i] = pair(Keysyms.F1 + i);
        }
        KEYSYMS[77] = pair(Keysyms.NUM_LOCK);
        KEYSYMS[78] = pair(Keysyms.SCROLL_LOCK);
        KEYSYMS[79] = pair(Keysyms.KP_HOME);
        KEYSYMS[80] = pair(Keysyms.KP_UP);
        KEYSYMS[81] = pair(Keysyms.KP_PRIOR);
        KEYSYMS[82] = pair(Keysyms.KP_SUBTRACT);
        KEYSYMS[83] = pair(Keysyms.KP_LEFT);
        KEYSYMS[84] = pair(Keysyms.KP_BEGIN);
        KEYSYMS[85] = pair(Keysyms.KP_RIGHT);
        KEYSYMS[86] = pair(Keysyms.KP_ADD);
        KEYSYMS[87] = pair(Keysyms.KP_END);
        KEYSYMS[88] = pair(Keysyms.KP_DOWN);
        KEYSYMS[89] = pair(Keysyms.KP_NEXT);
        KEYSYMS[90] = pair(Keysyms.KP_INSERT);
        KEYSYMS[91] = pair(Keysyms.KP_DELETE);
        KEYSYMS[92] = pair(Keysyms.KP_ENTER);
        KEYSYMS[94] = pair('<', '>');
        KEYSYMS[95] = pair(Keysyms.F11);
        KEYSYMS[96] = pair(Keysyms.F12);
        KEYSYMS[105] = pair(Keysyms.CONTROL_R);
        KEYSYMS[106] = pair(Keysyms.KP_DIVIDE);
        KEYSYMS[107] = pair(Keysyms.PRINT);
        KEYSYMS[108] = pair(Keysyms.ALT_R);
        KEYSYMS[110] = pair(Keysyms.HOME);
        KEYSYMS[111] = pair(Keysyms.UP);
        KEYSYMS[112] = pair(Keysyms.PRIOR);
        KEYSYMS[113] = pair(Keysyms.LEFT);
        KEYSYMS[114] = pair(Keysyms.RIGHT);
        KEYSYMS[115] = pair(Keysyms.END);
        KEYSYMS[116] = pair(Keysyms.DOWN);
        KEYSYMS[117] = pair(Keysyms.NEXT);
        KEYSYMS[118] = pair(Keysyms.INSERT);
        KEYSYMS[119] = pair(Keysyms.DELETE);
        KEYSYMS[127] = pair(Keysyms.PAUSE);
        KEYSYMS[133] = pair(Keysyms.SUPER_L);
        KEYSYMS[134] = pair(Keysyms.SUPER_R);
        KEYSYMS[135] = pair(Keysyms.MENU);

        // Number pad: the shifted level carries the digit so that Num Lock reaches clients.
        KEYSYMS[79] = pair(Keysyms.KP_HOME, Keysyms.KP_7);
        KEYSYMS[80] = pair(Keysyms.KP_UP, Keysyms.KP_8);
        KEYSYMS[81] = pair(Keysyms.KP_PRIOR, Keysyms.KP_9);
        KEYSYMS[83] = pair(Keysyms.KP_LEFT, Keysyms.KP_4);
        KEYSYMS[84] = pair(Keysyms.KP_BEGIN, Keysyms.KP_5);
        KEYSYMS[85] = pair(Keysyms.KP_RIGHT, Keysyms.KP_6);
        KEYSYMS[87] = pair(Keysyms.KP_END, Keysyms.KP_1);
        KEYSYMS[88] = pair(Keysyms.KP_DOWN, Keysyms.KP_2);
        KEYSYMS[89] = pair(Keysyms.KP_NEXT, Keysyms.KP_3);
        KEYSYMS[90] = pair(Keysyms.KP_INSERT, Keysyms.KP_0);

        MODIFIERS[0] = new int[] {50, 62};      // Shift
        MODIFIERS[1] = new int[] {66};          // Lock
        MODIFIERS[2] = new int[] {37, 105};     // Control
        MODIFIERS[3] = new int[] {64, 108};     // Mod1 (Alt)
        MODIFIERS[4] = new int[] {77};          // Mod2 (Num Lock)
        MODIFIERS[5] = new int[] {};            // Mod3
        MODIFIERS[6] = new int[] {133, 134};    // Mod4 (Super)
        MODIFIERS[7] = new int[] {78, 203};     // Mod5 (Scroll Lock, Mode switch)
    }

    private Keymap() {
    }

    private static int[] pair(int a) {
        return new int[] {a, a};
    }

    private static int[] pair(int a, int b) {
        return new int[] {a, b};
    }

    /** Keysym for a keycode at a shift level, or {@link Keysyms#NONE} when unassigned. */
    public static int keysym(int keycode, int level) {
        if (keycode < 0 || keycode > 255) {
            return Keysyms.NONE;
        }
        int[] row = KEYSYMS[keycode];
        if (row == null) {
            return Keysyms.NONE;
        }
        if (level < 0) {
            level = 0;
        }
        return level < row.length ? row[level] : row[row.length - 1];
    }

    /**
     * The keycode of a US layout letter, or 0 when the character is not one.
     *
     * <p>The keycodes are not in alphabet order: they are numbered by the physical key's position,
     * and the home row's punctuation (47..51) sits between the letters, so {@code a} is 38 while
     * {@code b} is 56. Counting off from {@code a} therefore puts seven letters on the wrong
     * keycode - the ones from {@code s} to {@code k} - and a client, which resolves keycodes
     * through {@code GetKeyboardMapping}, then types the letter that keycode really carries.
     */
    public static int keycodeForLetter(char letter) {
        return switch (Character.toLowerCase(letter)) {
            case 'a' -> 38;
            case 'b' -> 56;
            case 'c' -> 54;
            case 'd' -> 40;
            case 'e' -> 26;
            case 'f' -> 41;
            case 'g' -> 42;
            case 'h' -> 43;
            case 'i' -> 31;
            case 'j' -> 44;
            case 'k' -> 45;
            case 'l' -> 46;
            case 'm' -> 58;
            case 'n' -> 57;
            case 'o' -> 32;
            case 'p' -> 33;
            case 'q' -> 24;
            case 'r' -> 27;
            case 's' -> 39;
            case 't' -> 28;
            case 'u' -> 30;
            case 'v' -> 55;
            case 'w' -> 25;
            case 'x' -> 53;
            case 'y' -> 29;
            case 'z' -> 52;
            default -> 0;
        };
    }

    /** All keysyms for a keycode, exactly {@link #KEYSYMS_PER_KEYCODE} entries. */
    public static int[] keysymsFor(int keycode) {
        int[] result = new int[KEYSYMS_PER_KEYCODE];
        for (int level = 0; level < KEYSYMS_PER_KEYCODE; level++) {
            result[level] = keysym(keycode, level);
        }
        return result;
    }

    /** Keycodes assigned to each modifier row, as reported by {@code GetModifierMapping}. */
    public static int[] modifierRow(int index) {
        return MODIFIERS[index].clone();
    }

    public static int modifierCount() {
        return MODIFIER_COUNT;
    }

    /** Which modifier bit a keycode sets on press, or 0 when it is not a modifier. */
    public static int modifierMaskForKeycode(int keycode) {
        int mask = 0;
        for (int row = 0; row < MODIFIERS.length; row++) {
            for (int candidate : MODIFIERS[row]) {
                if (candidate == keycode) {
                    mask |= 1 << row;
                    break;
                }
            }
        }
        return mask;
    }

    /** True when the keycode is the left or right Shift key. */
    public static boolean isShift(int keycode) {
        return keycode == 50 || keycode == 62;
    }

    /** True when the keycode is Caps Lock. */
    public static boolean isCapsLock(int keycode) {
        return keycode == 66;
    }

    /** True when the keycode is a Num Lock or Scroll Lock style latch. */
    public static boolean isLatch(int keycode) {
        return keycode == 77 || keycode == 78;
    }
}
