package io.github.oyzh1994.xserver.draw;

import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.resource.XPixmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round trips images through {@link ImageCodec}.
 *
 * <p>These exist because a {@code GetImage} on a depth one drawable used to throw
 * {@code ArrayIndexOutOfBoundsException}: the encoder wrote one byte per pixel for a format that
 * packs one bit per pixel, so a request any client could make took the connection down with it.
 */
class ImageCodecTest {

    private static final VisualInfo GRAY1 = VisualInfo.staticGray1(1);
    private static final VisualInfo TRUE_COLOR = VisualInfo.trueColor24(0x200);

    private static XPixmap pixmap(int width, int height, int depth, VisualInfo visual) {
        return new XPixmap(1, null, null, width, height, depth, visual);
    }

    /** The width that overflowed: 33 pixels do not fit in the 8 bytes a row was given. */
    @Test
    void depthOneZPixmapDoesNotOverrunTheBuffer() {
        for (int width : new int[] {1, 7, 8, 9, 31, 32, 33, 63, 64, 65, 128, 512}) {
            for (int height : new int[] {1, 2, 8}) {
                XPixmap target = pixmap(width, height, 1, GRAY1);
                byte[] data = ImageCodec.getImage(target, 0, 0, width, height, ImageCodec.ZPIXMAP,
                        0xFFFFFFFF, 1, GRAY1, false);
                assertEquals(expectedScanlineBytes(width, 1) * height, data.length,
                        "depth 1 ZPixmap of " + width + "x" + height + " has the wrong length");
            }
        }
    }

    @Test
    void depthOnePixelsSurviveARoundTrip() {
        XPixmap source = pixmap(20, 3, 1, GRAY1);
        // A recognisable pattern, including the last pixel of a row so an off by one shows up.
        for (int x = 0; x < 20; x++) {
            source.setPixel(x, 0, x % 2 == 0 ? 0xFFFFFFFF : 0);
            source.setPixel(x, 2, x == 19 ? 0xFFFFFFFF : 0);
        }
        byte[] encoded = ImageCodec.getImage(source, 0, 0, 20, 3, ImageCodec.ZPIXMAP, 0xFFFFFFFF, 1,
                GRAY1, false);

        XPixmap target = pixmap(20, 3, 1, GRAY1);
        ImageCodec.putImage(target, plainGc(), 0, 0, 20, 3, ImageCodec.ZPIXMAP, 0, 1, encoded, 0,
                false);

        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 20; x++) {
                // Compare the colour, not the alpha: the encoder writes a pixel value and the
                // decoder turns it back into opaque black or white, so "black" comes back as
                // 0xFF000000 rather than the 0 the test stored.
                assertEquals((source.pixel(x, y) & 0xFFFFFF) != 0,
                        (target.pixel(x, y) & 0xFFFFFF) != 0,
                        "pixel (" + x + "," + y + ") changed across the round trip");
            }
        }
    }

    @Test
    void trueColorZPixmapRoundTripsExactly() {
        XPixmap source = pixmap(37, 5, 24, TRUE_COLOR);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 37; x++) {
                source.setPixel(x, y, 0xFF000000 | (x * 7 << 16) | (y * 31 << 8) | (x + y));
            }
        }
        byte[] encoded = ImageCodec.getImage(source, 0, 0, 37, 5, ImageCodec.ZPIXMAP, 0xFFFFFFFF,
                24, TRUE_COLOR, false);
        assertEquals(37 * 4 * 5, encoded.length);

        XPixmap target = pixmap(37, 5, 24, TRUE_COLOR);
        ImageCodec.putImage(target, plainGc(), 0, 0, 37, 5, ImageCodec.ZPIXMAP, 0, 24, encoded, 0,
                false);
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 37; x++) {
                assertEquals(source.pixel(x, y) & 0xFFFFFF, target.pixel(x, y) & 0xFFFFFF,
                        "pixel (" + x + "," + y + ") changed across the round trip");
            }
        }
    }

    @Test
    void xyBitmapRoundTrips() {
        XPixmap source = pixmap(20, 2, 1, GRAY1);
        source.setPixel(0, 0, 0xFFFFFFFF);
        source.setPixel(19, 1, 0xFFFFFFFF);
        byte[] encoded = ImageCodec.getImage(source, 0, 0, 20, 2, ImageCodec.XY_BITMAP,
                0xFFFFFFFF, 1, GRAY1, false);
        assertNotNull(encoded);

        XPixmap target = pixmap(20, 2, 1, GRAY1);
        ImageCodec.putImage(target, plainGc(), 0, 0, 20, 2, ImageCodec.XY_BITMAP, 0, 1, encoded, 0,
                false);
        assertEquals((source.pixel(0, 0) & 0xFFFFFF) != 0, (target.pixel(0, 0) & 0xFFFFFF) != 0);
        assertEquals((source.pixel(19, 1) & 0xFFFFFF) != 0, (target.pixel(19, 1) & 0xFFFFFF) != 0);
        assertEquals((source.pixel(1, 0) & 0xFFFFFF) != 0, (target.pixel(1, 0) & 0xFFFFFF) != 0);
    }

    @Test
    void getImageOutsideTheDrawableDoesNotThrow() {
        XPixmap target = pixmap(10, 10, 1, GRAY1);
        byte[] data = ImageCodec.getImage(target, 5, 5, 10, 10, ImageCodec.ZPIXMAP, 0xFFFFFFFF, 1,
                GRAY1, false);
        // A depth one row of ten pixels is four bytes, padded to the protocol's 32 bit scanline.
        assertEquals(4 * 10, data.length);
    }

    private static io.github.oyzh1994.xserver.resource.XGC plainGc() {
        var gc = new io.github.oyzh1994.xserver.resource.XGC(2, null, null);
        gc.setForegroundArgb(0xFF000000);
        gc.setBackgroundArgb(0xFFFFFFFF);
        return gc;
    }

    /** The scanline length the protocol specifies: rows padded to a multiple of four bytes. */
    private static int expectedScanlineBytes(int width, int bitsPerPixel) {
        int bits = width * bitsPerPixel;
        return (bits + 31) / 32 * 4;
    }

    @Test
    void encodesSomethingForEverySupportedFormat() {
        XPixmap target = pixmap(8, 8, 1, GRAY1);
        for (int format : new int[] {ImageCodec.XY_BITMAP, ImageCodec.XYPIXMAP,
                ImageCodec.ZPIXMAP}) {
            assertNotNull(ImageCodec.getImage(target, 0, 0, 8, 8, format, 0xFFFFFFFF, 1, GRAY1,
                    false));
        }
    }

}
