package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.display.Screen;
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

    private int bitGravity = 0;
    private int winGravity = 0;

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

    /** Properties, keyed by the atom naming them. */
    private final java.util.Map<Integer, XProperty> properties = new java.util.HashMap<>();

    /** This client's own event mask. */
    private int eventMask;
    private int doNotPropagateMask;
    /** Event masks other clients asked for on this window. */
    private final List<OtherClient> otherClients = new ArrayList<>();

    /** True when the window has never been drawn, so a map must produce exposes. */
    private boolean needsFullExpose = true;

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

    private List<Rectangle> visibleRegion(Rectangle clip) {
        if (clip.isEmpty()) {
            return List.of();
        }
        List<Rectangle> region = List.of(clip);
        // Walk this window and then each ancestor: at every level, the siblings stacked above the
        // one we came through are the ones that can cover it - and this window's own children,
        // which are drawn inside it, are above its own content just as surely.
        for (XWindow window = this; window != null && !region.isEmpty(); window = window.parent()) {
            if (window == this) {
                region = subtractMappedChildren(this, region);
            }
            XWindow parent = window.parent();
            if (parent == null) {
                break;
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
        // inside it and needs no separate subtraction.
        return ShapeState.subtract(region, List.of(above.absoluteBoundsWithBorder()));
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
