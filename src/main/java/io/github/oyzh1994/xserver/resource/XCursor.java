package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;

/**
 * A cursor: a small ARGB image plus a hotspot.
 *
 * <p>The cursor is drawn straight into the framebuffer at the pointer position, which keeps the
 * sprite handling identical for every display backend.
 */
public final class XCursor extends XResource {

    /** Standard X cursor shape, used when a window has no cursor of its own. */
    private static final int[] DEFAULT_CURSOR = {
            0b1000000000000000,
            0b1100000000000000,
            0b1110000000000000,
            0b1111000000000000,
            0b1111100000000000,
            0b1111110000000000,
            0b1111111000000000,
            0b1111111100000000,
            0b1111111110000000,
            0b1111111111000000,
            0b1111111111100000,
            0b1111110000000000,
            0b1100110000000000,
            0b1000011000000000,
            0b0000011000000000,
            0b0000000000000000,
    };

    private final int width;
    private final int height;
    private final int hotspotX;
    private final int hotspotY;
    private final int[] pixels;
    private final boolean recolorable;

    private int foreground;
    private int background;

    public XCursor(int id, ClientConnection owner, int width, int height, int hotspotX,
                   int hotspotY, int[] pixels, boolean recolorable) {
        super(id, CLASS_CURSOR, owner);
        this.width = width;
        this.height = height;
        this.hotspotX = hotspotX;
        this.hotspotY = hotspotY;
        this.pixels = pixels;
        this.recolorable = recolorable;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int hotspotX() {
        return hotspotX;
    }

    public int hotspotY() {
        return hotspotY;
    }

    public int pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return pixels[y * width + x];
    }

    public boolean isRecolorable() {
        return recolorable;
    }

    public void setColors(int foreground, int background) {
        this.foreground = foreground;
        this.background = background;
    }

    public int foreground() {
        return foreground;
    }

    public int background() {
        return background;
    }

    /**
     * Builds a cursor from a source pixmap and a mask pixmap, as {@code CreateCursor} does.
     * Source bits are drawn black, mask-only bits white, matching the core protocol.
     */
    public static XCursor fromBitmaps(int id, ClientConnection owner, XPixmap source, XPixmap mask,
                                      int sourceX, int sourceY, int maskX, int maskY, int width,
                                      int height, int hotspotX, int hotspotY) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int src = source == null ? 0 : luminance(source.pixel(sourceX + x, sourceY + y));
                int msk = mask == null ? 0 : luminance(mask.pixel(maskX + x, maskY + y));
                pixels[y * width + x] = cursorPixel(src != 0, msk != 0);
            }
        }
        return new XCursor(id, owner, width, height, hotspotX, hotspotY, pixels, false);
    }

    /**
     * Builds a cursor from a font glyph, as {@code CreateGlyphCursor} does.
     */
    public static XCursor fromGlyph(int id, ClientConnection owner, XFont font, int sourceChar,
                                    int maskChar, int foreground, int background) {
        CursorFont.Glyph shape = CursorFont.glyph(sourceChar);
        if (shape != null) {
            // One of the cursor font's own shapes: the client asked for an arrow, an I-beam, a
            // resize handle. Its bitmap is the shape and the character after it is its mask, which
            // is what XCreateGlyphCursor composes: the source in the foreground over the mask in
            // the background. The font argument is not needed for this path at all - the glyphs are
            // in the table, because a client that asks for XC_left_ptr means the arrow that every X
            // server draws, not whatever this server's stand-in font has at that character.
            int[] pixels = new int[shape.width() * shape.height()];
            for (int y = 0; y < shape.height(); y++) {
                for (int x = 0; x < shape.width(); x++) {
                    boolean source = bit(shape.source(), shape.width(), x, y);
                    boolean mask = bit(shape.mask(), shape.width(), x, y);
                    pixels[y * shape.width() + x] = source && mask ? foreground
                            : mask ? background : 0;
                }
            }
            XCursor cursor = new XCursor(id, owner, shape.width(), shape.height(),
                    shape.hotspotX(), shape.hotspotY(), pixels, true);
            cursor.setColors(foreground, background);
            return cursor;
        }
        int width = Math.max(1, font.maxWidth());
        int height = Math.max(1, font.ascent() + font.descent());
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Without glyph outlines we approximate: the source char fills the cell.
                boolean inSource = x < font.charWidth(sourceChar);
                boolean inMask = maskChar == 0 || x < font.charWidth(maskChar);
                if (inSource && inMask) {
                    pixels[y * width + x] = foreground;
                } else if (inMask) {
                    pixels[y * width + x] = background;
                } else {
                    pixels[y * width + x] = 0;
                }
            }
        }
        XCursor cursor = new XCursor(id, owner, width, height, 0, 0, pixels, true);
        cursor.setColors(foreground, background);
        return cursor;
    }

    /** One bit of a packed cursor bitmap: a row is padded to whole bytes, most significant bit first. */
    private static boolean bit(byte[] rows, int width, int x, int y) {
        int stride = (width + 7) / 8;
        int index = y * stride + (x >> 3);
        if (index < 0 || index >= rows.length) {
            return false;
        }
        return ((rows[index] >> (7 - (x & 7))) & 1) != 0;
    }

    private static int cursorPixel(boolean source, boolean mask) {
        if (source && mask) {
            return 0xFF000000;
        }
        if (mask) {
            return 0xFFFFFFFF;
        }
        return 0;
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    /** The cursor every window and the root start with when nothing else is set. */
    public static XCursor defaultCursor(int id, ClientConnection owner) {
        int size = 16;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean set = (DEFAULT_CURSOR[y] & (1 << (15 - x))) != 0;
                pixels[y * size + x] = set ? 0xFF000000 : 0;
            }
        }
        return new XCursor(id, owner, size, size, 0, 0, pixels, false);
    }

    @Override
    public String typeName() {
        return "cursor";
    }
}
