package io.github.oyzh1994.xserver.resource;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The shapes a window has been given by the SHAPE extension, and the rectilinear region algebra
 * those shapes are built from.
 *
 * <p>A window carries three independent shapes. The bounding shape is the region the window is
 * considered to occupy — it is what gives a window rounded corners. The clip shape is intersected
 * with the bounding shape to limit drawing. The input shape is the region that accepts pointer
 * events, which is what makes click-through regions work.
 *
 * <p>Each shape is either a list of rectangles or a one bit mask pixmap, positioned relative to
 * the window origin by the shape's offset. {@link #contains(int, int)} answers the question this
 * server can act on today: whether a window relative point falls inside the region that window
 * accepts pointer input in.
 *
 * <p>The rectangle algebra (union, intersection, difference) lives here rather than in the
 * extensions because a SHAPE rectangle list and an XFIXES region are the same thing and both
 * extensions need the same operations on them. It is exact for rectilinear regions: the band
 * decomposition below splits both operands at every rectangle edge, solves each horizontal band
 * as one dimensional intervals, then merges bands that came out identical back together.
 */
public final class ShapeState {

    /** Shape kinds, numbered as the protocol numbers them. */
    public static final int BOUNDING = 0;
    public static final int CLIP = 1;
    public static final int INPUT = 2;

    /** Combine operations, numbered as the protocol numbers them. */
    public static final int OP_SET = 0;
    public static final int OP_UNION = 1;
    public static final int OP_INTERSECT = 2;
    public static final int OP_SUBTRACT = 3;
    public static final int OP_INVERT = 4;

    /** How a shape is stored. {@code MODE_NONE} is the unset shape, which accepts everything. */
    public static final int MODE_NONE = 0;
    public static final int MODE_RECTANGLES = 1;
    public static final int MODE_MASK = 2;

    private static final Comparator<int[]> BY_START = Comparator.comparingInt(span -> span[0]);

    private final Shape[] shapes = {new Shape(), new Shape(), new Shape()};

    /** The shape of one kind. Never null; an unset shape reports {@link Shape#isSet()} false. */
    public Shape shape(int kind) {
        if (kind < BOUNDING || kind > INPUT) {
            throw new IllegalArgumentException("bad shape kind " + kind);
        }
        return shapes[kind];
    }

    public boolean isSet(int kind) {
        return shape(kind).isSet();
    }

    /**
     * Whether a window relative point falls inside the region that accepts pointer input: the
     * input shape when one is set, otherwise the bounding shape, which is how X defines the
     * default, and otherwise the whole window.
     */
    public boolean contains(int x, int y) {
        if (shapes[INPUT].isSet()) {
            return shapes[INPUT].contains(x, y);
        }
        if (shapes[BOUNDING].isSet()) {
            return shapes[BOUNDING].contains(x, y);
        }
        return true;
    }

    public static String kindName(int kind) {
        return switch (kind) {
            case BOUNDING -> "bounding";
            case CLIP -> "clip";
            case INPUT -> "input";
            default -> "kind " + kind;
        };
    }

    // ------------------------------------------------------------------
    // One shape
    // ------------------------------------------------------------------

    /**
     * One of a window's three shapes: either a rectangle list or a one bit mask pixmap, plus the
     * offset that positions it relative to the window origin.
     */
    public static final class Shape {

        private int mode = MODE_NONE;
        private List<Rectangle> rectangles = List.of();
        private XPixmap mask;
        private int originX;
        private int originY;

        public int mode() {
            return mode;
        }

        public boolean isSet() {
            return mode != MODE_NONE;
        }

        /** The rectangles, in shape local coordinates. Empty unless the mode is rectangles. */
        public List<Rectangle> rectangles() {
            return rectangles;
        }

        /** The mask pixmap, or null unless the mode is mask. */
        public XPixmap mask() {
            return mask;
        }

        public int originX() {
            return originX;
        }

        public int originY() {
            return originY;
        }

        public static Shape ofRectangles(List<Rectangle> rectangles, int originX, int originY) {
            Shape shape = new Shape();
            shape.setRectangles(rectangles, originX, originY);
            return shape;
        }

        public static Shape ofMask(XPixmap mask, int originX, int originY) {
            Shape shape = new Shape();
            shape.setMask(mask, originX, originY);
            return shape;
        }

        /**
         * Sets the shape to a rectangle list. An empty list means "no shape", which is how the
         * protocol spells the removal of a shape.
         */
        public void setRectangles(List<Rectangle> rectangles, int originX, int originY) {
            if (rectangles.isEmpty()) {
                clear();
                return;
            }
            this.mode = MODE_RECTANGLES;
            this.rectangles = List.copyOf(rectangles);
            this.mask = null;
            this.originX = originX;
            this.originY = originY;
        }

        public void setMask(XPixmap mask, int originX, int originY) {
            this.mode = MODE_MASK;
            this.rectangles = List.of();
            this.mask = mask;
            this.originX = originX;
            this.originY = originY;
        }

        public void clear() {
            this.mode = MODE_NONE;
            this.rectangles = List.of();
            this.mask = null;
            this.originX = 0;
            this.originY = 0;
        }

        /** Moves the shape relative to the window origin, which is what {@code ShapeOffset} does. */
        public void setOrigin(int x, int y) {
            this.originX = x;
            this.originY = y;
        }

        public void copyFrom(Shape other) {
            this.mode = other.mode;
            this.rectangles = other.rectangles;
            this.mask = other.mask;
            this.originX = other.originX;
            this.originY = other.originY;
        }

        public Shape copy() {
            Shape shape = new Shape();
            shape.copyFrom(this);
            return shape;
        }

        /** Whether a window relative point is inside this shape. */
        public boolean contains(int x, int y) {
            int localX = x - originX;
            int localY = y - originY;
            if (mode == MODE_MASK) {
                return mask != null && localX >= 0 && localY >= 0
                        && localX < mask.width() && localY < mask.height()
                        && mask.pixel(localX, localY) != 0;
            }
            return mode == MODE_RECTANGLES && containsPoint(rectangles, localX, localY);
        }

        /**
         * The bounding box of the shape in window coordinates, or null when the shape is unset. A
         * mask reports its own rectangle because its bits are never decomposed back to rectangles.
         */
        public Rectangle extents() {
            if (mode == MODE_MASK) {
                return mask == null ? null : new Rectangle(originX, originY, mask.width(), mask.height());
            }
            if (mode != MODE_RECTANGLES) {
                return null;
            }
            Rectangle bounds = ShapeState.extents(rectangles);
            if (bounds == null) {
                return null;
            }
            bounds.translate(originX, originY);
            return bounds;
        }

        /**
         * The shape as a rectangle list in window coordinates, which is what set operations
         * consume. A mask is decomposed one horizontal run per row; the result covers exactly the
         * same points, so combining a mask with another shape stays exact.
         */
        public List<Rectangle> absoluteRectangles() {
            if (mode == MODE_MASK) {
                return translate(maskRectangles(mask), originX, originY);
            }
            if (mode != MODE_RECTANGLES) {
                return List.of();
            }
            return translate(rectangles, originX, originY);
        }
    }

    // ------------------------------------------------------------------
    // Rectilinear region algebra
    // ------------------------------------------------------------------

    public static boolean containsPoint(List<Rectangle> rectangles, int x, int y) {
        for (int i = 0; i < rectangles.size(); i++) {
            if (rectangles.get(i).contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** The bounding box of a rectangle list, or null when the list is empty. */
    public static Rectangle extents(List<Rectangle> rectangles) {
        if (rectangles.isEmpty()) {
            return null;
        }
        Rectangle bounds = new Rectangle(rectangles.get(0));
        for (int i = 1; i < rectangles.size(); i++) {
            bounds = bounds.union(rectangles.get(i));
        }
        return bounds;
    }

    public static List<Rectangle> translate(List<Rectangle> rectangles, int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return List.copyOf(rectangles);
        }
        List<Rectangle> result = new ArrayList<>(rectangles.size());
        for (Rectangle rectangle : rectangles) {
            result.add(new Rectangle(rectangle.x + dx, rectangle.y + dy, rectangle.width,
                    rectangle.height));
        }
        return result;
    }

    /**
     * Expands every rectangle by the given margins. The Minkowski sum of a rectilinear region and
     * a rectangle is exactly the union of the expanded rectangles, so this is not an approximation.
     */
    public static List<Rectangle> expand(List<Rectangle> rectangles, int left, int right, int top,
                                         int bottom) {
        List<Rectangle> result = new ArrayList<>(rectangles.size());
        for (Rectangle rectangle : rectangles) {
            result.add(new Rectangle(rectangle.x - left, rectangle.y - top,
                    rectangle.width + left + right, rectangle.height + top + bottom));
        }
        return result;
    }

    /** Everything inside {@code bounds} that is not in {@code rectangles}. */
    public static List<Rectangle> invert(List<Rectangle> rectangles, Rectangle bounds) {
        return subtract(List.of(new Rectangle(bounds)), rectangles);
    }

    public static List<Rectangle> union(List<Rectangle> a, List<Rectangle> b) {
        if (a.isEmpty()) {
            return List.copyOf(b);
        }
        if (b.isEmpty()) {
            return List.copyOf(a);
        }
        return bandCombine(a, b, OP_UNION);
    }

    public static List<Rectangle> intersect(List<Rectangle> a, List<Rectangle> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return List.of();
        }
        return bandCombine(a, b, OP_INTERSECT);
    }

    public static List<Rectangle> subtract(List<Rectangle> a, List<Rectangle> b) {
        if (a.isEmpty()) {
            return List.of();
        }
        if (b.isEmpty()) {
            return List.copyOf(a);
        }
        return bandCombine(a, b, OP_SUBTRACT);
    }

    /**
     * Splits both operands at every rectangle edge, solves each horizontal band as one dimensional
     * intervals, and merges bands that came out identical back into taller rectangles.
     */
    private static List<Rectangle> bandCombine(List<Rectangle> a, List<Rectangle> b, int operation) {
        TreeSet<Integer> cuts = new TreeSet<>();
        for (Rectangle rectangle : a) {
            cuts.add(rectangle.y);
            cuts.add(rectangle.y + rectangle.height);
        }
        for (Rectangle rectangle : b) {
            cuts.add(rectangle.y);
            cuts.add(rectangle.y + rectangle.height);
        }
        List<Integer> rows = new ArrayList<>(cuts);
        List<Rectangle> result = new ArrayList<>();
        List<int[]> previousSpans = null;
        for (int i = 0; i + 1 < rows.size(); i++) {
            int top = rows.get(i);
            int bottom = rows.get(i + 1);
            List<int[]> spans = combineSpans(clipToBand(a, top, bottom), clipToBand(b, top, bottom),
                    operation);
            if (spans.isEmpty()) {
                previousSpans = null;
                continue;
            }
            if (previousSpans != null && sameSpans(previousSpans, spans)) {
                for (int k = 0; k < previousSpans.size(); k++) {
                    Rectangle rectangle = result.get(result.size() - 1 - k);
                    rectangle.height = bottom - rectangle.y;
                }
                continue;
            }
            for (int[] span : spans) {
                result.add(new Rectangle(span[0], top, span[1] - span[0], bottom - top));
            }
            previousSpans = spans;
        }
        return result;
    }

    /** The x intervals the rectangles cover inside the horizontal band {@code [top, bottom)}. */
    private static List<int[]> clipToBand(List<Rectangle> rectangles, int top, int bottom) {
        List<int[]> spans = new ArrayList<>();
        for (Rectangle rectangle : rectangles) {
            if (rectangle.y < bottom && rectangle.y + rectangle.height > top) {
                spans.add(new int[]{rectangle.x, rectangle.x + rectangle.width});
            }
        }
        return spans;
    }

    private static List<int[]> combineSpans(List<int[]> a, List<int[]> b, int operation) {
        List<int[]> left = normalize(a);
        List<int[]> right = normalize(b);
        return switch (operation) {
            case OP_UNION -> mergeSorted(concat(left, right));
            case OP_INTERSECT -> intersectSpans(left, right);
            default -> subtractSpans(left, right);
        };
    }

    private static List<int[]> intersectSpans(List<int[]> a, List<int[]> b) {
        List<int[]> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            int[] left = a.get(i);
            int[] right = b.get(j);
            int start = Math.max(left[0], right[0]);
            int end = Math.min(left[1], right[1]);
            if (start < end) {
                result.add(new int[]{start, end});
            }
            if (left[1] < right[1]) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    private static List<int[]> subtractSpans(List<int[]> a, List<int[]> b) {
        List<int[]> result = new ArrayList<>();
        int j = 0;
        for (int[] left : a) {
            int start = left[0];
            int end = left[1];
            while (j < b.size() && b.get(j)[1] <= start) {
                j++;
            }
            for (int k = j; k < b.size() && start < end; k++) {
                int[] right = b.get(k);
                if (right[0] >= end) {
                    break;
                }
                if (right[0] > start) {
                    result.add(new int[]{start, Math.min(right[0], end)});
                }
                start = Math.max(start, right[1]);
            }
            if (start < end) {
                result.add(new int[]{start, end});
            }
        }
        return result;
    }

    private static List<int[]> normalize(List<int[]> spans) {
        if (spans.size() < 2) {
            return spans;
        }
        return mergeSorted(new ArrayList<>(spans));
    }

    /** Sorts, drops empty spans and coalesces overlapping or touching ones. */
    private static List<int[]> mergeSorted(List<int[]> spans) {
        spans.sort(BY_START);
        List<int[]> result = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            if (span[0] >= span[1]) {
                continue;
            }
            if (!result.isEmpty() && span[0] <= result.get(result.size() - 1)[1]) {
                int[] last = result.get(result.size() - 1);
                last[1] = Math.max(last[1], span[1]);
            } else {
                result.add(new int[]{span[0], span[1]});
            }
        }
        return result;
    }

    private static List<int[]> concat(List<int[]> a, List<int[]> b) {
        List<int[]> result = new ArrayList<>(a.size() + b.size());
        result.addAll(a);
        result.addAll(b);
        return result;
    }

    private static boolean sameSpans(List<int[]> a, List<int[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a one bit mask to rectangles, one horizontal run per row. Only the bits that are
     * set become geometry, so a clear bit is outside the shape.
     */
    public static List<Rectangle> maskRectangles(XPixmap mask) {
        if (mask == null) {
            return List.of();
        }
        List<Rectangle> rectangles = new ArrayList<>();
        for (int y = 0; y < mask.height(); y++) {
            int runStart = -1;
            for (int x = 0; x < mask.width(); x++) {
                boolean set = mask.pixel(x, y) != 0;
                if (set && runStart < 0) {
                    runStart = x;
                } else if (!set && runStart >= 0) {
                    rectangles.add(new Rectangle(runStart, y, x - runStart, 1));
                    runStart = -1;
                }
            }
            if (runStart >= 0) {
                rectangles.add(new Rectangle(runStart, y, mask.width() - runStart, 1));
            }
        }
        return rectangles;
    }
}
