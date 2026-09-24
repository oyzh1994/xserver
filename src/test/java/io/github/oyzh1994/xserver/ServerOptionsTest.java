package io.github.oyzh1994.xserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code --scale} means.
 *
 * <p>It is the one option whose value the host machine has an opinion about: on a Retina panel the
 * screen has twice the pixels for the same hand-span, and {@code auto} is the answer "this display's
 * own scale". Everything else about it is a whole number between 1 and a display that can actually
 * be lined up pixel for pixel.
 */
class ServerOptionsTest {

    private static ServerOptions parse(String... args) {
        return ServerOptions.parse(args);
    }

    @Test
    void scaleDefaultsToOne() {
        assertEquals(1, parse().scale(), "a display that says nothing is a 1x display");
    }

    @Test
    void anExplicitScaleIsTakenAsGiven() {
        assertEquals(2, parse("--scale", "2").scale());
        assertEquals(1, parse("--scale", "1").scale(), "and 1 is how a user turns scaling off");
    }

    @Test
    void autoMeansWhateverTheHostSays() {
        // The value the test machine would report, which is 1 on anything without a HiDPI panel -
        // the point is that it is asked for rather than assumed, and that it is a usable number
        // either way rather than a value that has to be resolved later by somebody else.
        assertEquals(ServerOptions.hostScale(), parse("--scale", "auto").scale());
        assertTrue(ServerOptions.hostScale() >= 1);

        ServerOptions scaled = parse("--scale", "auto");
        assertEquals(scaled.width() * scaled.scale(), scaled.pixelWidth(),
                "the screen is the host's scale times the logical size");
    }

    @Test
    void aScaleNoDisplayCanLineUpWithIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--scale", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--scale", "5"));
        assertThrows(IllegalArgumentException.class, () -> parse("--scale", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> parse("--scale", "two"));
    }

    @Test
    void maxScreenIsParsedAndCapped() {
        ServerOptions options = parse("--max-screen", "1024x768");
        assertEquals(1024, options.maxScreenWidth());
        assertEquals(768, options.maxScreenHeight());

        // The setup reply describes a screen in sixteen bit fields, so a larger one could not be
        // described to a client at all.
        assertEquals(65535, parse("--max-screen", "100000x100000").maxScreenWidth());
    }
}
