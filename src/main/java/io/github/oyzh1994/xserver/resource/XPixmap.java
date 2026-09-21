package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import io.github.oyzh1994.xserver.draw.PixelStore;

/**
 * An off screen drawable. Pixmaps own their pixels; they have no position on the screen and
 * receive no events.
 */
public final class XPixmap extends XResource implements PixelStore {

    private final Screen screen;
    private final int width;
    private final int height;
    private final int depth;
    private final VisualInfo visual;
    private final int[] pixels;

    public XPixmap(int id, ClientConnection owner, Screen screen, int width, int height, int depth,
                   VisualInfo visual) {
        super(id, CLASS_PIXMAP, owner);
        this.screen = screen;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.depth = depth;
        this.visual = visual;
        this.pixels = new int[this.width * this.height];
    }

    public Screen screen() {
        return screen;
    }

    public int depth() {
        return depth;
    }

    public VisualInfo visual() {
        return visual;
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
        pixels[y * width + x] = argb;
    }

    @Override
    public String typeName() {
        return "pixmap";
    }
}
