package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.draw.PixelStore;

import java.awt.Rectangle;
import java.util.List;

/**
 * A RENDER picture: a drawable plus the state that decides how its pixels are read, written and
 * clipped.
 *
 * <p>A picture is to RENDER what a graphics context is to the core protocol, with one important
 * difference: a picture carries a {@link PictFormat}, so the same drawable can be read as opaque
 * colour or as alpha, and the format is what makes compositing meaningful. A picture over a window
 * draws into the screen framebuffer; a picture over a pixmap draws into the pixmap.
 *
 * <p>The methods here are the only way the rest of the server touches pixels, and they hide three
 * translations that are easy to get wrong:
 * <ul>
 *   <li>A window's coordinates are relative to its parent, but the pixels live in the screen
 *       framebuffer, so every access is moved by {@code window.absoluteX()/absoluteY()}. This is
 *       the same translation {@code DrawHandlers.Target} performs for the core protocol; skipping
 *       it makes every picture land at the wrong place.</li>
 *   <li>Values crossing this boundary are always non-premultiplied {@code 0xAARRGGBB}, whichever
 *       depth the drawable really is.</li>
 *   <li>A read has to honour {@code Repeat} and the picture's clip mask, because RENDER sources
 *       are sampled, not blitted.</li>
 * </ul>
 *
 * <h2>What is not enforced</h2>
 * <ul>
 *   <li>{@code SubwindowMode.IncludeInferiors} is recorded but not honoured. The server keeps one
 *       image per screen and children are painted into it in order, so there is no separate
 *       "contents of this window including its children" to read; a picture behaves as though the
 *       mode were {@code ClipByChildren}, which is the default and what toolkits ask for.</li>
 *   <li>Transforms are evaluated in double precision rather than in the 16.16 fixed point the
 *       protocol uses, so a scaled or rotated source can land a pixel away from where a server
 *       working in fixed point would put it.</li>
 * </ul>
 */
public final class Picture extends XResource {

    /** {@code Repeat}: pixels outside the drawable are transparent. */
    public static final int REPEAT_NONE = 0;
    /** {@code Repeat}: the drawable is tiled. */
    public static final int REPEAT_NORMAL = 1;
    /** {@code Repeat}: the nearest edge pixel is repeated. */
    public static final int REPEAT_PAD = 2;
    /** {@code Repeat}: the drawable is tiled with alternate tiles mirrored. */
    public static final int REPEAT_REFLECT = 3;

    /** {@code SubwindowMode}: clip by inferiors. The only mode this server distinguishes. */
    public static final int CLIP_BY_CHILDREN = 0;
    /** {@code SubwindowMode}: do not clip by inferiors. Recorded but not honoured. */
    public static final int INCLUDE_INFERIORS = 1;

    /** {@code PolyEdge}: polygon edges are one bit of coverage. */
    public static final int POLY_EDGE_SHARP = 0;
    /** {@code PolyEdge}: polygon edges carry fractional coverage. */
    public static final int POLY_EDGE_SMOOTH = 1;

    /** {@code PolyMode}: a regular sample grid is used for coverage. */
    public static final int POLY_MODE_PRECISE = 0;
    /** {@code PolyMode}: coverage is only required to satisfy the protocol's constraints. */
    public static final int POLY_MODE_IMPRECISE = 1;

    /**
     * Resource class shared by every picture, so the resource table reports {@code BadPicture}
     * rather than a drawable error when a wrong or stale id is used.
     */
    private static final int RESOURCE_CLASS = XResource.newExtensionClass();

    private final XResource drawable;
    private final PictFormat format;
    private final Screen screen;
    private final PixelStore store;

    /**
     * The origin as of the request being drawn.
     *
     * <p>A window's position in the framebuffer is not fixed: AWT creates a dialog at the origin of
     * the screen, maps it and only then moves it where it belongs, and every client that centres a
     * window does the same. A picture that kept the position it was created at addresses the wrong
     * pixels for the rest of its life - and because the clip is consulted through the same origin,
     * the writes do not land in the wrong place, they are refused altogether: a dialog that fills its
     * background through {@code ClearArea} but never draws a word of its contents. The origin is
     * therefore re-read per request by {@link #refreshClip()}, which is where the window's clip is
     * renewed for the same reason.
     */
    private int drawableX;
    private int drawableY;

    /** The window this picture draws on, or null when it draws on a pixmap. */
    private final XWindow window;

    private int repeat = REPEAT_NONE;
    private Picture alphaMap;
    private int alphaXOrigin;
    private int alphaYOrigin;
    private int clipXOrigin;
    private int clipYOrigin;
    private XPixmap clipMask;
    private List<Rectangle> clipRectangles;
    private boolean clipByRectangles;
    private int subwindowMode = CLIP_BY_CHILDREN;
    private int polyEdge = POLY_EDGE_SMOOTH;
    private int polyMode = POLY_MODE_PRECISE;
    private boolean componentAlpha;

    /** {@code null} while the transform is the identity, which is the overwhelmingly common case. */
    private Transform transform;
    private String filter = "nearest";
    private int[] filterValues = new int[0];

    /** Set by {@code CreateSolidFill}: the picture has no drawable content, only this colour. */
    private int solidArgb;
    private boolean solid;

    /** Set by the gradient requests: the colour is a function of position rather than a constant. */
    private Gradient gradient;

    /**
     * The parts of the window that are visible - its rectangle minus whatever is stacked above it -
     * cached because they are consulted once per pixel. {@link #refreshClip()} renews them at the
     * start of a drawing request; the lazy fallback means a picture that was never refreshed still
     * clips correctly rather than not at all.
     */
    private List<Rectangle> windowClip;

    public Picture(int id, ClientConnection owner, XResource drawable, PictFormat format,
                   Screen screen, PixelStore store, int originX, int originY, XWindow window) {
        super(id, RESOURCE_CLASS, owner);
        this.drawable = drawable;
        this.format = format;
        this.screen = screen;
        this.store = store;
        this.drawableX = originX;
        this.drawableY = originY;
        this.window = window;
    }

    // ------------------------------------------------------------------
    // Shape of the picture
    // ------------------------------------------------------------------

    public XResource drawable() {
        return drawable;
    }

    /** The drawable's origin inside the pixel store, in screen coordinates for a window. */
    public int originX() {
        return drawableX;
    }

    public int originY() {
        return drawableY;
    }

    public PictFormat format() {
        return format;
    }

    public Screen screen() {
        return screen;
    }

    /** The window this picture draws on, or null for a pixmap backed picture. */
    public XWindow window() {
        return window;
    }

    /**
     * The drawable's size. A solid fill or a gradient is anchored to the root window rather than to
     * a drawable of its own, which is why this asks the drawable itself and not {@link #window()}.
     */
    public int width() {
        return drawable instanceof XWindow window ? window.width() : ((XPixmap) drawable).width();
    }

    public int height() {
        return drawable instanceof XWindow window ? window.height() : ((XPixmap) drawable).height();
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    public int repeat() {
        return repeat;
    }

    public void setRepeat(int repeat) {
        this.repeat = repeat;
    }

    public Picture alphaMap() {
        return alphaMap;
    }

    public void setAlphaMap(Picture alphaMap) {
        this.alphaMap = alphaMap;
    }

    public int alphaXOrigin() {
        return alphaXOrigin;
    }

    public int alphaYOrigin() {
        return alphaYOrigin;
    }

    public void setAlphaOrigin(int x, int y) {
        this.alphaXOrigin = x;
        this.alphaYOrigin = y;
    }

    public int clipXOrigin() {
        return clipXOrigin;
    }

    public int clipYOrigin() {
        return clipYOrigin;
    }

    public void setClipOrigin(int x, int y) {
        this.clipXOrigin = x;
        this.clipYOrigin = y;
    }

    public XPixmap clipMask() {
        return clipMask;
    }

    public void setClipMask(XPixmap clipMask) {
        this.clipMask = clipMask;
        this.clipRectangles = null;
        this.clipByRectangles = false;
    }

    /** The clip rectangle list, or null when the clip mask is a pixmap or unset. */
    public List<Rectangle> clipRectangles() {
        return clipRectangles;
    }

    public void setClipRectangles(List<Rectangle> rectangles) {
        this.clipRectangles = List.copyOf(rectangles);
        this.clipByRectangles = true;
        this.clipMask = null;
    }

    public int subwindowMode() {
        return subwindowMode;
    }

    public void setSubwindowMode(int subwindowMode) {
        this.subwindowMode = subwindowMode;
    }

    public int polyEdge() {
        return polyEdge;
    }

    public void setPolyEdge(int polyEdge) {
        this.polyEdge = polyEdge;
    }

    public int polyMode() {
        return polyMode;
    }

    public void setPolyMode(int polyMode) {
        this.polyMode = polyMode;
    }

    public boolean componentAlpha() {
        return componentAlpha;
    }

    public void setComponentAlpha(boolean componentAlpha) {
        this.componentAlpha = componentAlpha;
    }

    public Transform transform() {
        return transform;
    }

    public void setTransform(Transform transform) {
        this.transform = transform;
    }

    public String filter() {
        return filter;
    }

    public int[] filterValues() {
        return filterValues;
    }

    public void setFilter(String filter, int[] values) {
        this.filter = filter;
        this.filterValues = values;
    }

    /** Makes this picture a constant source, as {@code CreateSolidFill} does. */
    public void setSolid(int argb) {
        this.solidArgb = argb;
        this.solid = true;
        this.gradient = null;
    }

    public boolean isSolid() {
        return solid;
    }

    public int solidArgb() {
        return solidArgb;
    }

    public void setGradient(Gradient gradient) {
        this.gradient = gradient;
        this.solid = false;
    }

    public Gradient gradient() {
        return gradient;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Non-premultiplied ARGB at a picture coordinate, honouring the clip mask, repeat mode and, for
     * a window, the part of it that is actually on screen. A point the picture has no pixel for
     * reads as transparent, which is what {@code Repeat.None} asks for.
     */
    public int pixelAt(int x, int y) {
        if (solid) {
            return solidArgb;
        }
        if (gradient != null) {
            return gradient.argbAt(x, y);
        }
        if (!withinClip(x, y)) {
            return 0;
        }
        return rawPixel(x, y);
    }

    /**
     * Samples the picture at a fractional coordinate using its filter. Only nearest and bilinear
     * are distinguished; every other name - including the {@code fast}/{@code good}/{@code best}
     * aliases - is resolved by {@code RenderExtension} before it reaches here.
     */
    public int sample(double x, double y) {
        if (!"bilinear".equals(filter)) {
            return pixelAt((int) Math.floor(x), (int) Math.floor(y));
        }
        double fx = x - 0.5;
        double fy = y - 0.5;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double ax = fx - x0;
        double ay = fy - y0;
        int c00 = pixelAt(x0, y0);
        int c10 = pixelAt(x0 + 1, y0);
        int c01 = pixelAt(x0, y0 + 1);
        int c11 = pixelAt(x0 + 1, y0 + 1);
        return mix(mix(c00, c10, ax), mix(c01, c11, ax), ay);
    }

    /** Linear blend of two non-premultiplied ARGB values. */
    private static int mix(int a, int b, double t) {
        if (t <= 0) {
            return a;
        }
        if (t >= 1) {
            return b;
        }
        int alpha = lerp((a >>> 24) & 0xFF, (b >>> 24) & 0xFF, t);
        int red = lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, t);
        int green = lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, t);
        int blue = lerp(a & 0xFF, b & 0xFF, t);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    /**
     * The stored pixel at a picture coordinate, tiled or clamped out of range according to the
     * repeat mode. The window rectangle and its clip are treated as the drawable's edge, so a
     * window behaves like a pixmap of its own size rather than like a hole into the screen.
     */
    private int rawPixel(int x, int y) {
        if (hasContent(x, y)) {
            return stored(x, y);
        }
        int width = width();
        int height = height();
        return switch (repeat) {
            case REPEAT_NORMAL -> repeated(Math.floorMod(x, width), Math.floorMod(y, height));
            case REPEAT_PAD -> repeated(clamp(x, width), clamp(y, height));
            case REPEAT_REFLECT -> repeated(mirror(x, width), mirror(y, height));
            default -> 0;
        };
    }

    private int repeated(int x, int y) {
        return hasContent(x, y) ? stored(x, y) : 0;
    }

    /**
     * A stored pixel as this picture's format means it.
     *
     * <p>Drawables hold their pixels as {@code 0xAARRGGBB}, which is the right reading for a format
     * with colour channels and the wrong one for a format that is nothing but coverage: an A8 mask's
     * pixel value <em>is</em> its alpha, so reading it as a colour channel with an opaque alpha
     * makes every pixel of the mask fully opaque - and everything a client composites through that
     * mask is drawn as a solid rectangle in the source's colour. A whole application's icons and
     * filled paths arrive this way; the glyph path already decodes through the format, which is why
     * text was unaffected.
     */
    private int stored(int x, int y) {
        int pixel = store.pixel(drawableX + x, drawableY + y);
        if (format.isAlphaOnly() || drawableDepth() == 8) {
            // A depth 8 drawable holds the byte its client sent, and the format is what says whether
            // that byte is coverage (alpha only, so the whole value is the alpha) or a colour in the
            // visual's layout (3-3-2, whose fields the format's masks carry). Reading it any other
            // way costs the antialiasing: see ImageCodec on why it is stored unconverted.
            return format.toArgb(pixel);
        }
        return pixel;
    }

    /** The depth of the drawable this picture is on, which says how its pixels are stored. */
    private int drawableDepth() {
        return drawable instanceof XWindow window ? window.depth() : ((XPixmap) drawable).depth();
    }

    /**
     * Renews the cached window clip and puts the picture where its window now is. Called once at the
     * start of each drawing request, while the window tree cannot change underneath it - the server
     * holds its lock for the whole request.
     *
     * <p>A window that moved since the last request moves its picture with it: the position is read
     * here rather than kept from the picture's creation, which is the only moment at which the two
     * are known to agree.
     */
    public void refreshClip() {
        if (window == null) {
            windowClip = null;
            return;
        }
        drawableX = window.absoluteX();
        drawableY = window.absoluteY();
        // A picture over a window reads that window's own pixels and stops where a child begins.
        // With IncludeInferiors the children *are* part of what the window shows, which is what a
        // client copying "the screen" through a parent window is asking for: without it a frame whose
        // content child covers it has no pixels to offer at all, and the copy comes back empty.
        // What is stacked *above* the window is excluded either way - those pixels are not its own.
        windowClip = subwindowMode == INCLUDE_INFERIORS ? window.coveredRegion()
                : window.visibleRegion();
    }

    /**
     * Whether the drawable has pixels this server can reach at a picture coordinate. For a window
     * that means inside its rectangle and not obscured: the contents of obscured pixels are
     * undefined, and reading them anyway would let a source picture sample a sibling's pixels - and
     * writing them would paint over the window on top.
     */
    private boolean hasContent(int x, int y) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) {
            return false;
        }
        if (window == null) {
            return true;
        }
        if (windowClip == null) {
            // Never refreshed: reading through the same call a request would have made keeps the
            // clip and the position consistent with each other.
            refreshClip();
        }
        return ShapeState.containsPoint(windowClip, drawableX + x, drawableY + y);
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static int mirror(int value, int size) {
        if (size == 1) {
            return 0;
        }
        int period = size * 2;
        int folded = Math.floorMod(value, period);
        return folded < size ? folded : period - 1 - folded;
    }

    /** Whether the picture's clip lets a pixel be read or written. */
    public boolean withinClip(int x, int y) {
        if (clipByRectangles) {
            for (Rectangle rectangle : clipRectangles) {
                if (x >= rectangle.x + clipXOrigin && x < rectangle.x + clipXOrigin + rectangle.width
                        && y >= rectangle.y + clipYOrigin
                        && y < rectangle.y + clipYOrigin + rectangle.height) {
                    return true;
                }
            }
            return false;
        }
        if (clipMask != null) {
            // The mask's origin sits at the clip origin, in picture coordinates.
            return (clipMask.pixel(x - clipXOrigin, y - clipYOrigin) & 0x00FFFFFF) != 0;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Whether a pixel can be written: inside the drawable, visible, and inside the clip. */
    public boolean isWritable(int x, int y) {
        return hasContent(x, y) && withinClip(x, y);
    }

    /** Writes a non-premultiplied ARGB pixel, ignoring the write when the clip forbids it. */
    public void writePixel(int x, int y, int argb) {
        if (!isWritable(x, y)) {
            return;
        }
        store.setPixel(drawableX + x, drawableY + y, argb);
    }

    @Override
    protected void onDestroy() {
        // A picture holds only references, so there is nothing to release; clearing them lets the
        // alpha map and clip mask be collected when their own clients are gone.
        alphaMap = null;
        clipMask = null;
        clipRectangles = null;
        gradient = null;
    }

    @Override
    public String typeName() {
        return "picture";
    }

    /**
     * A RENDER transform: the matrix that maps destination pixel coordinates back to source pixel
     * coordinates when a picture is used as a source or mask.
     *
     * <p>The nine values arrive as 16.16 fixed point, in the order
     * {@code matrix11 matrix12 matrix13 matrix21 matrix22 matrix23 matrix31 matrix32 matrix33},
     * and a point is carried through them as a row vector on the left of the matrix:
     *
     * <pre>
     *   xs = (m11*x + m12*y + m13) / (m31*x + m32*y + m33)
     *   ys = (m21*x + m22*y + m23) / (m31*x + m32*y + m33)
     * </pre>
     *
     * <p>The <em>third column</em> holds the translation and the <em>third row</em> the projective
     * terms, which is the opposite way round from the way the columns of a 3x3 matrix are usually
     * named here. The specification says only that "the transform maps from destination pixel
     * geometry back to the source pixel geometry"; what settles the layout is what clients send.
     * Every client that uses a transform - cairo above all - writes an affine map
     * {@code (x*xx + y*xy + x0, x*yx + y*yy + y0)} into these fields in the order they appear on
     * the wire, so {@code matrix13} and {@code matrix23} are the translations and {@code matrix31}
     * and {@code matrix32} are zero. Reading the translation out of the third row instead makes
     * every scaled or rotated composite sample the wrong place: a picture under a 2x scale lands a
     * long way outside its own drawable, reads as transparent everywhere, and paints nothing at
     * all - an entirely blank window, with no error anywhere.
     *
     * <p>{@code SetPictureTransform} has to invert the matrix to decide whether it is legal; the
     * determinant is unaffected by transposing, so {@link #isInvertible()} holds either way.
     */
    public record Transform(int m11, int m12, int m13, int m21, int m22, int m23,
                            int m31, int m32, int m33) {

        private static final int ONE = 1 << 16;

        /** The identity transform, which {@code null} stands for elsewhere in the class. */
        public static Transform identity() {
            return new Transform(ONE, 0, 0, 0, ONE, 0, 0, 0, ONE);
        }

        public boolean isIdentity() {
            return m11 == ONE && m22 == ONE && m33 == ONE
                    && m12 == 0 && m13 == 0 && m21 == 0 && m23 == 0 && m31 == 0 && m32 == 0;
        }

        public boolean isProjective() {
            return m31 != 0 || m32 != 0 || m33 != ONE;
        }

        /**
         * Whether the matrix can be inverted, which is what {@code SetPictureTransform} requires:
         * a singular matrix would make the source coordinate of a destination pixel undefined.
         */
        public boolean isInvertible() {
            long a = m11, b = m12, c = m13;
            long d = m21, e = m22, f = m23;
            long g = m31, h = m32, i = m33;
            long determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
            return determinant != 0;
        }

        /** Source x for a destination point. */
        public double mapX(double x, double y) {
            double denominator = isProjective() ? (m31 * x + m32 * y + m33) / (double) ONE : 1.0;
            if (denominator == 0) {
                return Double.NaN;
            }
            return ((m11 * x + m12 * y + m13) / (double) ONE) / denominator;
        }

        /** Source y for a destination point. */
        public double mapY(double x, double y) {
            double denominator = isProjective() ? (m31 * x + m32 * y + m33) / (double) ONE : 1.0;
            if (denominator == 0) {
                return Double.NaN;
            }
            return ((m21 * x + m22 * y + m23) / (double) ONE) / denominator;
        }
    }

    /**
     * A RENDER 0.10 gradient source. The gradients are evaluated in picture coordinates on demand
     * rather than being rendered into a picture of their own, which keeps a resized or scrolled
     * window correct without any cache to invalidate.
     */
    public static final class Gradient {

        /** A gradient interpolated along the line from (x1, y1) to (x2, y2). */
        public static final int LINEAR = 0;
        /** A gradient interpolated radially between two concentric circles. */
        public static final int RADIAL = 1;
        /** A gradient swept around a centre, starting at an angle. */
        public static final int CONICAL = 2;

        private final int kind;
        private final double x1;
        private final double y1;
        private final double x2;
        private final double y2;
        private final double radius1;
        private final double radius2;
        private final double angle;
        private final double[] stops;
        private final int[] colors;

        public Gradient(int kind, double x1, double y1, double x2, double y2, double radius1,
                        double radius2, double angle, double[] stops, int[] colors) {
            this.kind = kind;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.radius1 = radius1;
            this.radius2 = radius2;
            this.angle = angle;
            this.stops = stops;
            this.colors = colors;
        }

        public int kind() {
            return kind;
        }

        /** The non-premultiplied ARGB of the gradient at a picture coordinate. */
        public int argbAt(double x, double y) {
            double t = switch (kind) {
                case LINEAR -> linearT(x, y);
                case RADIAL -> radialT(x, y);
                case CONICAL -> conicalT(x, y);
                default -> 0;
            };
            return colorAt(t);
        }

        private double linearT(double x, double y) {
            double dx = x2 - x1;
            double dy = y2 - y1;
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared == 0) {
                return 0;
            }
            return ((x - x1) * dx + (y - y1) * dy) / lengthSquared;
        }

        private double radialT(double x, double y) {
            double distance = Math.hypot(x - x2, y - y2);
            double span = radius2 - radius1;
            return span == 0 ? 0 : (distance - radius1) / span;
        }

        private double conicalT(double x, double y) {
            double theta = Math.atan2(y - y1, x - x1) - angle;
            double turns = theta / (Math.PI * 2);
            // The sweep wraps, so -0.25 of a turn is the same point as 0.75 of one.
            return turns - Math.floor(turns);
        }

        /** Interpolates the stop list, clamping to the end colours outside it. */
        private int colorAt(double t) {
            if (colors.length == 0) {
                return 0;
            }
            if (t <= stops[0]) {
                return colors[0];
            }
            int last = colors.length - 1;
            if (t >= stops[last]) {
                return colors[last];
            }
            for (int i = 1; i <= last; i++) {
                if (t <= stops[i]) {
                    double span = stops[i] - stops[i - 1];
                    double f = span == 0 ? 0 : (t - stops[i - 1]) / span;
                    return mix(colors[i - 1], colors[i], f);
                }
            }
            return colors[last];
        }
    }
}
