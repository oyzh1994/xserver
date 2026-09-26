package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.display.VisualInfo;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * A window: a rectangular region of the screen that draws into the screen framebuffer and
 * receives events.
 *
 * <p>Children are stored back to front, so index 0 is the bottom-most child and the last element
 * is on top. That matches the X stacking order and makes obscuring and expose generation a matter
 * of walking the list.
 */
public final class XWindow extends XResource {

    // window classes
    public static final int COPY_FROM_PARENT = 0;
    public static final int INPUT_OUTPUT = 1;
    public static final int INPUT_ONLY = 2;

    // map states
    public static final int IS_UNMAPPED = 0;
    public static final int IS_UNVIEWABLE = 1;
    public static final int IS_VIEWABLE = 2;

    // stacking modes
    public static final int ABOVE = 0;
    public static final int BELOW = 1;
    public static final int TOP_IF = 2;
    public static final int BOTTOM_IF = 3;
    public static final int OPPOSITE = 4;

    private final Screen screen;
    private final int depth;
    private final int windowClass;
    private final VisualInfo visual;

    private XWindow parent;
    private final List<XWindow> children = new ArrayList<>();

    private int x;
    private int y;
    private int width;
    private int height;
    private int borderWidth;

    private boolean mapped;
    private boolean overrideRedirect;
    private boolean saveUnder;
    private int backingStore;
    private int backingPlanes = 0xFFFFFFFF;
    private int backingPixel;

    /**
     * The window's own pixels, kept while something covers it, for a client that asked for a backing
     * store. Null until the first time it is needed, and only ever allocated for a window whose
     * {@code backing_store} attribute asks for one: the memory is the client's decision, not
     * something this server spends on every window of every client.
     *
     * <p>What is kept is the window's <em>visible</em> pixels at the moment it is covered, which is
     * everything it had drawn: this server clips every drawing request to what shows, so an area
     * nothing can see holds nothing to keep. That is also the gap - a client that draws while it is
     * covered is clipped away as it always was, and {@link #forgetBacking} is what makes the reveal
     * fall back to an {@code Expose} rather than putting stale pixels back.
     */
    private Framebuffer backing;
    /** The parts of the screen {@link #backing} holds, in screen coordinates. Empty when none. */
    private List<Rectangle> backingSaved = List.of();

    /**
     * The two gravities, whose default is X's own: {@code NorthWest} (1) for both. It matters that
     * the default is measured rather than assumed - {@code bitGravity} was stored as 0, which is
     * {@code Forget}, and a window resized under {@code Forget} **throws its contents away**: every
     * window in the server would have lost its pixels on its first resize. Measured against a
     * reference server 2026-09-26 with {@code GetWindowAttributes} on a freshly created window:
     * {@code bit_gravity} answers 1 there and answered 0 here.
     */
    private int bitGravity = 1;
    /** {@code winGravity}'s default is the same number; see {@link #bitGravity}. */
    private int winGravity = 1;

    /** Background: either a pixel or a pixmap; {@code backgroundIsPixmap} says which. */
    /**
     * The colours a window starts with: the screen showing through where it has nothing of its own,
     * and a border a shade darker so the window has an edge. A client overrides either by naming a
     * background or border in its {@code CreateWindow} or {@code ChangeWindowAttributes}.
     */
    private int backgroundPixel = Screen.DEFAULT_BACKGROUND_ARGB;
    private XResource backgroundPixmap;
    private boolean backgroundIsPixmap;

    private int borderPixel = Screen.DEFAULT_BORDER_ARGB;
    private XResource borderPixmap;
    private boolean borderIsPixmap;

    private XColormap colormap;
    private XCursor cursor;
    /** Outstanding {@code XFixesHideCursor} requests, see {@link #cursorHideCount()}. */
    private int cursorHideCount;

    /** Properties, keyed by the atom naming them. */
    private final java.util.Map<Integer, XProperty> properties = new java.util.HashMap<>();

    /** This client's own event mask. */
    private int eventMask;
    private int doNotPropagateMask;
    /** Event masks other clients asked for on this window. */
    private final List<OtherClient> otherClients = new ArrayList<>();

    /** True when the window has never been drawn, so a map must produce exposes. */
    private boolean needsFullExpose = true;

    /**
     * The window's bounding shape as rectangles in window coordinates, or null when it has none.
     *
     * <p>Kept here rather than looked up in the shape extension because the region code runs once per
     * drawing request and has no server to ask; the extension hands the region over whenever the
     * shape changes. A bounding shape <em>removes</em> pixels from the window - its corners are not
     * part of it - so it is a subtraction from {@link #visibleRegion()}, not a clip on top of it, and
     * it applies to the children too: a child outside its parent's shape is not on screen either.
     */
    private List<Rectangle> boundingShape;

    /** The window's bounding shape in window coordinates, or null when it has none. */
    public List<Rectangle> boundingShape() {
        return boundingShape;
    }

    public void setBoundingShape(List<Rectangle> shape) {
        this.boundingShape = shape == null || shape.isEmpty() ? null : List.copyOf(shape);
    }

    private boolean redirectToParent;

    public XWindow(int id, ClientConnection owner, Screen screen, XWindow parent, int depth,
                   int windowClass, VisualInfo visual, int x, int y, int width, int height,
                   int borderWidth) {
        super(id, CLASS_WINDOW, owner);
        this.screen = screen;
        this.parent = parent;
        this.depth = depth;
        this.windowClass = windowClass;
        this.visual = visual;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.borderWidth = borderWidth;
        if (parent != null) {
            parent.children.add(this);
        }
    }

    public Screen screen() {
        return screen;
    }

    public int depth() {
        return depth;
    }

    public int windowClass() {
        return windowClass;
    }

    public VisualInfo visual() {
        return visual;
    }

    public boolean isInputOnly() {
        return windowClass == INPUT_ONLY;
    }

    public XWindow parent() {
        return parent;
    }

    public void setParent(XWindow parent) {
        this.parent = parent;
    }

    public List<XWindow> children() {
        return children;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int borderWidth() {
        return borderWidth;
    }

    public void setSize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.needsFullExpose = true;
    }

    public void setBorderWidth(int borderWidth) {
        this.borderWidth = Math.max(0, borderWidth);
    }

    public boolean isMapped() {
        return mapped;
    }

    public void setMapped(boolean mapped) {
        this.mapped = mapped;
    }

    public boolean isOverrideRedirect() {
        return overrideRedirect;
    }

    public void setOverrideRedirect(boolean overrideRedirect) {
        this.overrideRedirect = overrideRedirect;
    }

    public boolean isSaveUnder() {
        return saveUnder;
    }

    public void setSaveUnder(boolean saveUnder) {
        this.saveUnder = saveUnder;
    }

    public int backingStore() {
        return backingStore;
    }

    public void setBackingStore(int backingStore) {
        this.backingStore = backingStore;
    }

    public int backingPlanes() {
        return backingPlanes;
    }

    public void setBackingPlanes(int backingPlanes) {
        this.backingPlanes = backingPlanes;
    }

    public int backingPixel() {
        return backingPixel;
    }

    public void setBackingPixel(int backingPixel) {
        this.backingPixel = backingPixel;
    }

    /**
     * Whether the client asked for this window's contents to be kept while something covers it -
     * {@code backing_store} of {@code WhenMapped} or {@code Always}.
     */
    public boolean keepsBackingStore() {
        return backingStore != 0 && !isInputOnly();
    }

    /**
     * Saves what the window shows now, so that uncovering it needs no {@code Expose}.
     *
     * <p>Called <em>before</em> the window is covered, never after: what is under another window at
     * that moment is not this window's pixels. The parts saved accumulate, because a window can be
     * covered from several directions, and a part captured twice is captured fresh the second time -
     * the client may have drawn into it in between.
     */
    public void captureBacking(List<Rectangle> aboutToBeCovered) {
        // What shows now, plus whatever of the window's own content is inside the area the covering
        // window is about to paint - which its visible region no longer has, because the window
        // doing the covering is already above it. Both are still this window's pixels at this
        // moment, and neither is after the paint.
        List<Rectangle> visible = ShapeState.union(visibleRegion(),
                ShapeState.intersect(contentRegion(), aboutToBeCovered));
        if (visible.isEmpty()) {
            return;
        }
        Framebuffer screenPixels = screen().framebuffer();
        int originX = absoluteX();
        int originY = absoluteY();
        for (Rectangle part : visible) {
            buffer().copyFrom(screenPixels, part.x, part.y, part.x - originX, part.y - originY,
                    part.width, part.height);
        }
        backingSaved = ShapeState.union(backingSaved, visible);
    }

    /**
     * Puts back the pixels the window had where {@code area} uncovers it, and answers which parts of
     * {@code area} came back - the rest has to be painted and exposed the ordinary way.
     *
     * <p>Nothing comes back when the window has no store, has never been captured, or has drawn since
     * - see {@link #forgetBacking}, which the drawing paths call.
     */
    public List<Rectangle> restoreBacking(Rectangle area) {
        if (backing == null || backingSaved.isEmpty()) {
            return List.of();
        }
        List<Rectangle> restored = ShapeState.intersect(List.of(area), backingSaved);
        if (restored.isEmpty()) {
            return List.of();
        }
        Framebuffer screenPixels = screen().framebuffer();
        int originX = absoluteX();
        int originY = absoluteY();
        for (Rectangle part : restored) {
            screenPixels.copyFrom(backing, part.x - originX, part.y - originY, part.x, part.y,
                    part.width, part.height);
        }
        return restored;
    }

    /**
     * Throws the saved pixels away, so the next reveal paints and exposes instead of restoring.
     *
     * <p>Three things call it, and each is a way the saved pixels stop being what the window shows:
     * the client drew something (which is clipped to the visible part and so is not in the store),
     * the window moved or was resized (the store is the window's contents, and this server does not
     * move them), and the client turned the backing store off.
     */
    public void forgetBacking() {
        backingSaved = List.of();
        backing = null;
    }

    /** The store, allocated on the first capture and re-made when the window's size changes. */
    private Framebuffer buffer() {
        if (backing == null || backing.width() != width() || backing.height() != height()) {
            backing = new Framebuffer(width(), height(), 0);
            backingSaved = List.of();
        }
        return backing;
    }

    public int bitGravity() {
        return bitGravity;
    }

    public void setBitGravity(int bitGravity) {
        this.bitGravity = bitGravity;
    }

    public int winGravity() {
        return winGravity;
    }

    public void setWinGravity(int winGravity) {
        this.winGravity = winGravity;
    }

    public boolean isBackgroundPixmap() {
        return backgroundIsPixmap;
    }

    public void setBackgroundPixmap(XResource pixmap) {
        this.backgroundPixmap = pixmap;
        this.backgroundIsPixmap = true;
    }

    /** Takes a pixel value in this window's visual; stored converted to ARGB. */
    public void setBackgroundPixel(int pixel) {
        this.backgroundPixel = toArgb(pixel);
        this.backgroundIsPixmap = false;
    }

    public void setBackgroundArgb(int argb) {
        this.backgroundPixel = argb;
        this.backgroundIsPixmap = false;
    }

    /**
     * Converts a protocol pixel value to ARGB. Indexed visuals go through their colormap; the
     * default colormap is used until the window has one of its own, which matches the order in
     * which {@code CreateWindow} and {@code ChangeWindowAttributes} are applied.
     */
    private int toArgb(int pixel) {
        if (visual != null && visual.isTrueColor()) {
            return visual.toArgb(pixel);
        }
        XColormap map = colormap != null ? colormap : screen.defaultColormap();
        if (map != null) {
            return map.colorAt(pixel);
        }
        return 0xFF000000 | pixel;
    }

    public XResource backgroundPixmap() {
        return backgroundPixmap;
    }

    public int backgroundPixel() {
        return backgroundPixel;
    }

    public boolean isBorderPixmap() {
        return borderIsPixmap;
    }

    public void setBorderPixmap(XResource pixmap) {
        this.borderPixmap = pixmap;
        this.borderIsPixmap = true;
    }

    /** Takes a pixel value in this window's visual; stored converted to ARGB. */
    public void setBorderPixel(int pixel) {
        this.borderPixel = toArgb(pixel);
        this.borderIsPixmap = false;
    }

    public void setBorderArgb(int argb) {
        this.borderPixel = argb;
        this.borderIsPixmap = false;
    }

    public XResource borderPixmap() {
        return borderPixmap;
    }

    public int borderPixel() {
        return borderPixel;
    }

    public XColormap colormap() {
        return colormap;
    }

    public void setColormap(XColormap colormap) {
        this.colormap = colormap;
    }

    /** Properties of this window, keyed by the atom that names them. */
    public java.util.Map<Integer, XProperty> properties() {
        return properties;
    }

    public XCursor cursor() {
        return cursor;
    }

    public void setCursor(XCursor cursor) {
        this.cursor = cursor;
    }

    /**
     * How many outstanding {@code XFixesHideCursor} requests this window has - the pointer sprite is
     * not drawn while it is over this window or one of its descendants, and {@code XFixesShowCursor}
     * takes one back off.
     *
     * <p>Per window and counted, which is what the extension defines: the requests pair up, a
     * window that asked twice needs two shows, and the cursor is hidden <em>in that window</em>
     * rather than everywhere - a video player hiding the pointer must not hide it over the rest of
     * the screen. The count lives on the window rather than in the extension because that is its
     * lifetime: a window that goes away takes its hidden cursor with it.
     */
    public int cursorHideCount() {
        return cursorHideCount;
    }

    /** {@code XFixesHideCursor}: one more hide, and a negative count is not a state to have. */
    public void hideCursor() {
        cursorHideCount++;
    }

    /** {@code XFixesShowCursor}: one fewer hide, never below zero. */
    public void showCursor() {
        if (cursorHideCount > 0) {
            cursorHideCount--;
        }
    }

    public int eventMask() {
        return eventMask;
    }

    public void setEventMask(int eventMask) {
        this.eventMask = eventMask;
    }

    public int doNotPropagateMask() {
        return doNotPropagateMask;
    }

    public void setDoNotPropagateMask(int mask) {
        this.doNotPropagateMask = mask;
    }

    public List<OtherClient> otherClients() {
        return otherClients;
    }

    public boolean isRedirectToParent() {
        return redirectToParent;
    }

    public void setRedirectToParent(boolean redirectToParent) {
        this.redirectToParent = redirectToParent;
    }

    /** The root window this window belongs to. */
    public XWindow root() {
        XWindow window = this;
        while (window.parent != null) {
            window = window.parent;
        }
        return window;
    }

    public boolean isRoot() {
        return parent == null;
    }

    /**
     * Position of the window's origin - the top left of its contents, inside the border - in screen
     * coordinates.
     *
     * <p>{@code x} and {@code y} locate the window's <em>outer</em> corner on the wire, so they
     * point at the outside of its own border and the contents start one border further in. A
     * child's position is likewise measured from its parent's outer corner, so each step towards
     * the root adds that ancestor's own border as well. Leaving either border out of the sum puts
     * every window that has one - which is most toolkits' toplevels and everything xterm creates -
     * that many pixels up and to the left of where the client asked for it.
     */
    public int absoluteX() {
        int result = x + borderWidth;
        for (XWindow window = parent; window != null; window = window.parent) {
            result += window.x + window.borderWidth;
        }
        return result;
    }

    public int absoluteY() {
        int result = y + borderWidth;
        for (XWindow window = parent; window != null; window = window.parent) {
            result += window.y + window.borderWidth;
        }
        return result;
    }

    /** Inner rectangle in screen coordinates, excluding the border. */
    public Rectangle absoluteBounds() {
        return new Rectangle(absoluteX(), absoluteY(), width, height);
    }

    /** Rectangle including the border, in screen coordinates. */
    public Rectangle absoluteBoundsWithBorder() {
        int bw = borderWidth;
        return new Rectangle(absoluteX() - bw, absoluteY() - bw, width + 2 * bw, height + 2 * bw);
    }

    /**
     * The part of this window that can currently be drawn: its own rectangle intersected with
     * every ancestor's inner rectangle and the screen.
     */
    public Rectangle clipRect() {
        Rectangle clip = absoluteBounds();
        for (XWindow ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            clip = clip.intersection(ancestor.absoluteBounds());
            if (clip.isEmpty()) {
                return clip;
            }
        }
        return clip.intersection(new Rectangle(0, 0, screen.width(), screen.height()));
    }

    /**
     * The area the window's border may occupy: its own rectangle including the border, clipped by
     * its ancestors and the screen.
     *
     * <p>This differs from {@link #clipRect()} precisely in not intersecting the window's own inner
     * rectangle, which would clip the border away entirely - a window's border lives outside its
     * content by definition.
     */
    public Rectangle outerClipRect() {
        Rectangle clip = absoluteBoundsWithBorder();
        for (XWindow ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
            clip = clip.intersection(ancestor.absoluteBounds());
            if (clip.isEmpty()) {
                return clip;
            }
        }
        return clip.intersection(new Rectangle(0, 0, screen.width(), screen.height()));
    }

    /**
     * The part of this window that is really visible: {@link #clipRect()} minus everything stacked
     * above it.
     *
     * <p>{@code clipRect()} stops at the ancestors, so a window that is covered by a sibling still
     * reports the covered part as its own. Drawing into it there would paint over the window on top,
     * and the covered window would never learn to draw again - which is what makes a dialog
     * disappear the moment the window underneath it repaints. This is the region drawing is clipped
     * to, and the region a revealed window has to be told to repaint.
     *
     * <p>Only the rectangles of the windows above are subtracted, never their subtrees: a child is
     * clipped by its parent, so a covering sibling's own rectangle already covers everything it can
     * put on screen. The result is a list of non-overlapping rectangles in screen coordinates.
     */
    public List<Rectangle> visibleRegion() {
        return visibleRegion(clipRect());
    }

    /** {@link #visibleRegion()} for the border, which lives outside the window's inner rectangle. */
    public List<Rectangle> outerVisibleRegion() {
        return visibleRegion(outerClipRect());
    }

    /**
     * Everything this window puts on the screen: its own rectangle and border, its children, minus
     * whatever is stacked above it.
     *
     * <p>{@link #visibleRegion()} is the window's <em>own</em> pixels and stops where a child
     * begins; this is the area it is seen in, and the difference matters for the shape a toolkit
     * gives every toplevel and dialog: a frame whose content window covers it completely. Such a
     * frame has no visible region of its own at all, so an unmap that gives back "what the window
     * showed" computed that way gives back nothing - and the dialog's picture stays on the screen
     * after its window has gone. A moved or resized window is the same story.
     */
    public List<Rectangle> coveredRegion() {
        return visibleRegion(outerClipRect(), true, false);
    }

    private List<Rectangle> visibleRegion(Rectangle clip) {
        return visibleRegion(clip, false, false);
    }

    /**
     * The window's own pixels: its content rectangle minus its children, and <em>not</em> minus
     * whatever is stacked above it.
     *
     * <p>This is the region the client's drawing lands in, which is what a backing store saves. The
     * difference from {@link #visibleRegion()} matters at exactly one moment: when a window is being
     * covered, the covering window is already above it, so its visible region no longer contains the
     * pixels that are about to be painted over - and those are the ones worth keeping.
     */
    public List<Rectangle> contentRegion() {
        return visibleRegion(clipRect(), false, true);
    }

    /**
     * @param includeChildren true for the area the window covers rather than the pixels it owns:
     *                        its children are painted inside it, so they are part of what it shows
     *                        and must be given back when it goes away, while they are not part of
     *                        its own drawing surface - which stops at the child's rectangle.
     */
    private List<Rectangle> visibleRegion(Rectangle clip, boolean includeChildren,
                                          boolean ignoreObscuring) {
        if (clip.isEmpty()) {
            return List.of();
        }
        List<Rectangle> region = List.of(clip);
        // Walk this window and then each ancestor: at every level, the siblings stacked above the
        // one we came through are the ones that can cover it - and this window's own children,
        // which are drawn inside it, are above its own content just as surely.
        for (XWindow window = this; window != null && !region.isEmpty(); window = window.parent()) {
            if (window.boundingShape() != null) {
                // The shape takes pixels away, at every level: the window's own corners are not
                // part of it, and neither is the part of a child that falls outside its parent's.
                region = ShapeState.intersect(region, ShapeState.translate(window.boundingShape(),
                        window.absoluteX(), window.absoluteY()));
                if (region.isEmpty()) {
                    return region;
                }
            }
            if (window == this && !includeChildren) {
                region = subtractMappedChildren(this, region);
            }
            XWindow parent = window.parent();
            if (parent == null) {
                break;
            }
            if (ignoreObscuring) {
                continue;
            }
            List<XWindow> siblings = parent.children();
            for (int i = siblings.indexOf(window) + 1; i < siblings.size(); i++) {
                region = subtractWindow(region, siblings.get(i));
                if (region.isEmpty()) {
                    return region;
                }
            }
        }
        return region;
    }

    /** A window's children are drawn inside it, so they take their rectangles out of its own. */
    private static List<Rectangle> subtractMappedChildren(XWindow window, List<Rectangle> region) {
        for (XWindow child : window.children()) {
            region = subtractWindow(region, child);
            if (region.isEmpty()) {
                return region;
            }
        }
        return region;
    }

    private static List<Rectangle> subtractWindow(List<Rectangle> region, XWindow above) {
        if (!above.isMapped() || above.isInputOnly()) {
            return region;
        }
        // Only the child's own rectangle: a child is clipped by its parent, so its whole subtree is
        // inside it and needs no separate subtraction - except where a bounding shape takes part of
        // that rectangle away, because what the window covers is then its shape and the corners it
        // no longer has are the screen underneath's again. A window whose corners kept being
        // subtracted would never be asked to paint them, and they would stay whatever the covered
        // window left there.
        List<Rectangle> shape = above.boundingShape();
        return ShapeState.subtract(region, shape == null
                ? List.of(above.absoluteBoundsWithBorder())
                : ShapeState.translate(shape, above.absoluteX(), above.absoluteY()));
    }

    /**
     * Whether this window is stacked above {@code other}, which decides whose pixels are really on
     * screen where the two overlap.
     *
     * <p>Windows in different trees are unrelated: the root is above nothing.
     */
    public boolean isAbove(XWindow other) {
        if (other == null || other == this) {
            return false;
        }
        if (this.isAncestorOf(other)) {
            // A child is drawn inside and therefore on top of its parent.
            return false;
        }
        if (other.isAncestorOf(this)) {
            return true;
        }
        // Unrelated branches: the first common ancestor is where the two chains meet, and the
        // sibling that comes later in its children list is the one on top.
        for (XWindow mine = this; mine != null; mine = mine.parent()) {
            XWindow parent = mine.parent();
            if (parent == null) {
                return false;
            }
            List<XWindow> siblings = parent.children();
            for (XWindow theirs = other; theirs != null; theirs = theirs.parent()) {
                if (theirs.parent() == parent) {
                    return siblings.indexOf(mine) > siblings.indexOf(theirs);
                }
            }
        }
        return false;
    }

    /** True when the window and all of its ancestors are mapped. */
    public boolean isViewable() {
        if (!mapped) {
            return false;
        }
        for (XWindow window = parent; window != null; window = window.parent) {
            if (!window.mapped) {
                return false;
            }
        }
        return true;
    }

    public int mapState() {
        if (!mapped) {
            return IS_UNMAPPED;
        }
        return isViewable() ? IS_VIEWABLE : IS_UNVIEWABLE;
    }

    public boolean isAncestorOf(XWindow other) {
        for (XWindow window = other; window != null; window = window.parent) {
            if (window == this) {
                return true;
            }
        }
        return false;
    }

    /** Finds the child of this window that contains the given screen point, or null. */
    public XWindow childAt(int screenX, int screenY) {
        for (int i = children.size() - 1; i >= 0; i--) {
            XWindow child = children.get(i);
            if (!child.mapped) {
                continue;
            }
            if (child.absoluteBoundsWithBorder().contains(screenX, screenY)) {
                return child;
            }
        }
        return null;
    }

    /** Deepest mapped descendant containing the point, or this window. */
    public XWindow windowAt(int screenX, int screenY) {
        XWindow window = this;
        while (true) {
            XWindow child = window.childAt(screenX, screenY);
            if (child == null) {
                return window;
            }
            window = child;
        }
    }

    /** Every mask any client has selected on this window, used for {@code GetWindowAttributes}. */
    public int allEventMasks() {
        int mask = eventMask;
        for (OtherClient other : otherClients) {
            mask |= other.mask;
        }
        return mask;
    }

    /** The mask {@code client} has selected on this window, or 0 if it has none. */
    public int eventMaskFor(ClientConnection client) {
        if (owner() == client) {
            return eventMask;
        }
        for (OtherClient other : otherClients) {
            if (other.client == client) {
                return other.mask;
            }
        }
        return 0;
    }

    /** True when any client (including non-owners) selected any bit of {@code mask}. */
    public boolean anyClientSelected(int mask) {
        return (allEventMasks() & mask) != 0;
    }

    /**
     * Registers, updates or removes a non-owning client's event mask. A zero mask removes the
     * registration, which is what {@code ChangeWindowAttributes} does when a client clears it.
     */
    public void setOtherClientMask(ClientConnection client, int mask) {
        for (int i = 0; i < otherClients.size(); i++) {
            OtherClient other = otherClients.get(i);
            if (other.client == client) {
                if (mask == 0) {
                    otherClients.remove(i);
                } else {
                    other.mask = mask;
                }
                return;
            }
        }
        if (mask != 0) {
            otherClients.add(new OtherClient(client, mask));
        }
    }

    public boolean takeNeedsFullExpose() {
        boolean result = needsFullExpose;
        needsFullExpose = false;
        return result;
    }

    public void markNeedsFullExpose() {
        needsFullExpose = true;
    }

    /** Validates that {@code id} names one of our children before reparenting. */
    public void checkNotSelfOrAncestor(XWindow candidate) {
        if (candidate == this || isAncestorOf(candidate)) {
            throw ProtocolException.badMatch();
        }
    }

    public void addChild(XWindow child, int position) {
        if (position < 0 || position >= children.size()) {
            children.add(child);
        } else {
            children.add(position, child);
        }
        child.setParent(this);
    }

    public void removeChild(XWindow child) {
        children.remove(child);
    }

    /** Raises the window to the top of its parent's stack. */
    public void raise() {
        if (parent != null && parent.children.remove(this)) {
            parent.children.add(this);
        }
    }

    /** Lowers the window to the bottom of its parent's stack. */
    public void lower() {
        if (parent != null && parent.children.remove(this)) {
            parent.children.add(0, this);
        }
    }

    @Override
    public String typeName() {
        return "window";
    }

    /** An event mask registered by a client that does not own the window. */
    public static final class OtherClient {
        public final ClientConnection client;
        public int mask;

        public OtherClient(ClientConnection client, int mask) {
            this.client = client;
            this.mask = mask;
        }
    }
}
