package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.display.VisualInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What an alpha-only picture's pixels mean.
 *
 * <p>A mask is how every filled path and icon reaches the screen: the toolkit rasterises the shape's
 * coverage into an A8 pixmap and composites a colour through it, one pixel at a time. The coverage
 * <em>is</em> the pixel's value, so a mask pixel of 0x40 means "this point is one quarter covered".
 * A drawable's pixels are held as ARGB, where the same byte is a colour channel and the alpha is
 * opaque - reading a mask that way makes every pixel of it fully covered, and everything drawn
 * through the mask is drawn as a solid rectangle. The symptom is an application's icons and filled
 * paths arriving as blank blocks (measured: termora's toolbar, `_NET_WM_ICON`-sized icons drawn
 * through exactly such a mask).
 *
 * <p>Text was unaffected throughout, because the glyph path decodes through the format already; it
 * was the picture path that did not.
 */
class AlphaMaskTest {

    private static final int A8_DEPTH = 8;
    private static final int A8_MASK = 0xFF;

    /** A one pixel wide A8 pixmap whose single pixel holds {@code coverage}. */
    private static Picture mask(int coverage) {
        XPixmap pixmap = new XPixmap(1, null, null, 1, 1, A8_DEPTH, null);
        // What a depth 8 PutImage stores: the coverage in the low byte, the alpha left opaque.
        pixmap.setPixel(0, 0, 0xFF000000 | coverage);
        return new Picture(2, null, pixmap, PictFormat.alphaOnly(1, A8_DEPTH, A8_MASK),
                null, pixmap, 0, 0, null);
    }

    @Test
    void anA8MasksCoverageIsItsAlpha() {
        assertEquals(0x40000000, mask(0x40).pixelAt(0, 0),
                "a quarter covered pixel is a quarter alpha, not an opaque colour");
        assertEquals(0x00000000, mask(0).pixelAt(0, 0), "no coverage is no alpha");
        assertEquals(0xFF000000, mask(0xFF).pixelAt(0, 0), "full coverage is opaque");
    }

    @Test
    void aColourPictureIsNotDecodedTwice() {
        VisualInfo argb32 = VisualInfo.argb32(0x200);
        XPixmap pixmap = new XPixmap(1, null, null, 1, 1, 32, argb32);
        pixmap.setPixel(0, 0, 0x80112233);
        Picture picture = new Picture(2, null, pixmap, PictFormat.direct(1, argb32, 0xFF000000),
                null, pixmap, 0, 0, null);

        // The format has colour channels, so the stored pixel is already the answer: decoding it as
        // a coverage value would move the blue channel into the alpha and change every colour.
        assertEquals(0x80112233, picture.pixelAt(0, 0));
    }
}
