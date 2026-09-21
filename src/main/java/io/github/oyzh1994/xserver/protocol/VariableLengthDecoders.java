package io.github.oyzh1994.xserver.protocol;

import com.github.moaxcp.x11.protocol.X11Input;
import com.github.moaxcp.x11.protocol.XRequest;
import com.github.moaxcp.x11.protocol.xproto.Arc;
import com.github.moaxcp.x11.protocol.xproto.Char2b;
import com.github.moaxcp.x11.protocol.xproto.Coloritem;
import com.github.moaxcp.x11.protocol.xproto.FillPoly;
import com.github.moaxcp.x11.protocol.xproto.FreeColors;
import com.github.moaxcp.x11.protocol.xproto.PolyText16;
import com.github.moaxcp.x11.protocol.xproto.PolyText8;
import com.github.moaxcp.x11.protocol.xproto.PutImage;
import com.github.moaxcp.x11.protocol.xproto.QueryColors;
import com.github.moaxcp.x11.protocol.xproto.Point;
import com.github.moaxcp.x11.protocol.xproto.PolyArc;
import com.github.moaxcp.x11.protocol.xproto.PolyFillArc;
import com.github.moaxcp.x11.protocol.xproto.PolyFillRectangle;
import com.github.moaxcp.x11.protocol.xproto.PolyLine;
import com.github.moaxcp.x11.protocol.xproto.PolyPoint;
import com.github.moaxcp.x11.protocol.xproto.PolyRectangle;
import com.github.moaxcp.x11.protocol.xproto.PolySegment;
import com.github.moaxcp.x11.protocol.xproto.QueryTextExtents;
import com.github.moaxcp.x11.protocol.xproto.Rectangle;
import com.github.moaxcp.x11.protocol.xproto.Segment;
import com.github.moaxcp.x11.protocol.xproto.SetClipRectangles;
import com.github.moaxcp.x11.protocol.xproto.StoreColors;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.eclipse.collections.impl.factory.primitive.IntLists;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoders for the requests whose generated list length comes out negative.
 *
 * <p>The generated readers size their result list with {@code withInitialCapacity(length - javaStart)}
 * where {@code length} is the request length in <em>four byte units</em> and {@code javaStart} is a
 * byte offset. The two are not comparable, so for any request shorter than roughly four times its
 * own header the argument is negative and {@code Eclipse Collections} throws
 * {@code IllegalArgumentException}. That covers almost every real request: a two point
 * {@code PolyLine} is 5 units long with a 12 byte header, so the list would be sized {@code -7}.
 *
 * <p>Five more requests use the same broken expression as a <em>count</em> rather than a capacity
 * ({@code readByte(length - javaStart)} and friends), which does not throw but silently reads fewer
 * items than were sent: {@code PutImage} would draw a fraction of each image, {@code PolyText8}
 * would draw little or no text, and {@code QueryColors} would answer with too few colours and
 * desynchronise the client. Those are decoded here too.
 *
 * <p>In all, sixteen requests are decoded here and handed to the same handlers as everything else.
 * The field order is taken from the generated readers, which are correct apart from the arithmetic.
 *
 * <p>If the library is ever fixed upstream this class can be deleted and the dispatch in
 * {@code RequestDispatcher} simplified back to a single call.
 */
public final class VariableLengthDecoders {

    // Core protocol opcodes for the affected requests.
    private static final int QUERY_TEXT_EXTENTS = 48;
    private static final int SET_CLIP_RECTANGLES = 59;
    private static final int POLY_POINT = 64;
    private static final int POLY_LINE = 65;
    private static final int POLY_SEGMENT = 66;
    private static final int POLY_RECTANGLE = 67;
    private static final int POLY_ARC = 68;
    private static final int FILL_POLY = 69;
    private static final int POLY_FILL_RECTANGLE = 70;
    private static final int POLY_FILL_ARC = 71;
    private static final int PUT_IMAGE = 72;
    private static final int POLY_TEXT8 = 74;
    private static final int POLY_TEXT16 = 75;
    private static final int FREE_COLORS = 88;
    private static final int STORE_COLORS = 89;
    private static final int QUERY_COLORS = 91;

    private VariableLengthDecoders() {
    }

    /** True when this opcode needs the custom decoder. */
    public static boolean handles(int opcode) {
        return switch (opcode) {
            case QUERY_TEXT_EXTENTS, SET_CLIP_RECTANGLES, POLY_POINT, POLY_LINE, POLY_SEGMENT,
                 POLY_RECTANGLE, POLY_ARC, FILL_POLY, POLY_FILL_RECTANGLE, POLY_FILL_ARC, PUT_IMAGE,
                 POLY_TEXT8, POLY_TEXT16, FREE_COLORS, STORE_COLORS, QUERY_COLORS -> true;
            default -> false;
        };
    }

    /**
     * Decodes one of the affected requests. The opcode byte has already been consumed; the input
     * starts at the request's data byte.
     */
    public static XRequest decode(int opcode, X11Input in) throws IOException {
        return switch (opcode) {
            case SET_CLIP_RECTANGLES -> readSetClipRectangles(in);
            case POLY_POINT -> readPolyPoint(in);
            case POLY_LINE -> readPolyLine(in);
            case POLY_SEGMENT -> readPolySegment(in);
            case POLY_RECTANGLE -> readPolyRectangle(in);
            case POLY_ARC -> readPolyArc(in);
            case FILL_POLY -> readFillPoly(in);
            case POLY_FILL_RECTANGLE -> readPolyFillRectangle(in);
            case POLY_FILL_ARC -> readPolyFillArc(in);
            case STORE_COLORS -> readStoreColors(in);
            case QUERY_TEXT_EXTENTS -> readQueryTextExtents(in);
            case PUT_IMAGE -> readPutImage(in);
            case POLY_TEXT8 -> readPolyText8(in);
            case POLY_TEXT16 -> readPolyText16(in);
            case FREE_COLORS -> readFreeColors(in);
            case QUERY_COLORS -> readQueryColors(in);
            default -> throw new IllegalArgumentException("opcode " + opcode + " is not custom decoded");
        };
    }

    // ------------------------------------------------------------------
    // Byte lists that fill the rest of the request
    // ------------------------------------------------------------------

    private static PutImage readPutImage(X11Input in) throws IOException {
        byte format = in.readByte();
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        short width = in.readCard16();
        short height = in.readCard16();
        short dstX = in.readInt16();
        short dstY = in.readInt16();
        byte leftPad = in.readByte();
        byte depth = in.readByte();
        in.readPad(2);
        // Everything after the fixed part is image data, one bit or pixel at a time.
        byte[] data = readBytes(in, count(length, 24, 1));
        return PutImage.builder()
                .format(format)
                .drawable(drawable)
                .gc(gc)
                .width(width)
                .height(height)
                .dstX(dstX)
                .dstY(dstY)
                .leftPad(leftPad)
                .depth(depth)
                .data(ByteLists.immutable.of(data))
                .build();
    }

    private static PolyText8 readPolyText8(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        short x = in.readInt16();
        short y = in.readInt16();
        byte[] items = readBytes(in, count(length, 16, 1));
        return PolyText8.builder()
                .drawable(drawable)
                .gc(gc)
                .x(x)
                .y(y)
                .items(ByteLists.immutable.of(items))
                .build();
    }

    private static PolyText16 readPolyText16(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        short x = in.readInt16();
        short y = in.readInt16();
        byte[] items = readBytes(in, count(length, 16, 1));
        return PolyText16.builder()
                .drawable(drawable)
                .gc(gc)
                .x(x)
                .y(y)
                .items(ByteLists.immutable.of(items))
                .build();
    }

    // ------------------------------------------------------------------
    // Pixel lists
    // ------------------------------------------------------------------

    private static FreeColors readFreeColors(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int cmap = in.readCard32();
        int planeMask = in.readCard32();
        int n = count(length, 12, 4);
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            pixels[i] = in.readCard32();
        }
        return FreeColors.builder()
                .cmap(cmap)
                .planeMask(planeMask)
                .pixels(IntLists.immutable.of(pixels))
                .build();
    }

    private static QueryColors readQueryColors(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int cmap = in.readCard32();
        int n = count(length, 8, 4);
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            pixels[i] = in.readCard32();
        }
        return QueryColors.builder()
                .cmap(cmap)
                .pixels(IntLists.immutable.of(pixels))
                .build();
    }

    private static byte[] readBytes(X11Input in, int count) throws IOException {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = in.readByte();
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // Geometry lists
    // ------------------------------------------------------------------

    private static PolyPoint readPolyPoint(X11Input in) throws IOException {
        byte coordinateMode = in.readByte();
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        List<Point> points = readPoints(in, count(length, 12, 4));
        return PolyPoint.builder()
                .coordinateMode(coordinateMode)
                .drawable(drawable)
                .gc(gc)
                .points(Lists.immutable.withAll(points))
                .build();
    }

    private static PolyLine readPolyLine(X11Input in) throws IOException {
        byte coordinateMode = in.readByte();
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        List<Point> points = readPoints(in, count(length, 12, 4));
        return PolyLine.builder()
                .coordinateMode(coordinateMode)
                .drawable(drawable)
                .gc(gc)
                .points(Lists.immutable.withAll(points))
                .build();
    }

    private static PolySegment readPolySegment(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        int n = count(length, 12, 8);
        List<Segment> segments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            segments.add(Segment.builder()
                    .x1(in.readInt16())
                    .y1(in.readInt16())
                    .x2(in.readInt16())
                    .y2(in.readInt16())
                    .build());
        }
        return PolySegment.builder()
                .drawable(drawable)
                .gc(gc)
                .segments(Lists.immutable.withAll(segments))
                .build();
    }

    private static PolyRectangle readPolyRectangle(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        int n = count(length, 12, 8);
        List<Rectangle> rectangles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rectangles.add(Rectangle.builder()
                    .x(in.readInt16())
                    .y(in.readInt16())
                    .width(in.readInt16())
                    .height(in.readInt16())
                    .build());
        }
        return PolyRectangle.builder()
                .drawable(drawable)
                .gc(gc)
                .rectangles(Lists.immutable.withAll(rectangles))
                .build();
    }

    private static PolyArc readPolyArc(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        int n = count(length, 12, 12);
        List<Arc> arcs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            arcs.add(readArc(in));
        }
        return PolyArc.builder()
                .drawable(drawable)
                .gc(gc)
                .arcs(Lists.immutable.withAll(arcs))
                .build();
    }

    private static FillPoly readFillPoly(X11Input in) throws IOException {
        // FillPoly is the odd one out: its shape and coordinate mode come after the drawable and
        // the GC rather than in the data byte.
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        byte shape = in.readByte();
        byte coordinateMode = in.readByte();
        in.readPad(2);
        List<Point> points = readPoints(in, count(length, 16, 4));
        return FillPoly.builder()
                .shape(shape)
                .coordinateMode(coordinateMode)
                .drawable(drawable)
                .gc(gc)
                .points(Lists.immutable.withAll(points))
                .build();
    }

    private static PolyFillRectangle readPolyFillRectangle(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        int n = count(length, 12, 8);
        List<Rectangle> rectangles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rectangles.add(Rectangle.builder()
                    .x(in.readInt16())
                    .y(in.readInt16())
                    .width(in.readInt16())
                    .height(in.readInt16())
                    .build());
        }
        return PolyFillRectangle.builder()
                .drawable(drawable)
                .gc(gc)
                .rectangles(Lists.immutable.withAll(rectangles))
                .build();
    }

    private static PolyFillArc readPolyFillArc(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int drawable = in.readCard32();
        int gc = in.readCard32();
        int n = count(length, 12, 12);
        List<Arc> arcs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            arcs.add(readArc(in));
        }
        return PolyFillArc.builder()
                .drawable(drawable)
                .gc(gc)
                .arcs(Lists.immutable.withAll(arcs))
                .build();
    }

    private static SetClipRectangles readSetClipRectangles(X11Input in) throws IOException {
        byte ordering = in.readByte();
        int length = Short.toUnsignedInt(in.readCard16());
        int gc = in.readCard32();
        short clipXOrigin = in.readInt16();
        short clipYOrigin = in.readInt16();
        int n = count(length, 12, 8);
        List<Rectangle> rectangles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rectangles.add(Rectangle.builder()
                    .x(in.readInt16())
                    .y(in.readInt16())
                    .width(in.readInt16())
                    .height(in.readInt16())
                    .build());
        }
        return SetClipRectangles.builder()
                .ordering(ordering)
                .gc(gc)
                .clipXOrigin(clipXOrigin)
                .clipYOrigin(clipYOrigin)
                .rectangles(Lists.immutable.withAll(rectangles))
                .build();
    }

    private static StoreColors readStoreColors(X11Input in) throws IOException {
        in.readPad(1);
        int length = Short.toUnsignedInt(in.readCard16());
        int cmap = in.readCard32();
        int n = count(length, 8, 12);
        List<Coloritem> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(Coloritem.builder()
                    .pixel(in.readCard32())
                    .red(in.readCard16())
                    .green(in.readCard16())
                    .blue(in.readCard16())
                    .flags(in.readCard8())
                    .build());
        }
        return StoreColors.builder()
                .cmap(cmap)
                .items(Lists.immutable.withAll(items))
                .build();
    }

    private static QueryTextExtents readQueryTextExtents(X11Input in) throws IOException {
        // The data byte flags an odd string length. That is what tells the server one byte of the
        // request is padding rather than half a character: a three character string occupies seven
        // bytes after the opcode, so the request is padded to sixteen and the naive count finds a
        // fourth, spurious character.
        boolean oddLength = in.readBool();
        int length = Short.toUnsignedInt(in.readCard16());
        int font = in.readCard32();
        int n = (length * 4 - 8 - (oddLength ? 1 : 0)) / 2;
        List<Char2b> string = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            string.add(Char2b.builder()
                    .byte1(in.readCard8())
                    .byte2(in.readCard8())
                    .build());
        }
        return QueryTextExtents.builder()
                .font(font)
                .string(Lists.immutable.withAll(string))
                .build();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Elements in a list that follows a header of {@code headerBytes} excluding the opcode, where
     * {@code lengthUnits} is the whole request in four byte units.
     */
    private static int count(int lengthUnits, int headerBytes, int elementBytes) {
        int available = lengthUnits * 4 - headerBytes;
        if (available <= 0) {
            return 0;
        }
        return available / elementBytes;
    }

    private static List<Point> readPoints(X11Input in, int count) throws IOException {
        List<Point> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(Point.builder().x(in.readInt16()).y(in.readInt16()).build());
        }
        return points;
    }

    private static Arc readArc(X11Input in) throws IOException {
        return Arc.builder()
                .x(in.readInt16())
                .y(in.readInt16())
                .width(in.readCard16())
                .height(in.readCard16())
                .angle1(in.readInt16())
                .angle2(in.readInt16())
                .build();
    }
}
