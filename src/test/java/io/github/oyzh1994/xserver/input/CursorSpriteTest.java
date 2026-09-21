package io.github.oyzh1994.xserver.input;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.CursorSprite;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pointer sprite belongs to the display backend, not to the framebuffer.
 *
 * <p>Painting it into the framebuffer and saving the pixels underneath is what left a trail of
 * patches wherever the pointer had been: a client that drew over the sprite made the saved copy
 * stale, and the next pointer move put that stale copy back. Keeping it out also means a client's
 * own pixels are never covered by it, and {@code GetImage} never reads it back.
 */
public class CursorSpriteTest {

    @Test
    void spriteIsTransparentAndLeavesTheFramebufferAlone() {
        // No backend is started, so this runs headless wherever the tests do.
        XServer server = new XServer(ServerOptions.parse(
                new String[]{"--display", "98", "--backend", "headless"}));
        Framebuffer framebuffer = server.screen().framebuffer();
        int before = framebuffer.pixel(40, 40);

        server.input().pointerMoved(40, 40);
        CursorSprite sprite = server.input().cursorSprite();

        assertNotNull(sprite, "the root cursor is always set");
        assertEquals(40, sprite.x(), "the default cursor's hotspot is its top left corner");
        assertEquals(40, sprite.y());

        // The default cursor is an arrow: everything but the arrow itself is transparent, and
        // forcing those pixels opaque is what makes it arrive as a black square.
        int transparent = 0;
        for (int pixel : sprite.pixels()) {
            if ((pixel >>> 24) == 0) {
                transparent++;
            }
        }
        assertTrue(transparent > sprite.pixels().length / 2,
                "most of a cursor is transparent, but only " + transparent + " of "
                        + sprite.pixels().length + " pixels are");

        assertEquals(before, framebuffer.pixel(40, 40),
                "the sprite must not be written into the framebuffer");
    }
}
