package io.github.oyzh1994.xserver.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The keyboard mapping is the only thing a client has to resolve a key event with.
 *
 * <p>{@code GetKeyboardMapping} tells a client which keysym each keycode carries, and that is what
 * it reports when the keycode arrives - so a letter placed on the wrong keycode is typed as
 * whatever letter that keycode really carries. US keycodes are numbered by the physical key's
 * position rather than alphabetically, which makes the letters from {@code s} to {@code k} the
 * ones a table built by counting off from {@code a} silently gets wrong: they all land six
 * keycodes early, so {@code s} arrives as {@code b}.
 */
public class KeymapTest {

    @Test
    void lettersSitOnTheKeycodesAClientExpects() {
        int[][] expected = {
                {'a', 38}, {'b', 56}, {'c', 54}, {'d', 40}, {'e', 26}, {'f', 41}, {'g', 42},
                {'h', 43}, {'i', 31}, {'j', 44}, {'k', 45}, {'l', 46}, {'m', 58}, {'n', 57},
                {'o', 32}, {'p', 33}, {'q', 24}, {'r', 27}, {'s', 39}, {'t', 28}, {'u', 30},
                {'v', 55}, {'w', 25}, {'x', 53}, {'y', 29}, {'z', 52},
        };
        for (int[] entry : expected) {
            char letter = (char) entry[0];
            assertEquals(entry[1], Keymap.keycodeForLetter(letter),
                    "keycode of '" + letter + "'");
            assertEquals(entry[1], Keymap.keycodeForLetter(Character.toUpperCase(letter)),
                    "a shifted letter names the same key");
        }
    }

    @Test
    void everyLetterReadsBackAsItself() {
        // The property a client sees: the keycode a letter is typed as carries that letter, and
        // its shifted level carries the capital. Broken by a mapping that is merely a permutation
        // of the right keycodes as much as by one that drops a letter.
        for (char letter = 'a'; letter <= 'z'; letter++) {
            int keycode = Keymap.keycodeForLetter(letter);
            assertEquals(letter, Keymap.keysym(keycode, 0), "unshifted '" + letter + "'");
            assertEquals(letter - 32, Keymap.keysym(keycode, 1), "shifted '" + letter + "'");
        }
    }

    @Test
    void lettersAreNotClaimedByPunctuationOrByEachOther() {
        for (char punctuation : new char[] {';', '\'', '`', '\\', ',', '.', '/', '-', '=', '[',
                ']'}) {
            assertEquals(0, Keymap.keycodeForLetter(punctuation),
                    "'" + punctuation + "' is punctuation, not a letter");
        }
        // Every letter needs its own keycode, or one of them is unreachable.
        boolean[] used = new boolean[Keymap.MAX_KEYCODE + 1];
        for (char letter = 'a'; letter <= 'z'; letter++) {
            int keycode = Keymap.keycodeForLetter(letter);
            assertFalse(used[keycode], "keycode " + keycode + " carries two letters");
            used[keycode] = true;
        }
    }
}
