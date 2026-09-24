package io.github.oyzh1994.xserver.draw;

import java.awt.Rectangle;

/**
 * The pixel store behind one screen.
 *
 * <p>There is exactly one framebuffer per screen and every window draws straight into it through
 * its clip rectangle, which is how X keeps a single coherent image and how {@code Expose} events
 * stay meaningful: obscured content is simply not preserved.
 *
 * <p>Changes are tracked as a bounding box so the display backend can blit only what moved.
 */
public final class Framebuffer implements PixelStore {

    private int width;
    private int height;
    /**
     * The pixels, which change identity when the screen is resized - so a caller that keeps the
     * array between frames has to ask again after one (see {@link #resize}). Everything else about
     * this object, its identity included, survives a resize, which is what the backends and the
     * picture store depend on.
     */
    private int[] pixels;

    private boolean dirty;
    private int dirtyX0;
    private int dirtyY0;
    private int dirtyX1;
    private int dirtyY1;

    public Framebuffer(int width, int height, int fillArgb) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        java.util.Arrays.fill(this.pixels, fillArgb);
        markDirty(0, 0, width, height);
    }

    /**
     * Reallocates the pixels at a new size, keeping what was already drawn.
     *
     * <p>For the screen that a client asked to be bigger - the one case where the size of a screen
     * changes after clients have connected. The contents are copied into the new arrays rather than
     * the array being swapped for a new one everywhere: this object keeps its identity, which the
     * display backends and every picture over it hold on to, so the pixels are what moves.
     *
     * <p>Everything that caches {@link #pixels()} past the end of a frame has to take it again -
     * which is why the backends are told through {@code DisplayBackend.screenResized} rather than
     * being expected to notice.
     *
     * @param fillArgb what the area the old screen did not cover is painted with, which is the
     *     screen's own background: a client that had a window there is told about the change and
     *     draws into it, and until then it is the desktop
     */
    public void resize(int width, int height, int fillArgb) {
        if (width == this.width && height == this.height) {
            return;
        }
        int[] grown = new int[width * height];
        java.util.Arrays.fill(grown, opaque(fillArgb));
        int keepWidth = Math.min(width, this.width);
        int keepHeight = Math.min(height, this.height);
        for (int row = 0; row < keepHeight; row++) {
            System.arraycopy(pixels, row * this.width, grown, row * width, keepWidth);
        }
        this.pixels = grown;
        this.width = width;
        this.height = height;
        markAllDirty();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int[] pixels() {
        return pixels;
    }

    @Override
    public int stride() {
        return width;
    }

    @Override
    public int pixel(int x, int y) {
        if (!contains(x, y)) {
            return 0;
        }
        return pixels[y * width + x];
    }

    @Override
    public void setPixel(int x, int y, int argb) {
        if (!contains(x, y)) {
            return;
        }
        pixels[y * width + x] = opaque(argb);
        markDirty(x, y, x + 1, y + 1);
    }

    /**
     * Forces a pixel opaque.
     *
     * <p>A screen has no alpha: X composites everything onto an opaque root, and the raster
     * operations can produce any 32 bit value - {@code GXclear} yields zero outright, and
     * {@code GXinvert} turns white into transparent black. A display backend that honours alpha
     * (JavaFX uploads premultiplied) then draws those pixels as holes, so a client that paints
     * perfectly correct black on white sees nothing. The screen stores colour only.
     */
    private static int opaque(int argb) {
        return 0xFF000000 | (argb & 0x00FFFFFF);
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        int colour = opaque(argb);
        for (int row = y0; row < y1; row++) {
            java.util.Arrays.fill(pixels, row * width + x0, row * width + x1, colour);
        }
        markDirty(x0, y0, x1, y1);
    }

    @Override
    public void copyFrom(PixelStore src, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        // Work out the visible span once, then copy row by row with overlap handling.
        int dx0 = Math.max(0, dstX);
        int dy0 = Math.max(0, dstY);
        int dx1 = Math.min(width, dstX + w);
        int dy1 = Math.min(height, dstY + h);
        if (dx0 >= dx1 || dy0 >= dy1) {
            return;
        }
        boolean sameSurface = src == this;
        boolean forward = !sameSurface || srcY >= dstY;
        int startRow = forward ? dy0 : dy1 - 1;
        int endRow = forward ? dy1 : dy0 - 1;
        int step = forward ? 1 : -1;
        for (int dy = startRow; dy != endRow; dy += step) {
            int sy = srcY + (dy - dstY);
            if (sy < 0 || sy >= src.height()) {
                continue;
            }
            for (int dx = dx0; dx < dx1; dx++) {
                int sx = srcX + (dx - dstX);
                if (sx < 0 || sx >= src.width()) {
                    continue;
                }
                setPixel(dx, dy, src.pixel(sx, sy));
            }
        }
        markDirty(dx0, dy0, dx1, dy1);
    }

    /** Marks a rectangle as needing to reach the screen. */
    public void markDirty(int x0, int y0, int x1, int y1) {
        if (!dirty) {
            dirty = true;
            dirtyX0 = x0;
            dirtyY0 = y0;
            dirtyX1 = x1;
            dirtyY1 = y1;
            return;
        }
        dirtyX0 = Math.min(dirtyX0, x0);
        dirtyY0 = Math.min(dirtyY0, y0);
        dirtyX1 = Math.max(dirtyX1, x1);
        dirtyY1 = Math.max(dirtyY1, y1);
    }

    public void markDirty(Rectangle rect) {
        markDirty(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height);
    }

    public void markAllDirty() {
        markDirty(0, 0, width, height);
    }

    /**
     * Returns the accumulated damage and resets it, or {@code null} when nothing changed since the
     * last call.
     */
    public Rectangle takeDamage() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        int x0 = Math.max(0, dirtyX0);
        int y0 = Math.max(0, dirtyY0);
        int x1 = Math.min(width, dirtyX1);
        int y1 = Math.min(height, dirtyY1);
        if (x0 >= x1 || y0 >= y1) {
            return null;
        }
        return new Rectangle(x0, y0, x1 - x0, y1 - y0);
    }
}
