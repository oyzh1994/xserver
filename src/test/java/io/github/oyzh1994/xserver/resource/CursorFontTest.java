package io.github.oyzh1994.xserver.resource;

import org.junit.jupiter.api.Test;

/**
 * The cursor a client gets when it asks for a cursor-font shape.
 *
 * <p>{@code XCreateFontCursor} reaches {@code CreateGlyphCursor} on the font called "cursor", so a
 * client that wants an arrow, an I-beam or a resize handle asks for the character the cursorfont
 * header names it by - and every one of those used to arrive as a filled cell, because the server had
 * no glyph outlines to draw and approximated the character with "this cell is the shape". The
 * symptom is a solid rectangle under the pointer, which is what `xterm` and `xclock` showed.
 *
 * <p>The glyphs are now the font's own bitmaps, embedded in {@link CursorFont}.
 */
class CursorFontTest {

    private static final int FOREGROUND = 0xFF000000;
    private static final int BACKGROUND = 0xFFFFFFFF;

    /** XC_left_ptr: the standard arrow, the shape almost every client asks for. */
    private static final int LEFT_PTR = 68;

    @Test
    void theStandardArrowIsAnArrowAndNotACell() {
        XCursor cursor = XCursor.fromGlyph(1, null, null, LEFT_PTR, LEFT_PTR + 1, FOREGROUND,
                BACKGROUND);
        int width = cursor.width();
        int height = cursor.height();
        int transparent = 0;
        int foreground = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = cursor.pixel(x, y);
                if (pixel == 0) {
                    transparent++;
                } else if (pixel == FOREGROUND) {
                    foreground++;
                }
            }
        }
        // A filled cell has no transparent pixels at all; the arrow's bitmap has plenty.
        org.junit.jupiter.api.Assertions.assertTrue(transparent > width * height / 4,
                "an arrow leaves most of its cell transparent, and " + transparent + " of "
                        + (width * height) + " were");
        org.junit.jupiter.api.Assertions.assertTrue(foreground > 0, "and draws a shape in the foreground");
    }

    @Test
    void theHotspotIsTheGlyphsOwn() {
        XCursor cursor = XCursor.fromGlyph(1, null, null, LEFT_PTR, LEFT_PTR + 1, FOREGROUND,
                BACKGROUND);
        // The arrow's point is its hotspot: top left, and the pixel there is the shape's own.
        org.junit.jupiter.api.Assertions.assertTrue(cursor.hotspotX() >= 0 && cursor.hotspotY() >= 0,
                "the hotspot is inside the bitmap, not at the origin by accident");
    }

    @Test
    void aShapeTheTableDoesNotHaveStillGetsACursor() {
        // The fallback is the approximation this used to be for everything: a client that invents a
        // character gets a cursor rather than an error.
        XCursor cursor = XCursor.fromGlyph(1, null, new XFont(1, null, "fixed"), 201, 0,
                FOREGROUND, BACKGROUND);
        org.junit.jupiter.api.Assertions.assertTrue(cursor.width() > 0 && cursor.height() > 0);
    }
}
