package io.github.oyzh1994.xserver.extension;

import com.github.moaxcp.x11.protocol.XProtocolPlugin;
import com.github.moaxcp.x11.protocol.XRequest;
import com.github.moaxcp.x11.protocol.xfixes.ChangeCursor;
import com.github.moaxcp.x11.protocol.xfixes.ChangeCursorByName;
import com.github.moaxcp.x11.protocol.xfixes.ChangeSaveSet;
import com.github.moaxcp.x11.protocol.xfixes.CopyRegion;
import com.github.moaxcp.x11.protocol.xfixes.CreatePointerBarrier;
import com.github.moaxcp.x11.protocol.xfixes.CreateRegion;
import com.github.moaxcp.x11.protocol.xfixes.CreateRegionFromBitmap;
import com.github.moaxcp.x11.protocol.xfixes.CreateRegionFromGC;
import com.github.moaxcp.x11.protocol.xfixes.CreateRegionFromPicture;
import com.github.moaxcp.x11.protocol.xfixes.CreateRegionFromWindow;
import com.github.moaxcp.x11.protocol.xfixes.DeletePointerBarrier;
import com.github.moaxcp.x11.protocol.xfixes.DestroyRegion;
import com.github.moaxcp.x11.protocol.xfixes.ExpandRegion;
import com.github.moaxcp.x11.protocol.xfixes.FetchRegion;
import com.github.moaxcp.x11.protocol.xfixes.FetchRegionReply;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorImage;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorImageAndName;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorImageAndNameReply;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorImageReply;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorName;
import com.github.moaxcp.x11.protocol.xfixes.GetCursorNameReply;
import com.github.moaxcp.x11.protocol.xfixes.HideCursor;
import com.github.moaxcp.x11.protocol.xfixes.IntersectRegion;
import com.github.moaxcp.x11.protocol.xfixes.InvertRegion;
import com.github.moaxcp.x11.protocol.xfixes.QueryVersion;
import com.github.moaxcp.x11.protocol.xfixes.QueryVersionReply;
import com.github.moaxcp.x11.protocol.xfixes.RegionExtents;
import com.github.moaxcp.x11.protocol.xfixes.SelectCursorInput;
import com.github.moaxcp.x11.protocol.xfixes.SelectSelectionInput;
import com.github.moaxcp.x11.protocol.xfixes.SetCursorName;
import com.github.moaxcp.x11.protocol.xfixes.SetGCClipRegion;
import com.github.moaxcp.x11.protocol.xfixes.SetPictureClipRegion;
import com.github.moaxcp.x11.protocol.xfixes.SetRegion;
import com.github.moaxcp.x11.protocol.xfixes.SetWindowShapeRegion;
import com.github.moaxcp.x11.protocol.xfixes.ShowCursor;
import com.github.moaxcp.x11.protocol.xfixes.SubtractRegion;
import com.github.moaxcp.x11.protocol.xfixes.TranslateRegion;
import com.github.moaxcp.x11.protocol.xfixes.UnionRegion;
import com.github.moaxcp.x11.protocol.xfixes.XfixesPlugin;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.resource.ShapeState;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XGC;
import io.github.oyzh1994.xserver.resource.XPixmap;
import io.github.oyzh1994.xserver.resource.XResource;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.primitive.IntList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.eclipse.collections.impl.factory.primitive.IntLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * XFIXES.
 *
 * <p>XFIXES is the extension toolkits probe before they use anything else, because it is the gate
 * for RENDER and Composite. Two of its requests carry real weight here: cursor images, which
 * clients read back to draw eye candy and to compare cursors, and regions, which are the same
 * rectangles-and-algebra the SHAPE extension works with and which {@code SetWindowShapeRegion}
 * hands straight to it.
 *
 * <p>The version reported is 4.0, the highest release whose requests are all answered. Requests
 * that this server cannot honour are rejected with {@code BadImplementation} rather than silently
 * ignored, so a client is never told something happened when nothing did. That covers the picture
 * and pointer barrier requests, which have no backing resources, and the cursor mutation requests,
 * which would have to rewrite a cursor image in place.
 *
 * <p>Known gaps, all deliberate:
 * <ul>
 *   <li>No selection or cursor notification events are ever generated. {@code SelectSelectionInput}
 *       and {@code SelectCursorInput} are recorded so the masks are not lost, but the events they
 *       ask for would have to be raised from the selection and cursor code, which lives outside
 *       this extension.
 *   <li>{@code HideCursor}/{@code ShowCursor} only count: {@code InputState} draws the sprite
 *       unconditionally, so the cursor stays visible. {@link #isCursorHidden()} is what a caller
 *       would consult to change that, and it is what the counter exists for.
 * </ul>
 */
public final class XFixesExtension implements Extension {

    private static final Logger log = LoggerFactory.getLogger(XFixesExtension.class);

    /** XFIXES 4.0: everything below is answered, and nothing above it is claimed. */
    private static final int MAJOR_VERSION = 4;
    private static final int MINOR_VERSION = 0;

    /** Resource class of an XFIXES region, shared by every region this extension creates. */
    private static final int REGION_CLASS = XResource.newExtensionClass();

    private final XfixesPlugin plugin = new XfixesPlugin();

    /** The SHAPE extension, resolved lazily because registration order is not ours to pick. */
    private ShapeExtension shape;

    /** Selection and cursor event masks clients asked for, per client and window. */
    private final Map<ClientConnection, Map<Integer, Integer>> selectionInput = new IdentityHashMap<>();
    private final Map<ClientConnection, Map<Integer, Integer>> cursorInput = new IdentityHashMap<>();

    /** How many clients currently have the cursor hidden. */
    private int hiddenCursors;

    @Override
    public String name() {
        return "XFIXES";
    }

    @Override
    public byte majorOpcode() {
        return plugin.getMajorOpcode();
    }

    @Override
    public byte firstEvent() {
        return plugin.getFirstEvent();
    }

    @Override
    public byte firstError() {
        return plugin.getFirstError();
    }

    @Override
    public XProtocolPlugin plugin() {
        return plugin;
    }

    @Override
    public void handleRequest(XServer server, ClientConnection client, XRequest request) {
        if (request instanceof QueryVersion queryVersion) {
            queryVersion(client, queryVersion);
        } else if (request instanceof GetCursorImage getCursorImage) {
            getCursorImage(server, client, getCursorImage);
        } else if (request instanceof GetCursorImageAndName getCursorImageAndName) {
            getCursorImageAndName(server, client, getCursorImageAndName);
        } else if (request instanceof GetCursorName getCursorName) {
            getCursorName(server, client, getCursorName);
        } else if (request instanceof SelectSelectionInput selectSelectionInput) {
            selectSelectionInput(server, client, selectSelectionInput);
        } else if (request instanceof SelectCursorInput selectCursorInput) {
            selectCursorInput(server, client, selectCursorInput);
        } else if (request instanceof HideCursor hideCursor) {
            hideCursor(server, client, hideCursor);
        } else if (request instanceof ShowCursor showCursor) {
            showCursor(server, client, showCursor);
        } else if (request instanceof CreateRegion createRegion) {
            createRegion(server, client, createRegion);
        } else if (request instanceof SetRegion setRegion) {
            setRegion(server, client, setRegion);
        } else if (request instanceof DestroyRegion destroyRegion) {
            destroyRegion(server, client, destroyRegion);
        } else if (request instanceof CopyRegion copyRegion) {
            copyRegion(server, client, copyRegion);
        } else if (request instanceof UnionRegion unionRegion) {
            unionRegion(server, client, unionRegion);
        } else if (request instanceof IntersectRegion intersectRegion) {
            intersectRegion(server, client, intersectRegion);
        } else if (request instanceof SubtractRegion subtractRegion) {
            subtractRegion(server, client, subtractRegion);
        } else if (request instanceof InvertRegion invertRegion) {
            invertRegion(server, client, invertRegion);
        } else if (request instanceof TranslateRegion translateRegion) {
            translateRegion(server, client, translateRegion);
        } else if (request instanceof RegionExtents regionExtents) {
            regionExtents(server, client, regionExtents);
        } else if (request instanceof ExpandRegion expandRegion) {
            expandRegion(server, client, expandRegion);
        } else if (request instanceof FetchRegion fetchRegion) {
            fetchRegion(server, client, fetchRegion);
        } else if (request instanceof CreateRegionFromBitmap createRegionFromBitmap) {
            createRegionFromBitmap(server, client, createRegionFromBitmap);
        } else if (request instanceof CreateRegionFromWindow createRegionFromWindow) {
            createRegionFromWindow(server, client, createRegionFromWindow);
        } else if (request instanceof CreateRegionFromGC createRegionFromGC) {
            createRegionFromGC(server, client, createRegionFromGC);
        } else if (request instanceof SetGCClipRegion setGCClipRegion) {
            setGCClipRegion(server, client, setGCClipRegion);
        } else if (request instanceof SetWindowShapeRegion setWindowShapeRegion) {
            setWindowShapeRegion(server, client, setWindowShapeRegion);
        } else if (request instanceof SetCursorName setCursorName) {
            setCursorName(setCursorName);
        } else if (request instanceof ChangeSaveSet changeSaveSet) {
            changeSaveSet(changeSaveSet);
        } else if (request instanceof DeletePointerBarrier deletePointerBarrier) {
            deletePointerBarrier(deletePointerBarrier);
        } else if (request instanceof CreateRegionFromPicture createRegionFromPicture) {
            throw ProtocolException.unimplemented("XFixesCreateRegionFromPicture");
        } else if (request instanceof SetPictureClipRegion setPictureClipRegion) {
            throw ProtocolException.unimplemented("XFixesSetPictureClipRegion");
        } else if (request instanceof ChangeCursor changeCursor) {
            throw ProtocolException.unimplemented("XFixesChangeCursor");
        } else if (request instanceof ChangeCursorByName changeCursorByName) {
            throw ProtocolException.unimplemented("XFixesChangeCursorByName");
        } else if (request instanceof CreatePointerBarrier createPointerBarrier) {
            throw ProtocolException.unimplemented("XFixesCreatePointerBarrier");
        } else {
            throw new IllegalArgumentException("unhandled XFIXES request " + request.getClass());
        }
    }

    @Override
    public void clientDisconnected(XServer server, ClientConnection client) {
        selectionInput.remove(client);
        cursorInput.remove(client);
    }

    /** Whether some client asked for the cursor to be hidden. */
    public boolean isCursorHidden() {
        return hiddenCursors > 0;
    }

    // ------------------------------------------------------------------
    // Version and cursors
    // ------------------------------------------------------------------

    /**
     * The reply is capped at the major version the client asked for, as X defines: a client that
     * only knows 2.0 would otherwise be told about a version it cannot speak. A client that asks
     * for more than we have is told what we have, which is the point of the request.
     */
    private void queryVersion(ClientConnection client, QueryVersion request) {
        int major = MAJOR_VERSION;
        int minor = MINOR_VERSION;
        if (request.getClientMajorVersion() < MAJOR_VERSION) {
            major = request.getClientMajorVersion();
            minor = 0;
        }
        client.sendReply(QueryVersionReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .majorVersion(major)
                .minorVersion(minor)
                .build());
    }

    private void getCursorImage(XServer server, ClientConnection client, GetCursorImage request) {
        XCursor cursor = server.rootCursor();
        client.sendReply(GetCursorImageReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .x((short) (server.input().pointerX() - cursor.hotspotX()))
                .y((short) (server.input().pointerY() - cursor.hotspotY()))
                .width((short) cursor.width())
                .height((short) cursor.height())
                .xhot((short) cursor.hotspotX())
                .yhot((short) cursor.hotspotY())
                .cursorSerial(0)                        // The root cursor never changes
                .cursorImage(cursorPixels(cursor))
                .build());
    }

    private void getCursorImageAndName(XServer server, ClientConnection client,
                                       GetCursorImageAndName request) {
        XCursor cursor = server.rootCursor();
        client.sendReply(GetCursorImageAndNameReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .x((short) (server.input().pointerX() - cursor.hotspotX()))
                .y((short) (server.input().pointerY() - cursor.hotspotY()))
                .width((short) cursor.width())
                .height((short) cursor.height())
                .xhot((short) cursor.hotspotX())
                .yhot((short) cursor.hotspotY())
                .cursorSerial(0)
                .cursorAtom(0)                          // None: our cursors carry no name
                .cursorImage(cursorPixels(cursor))
                .name(ByteLists.immutable.empty())
                .build());
    }

    /**
     * Cursors here are built from bitmaps or glyphs and are never named, so the honest answer is
     * {@code None} with an empty name rather than an invented atom.
     */
    private void getCursorName(XServer server, ClientConnection client, GetCursorName request) {
        server.resources().require(client, request.getCursor(), XResource.CLASS_CURSOR, false);
        client.sendReply(GetCursorNameReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .atom(0)
                .name(ByteLists.immutable.empty())
                .build());
    }

    private void setCursorName(SetCursorName request) {
        // Cursors have no names in this server, so there is no name to attach. Saying so is better
        // than pretending it worked, but the request is harmless enough not to be an error.
        log.debug("XFIXES SetCursorName ignored: cursors carry no names");
    }

    private static IntList cursorPixels(XCursor cursor) {
        int width = cursor.width();
        int height = cursor.height();
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = cursor.pixel(x, y);
            }
        }
        return IntLists.immutable.of(pixels);
    }

    // ------------------------------------------------------------------
    // Notifications, hiding
    // ------------------------------------------------------------------

    private void selectSelectionInput(XServer server, ClientConnection client,
                                      SelectSelectionInput request) {
        XWindow window = requireWindow(server, client, request.getWindow());
        recordMask(selectionInput, client, window.id(), request.getEventMask());
    }

    private void selectCursorInput(XServer server, ClientConnection client,
                                   SelectCursorInput request) {
        XWindow window = requireWindow(server, client, request.getWindow());
        recordMask(cursorInput, client, window.id(), request.getEventMask());
    }

    private void hideCursor(XServer server, ClientConnection client, HideCursor request) {
        requireWindow(server, client, request.getWindow());
        hiddenCursors++;
        server.markScreenDirty();
    }

    private void showCursor(XServer server, ClientConnection client, ShowCursor request) {
        requireWindow(server, client, request.getWindow());
        if (hiddenCursors > 0) {
            hiddenCursors--;
        }
        server.markScreenDirty();
    }

    /**
     * Records an event mask, dropping it when it is zero, which is how a client unselects. Nothing
     * reads these back yet: the events they ask for are raised by the selection code and the cursor
     * sprite, neither of which is part of this extension.
     */
    private void recordMask(Map<ClientConnection, Map<Integer, Integer>> table,
                            ClientConnection client, int window, int mask) {
        Map<Integer, Integer> masks = table.computeIfAbsent(client, key -> new HashMap<>());
        if (mask == 0) {
            masks.remove(window);
        } else {
            masks.put(window, mask);
        }
    }

    private void changeSaveSet(ChangeSaveSet request) {
        // XFIXES extends the save set to windows owned by other clients. The core save set lives in
        // the window handlers and has no hook for that, so the request is accepted and ignored.
        log.debug("XFIXES ChangeSaveSet ignored: only the core save set is tracked");
    }

    private void deletePointerBarrier(DeletePointerBarrier request) {
        // No barrier can have been created, so there is nothing to delete.
        log.debug("XFIXES DeletePointerBarrier ignored: barriers are not implemented");
    }

    // ------------------------------------------------------------------
    // Regions
    // ------------------------------------------------------------------

    private void createRegion(XServer server, ClientConnection client, CreateRegion request) {
        create(server, client, request.getRegion(), toAwt(request.getRectangles()));
    }

    private void setRegion(XServer server, ClientConnection client, SetRegion request) {
        requireRegion(server, client, request.getRegion())
                .setRectangles(toAwt(request.getRectangles()));
    }

    private void destroyRegion(XServer server, ClientConnection client, DestroyRegion request) {
        if (request.getRegion() == 0) {
            return;
        }
        requireRegion(server, client, request.getRegion());
        server.resources().free(request.getRegion());
    }

    private void copyRegion(XServer server, ClientConnection client, CopyRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        destination.setRectangles(requireRegion(server, client, request.getSource()).rectangles());
    }

    private void unionRegion(XServer server, ClientConnection client, UnionRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        destination.setRectangles(ShapeState.union(
                requireRegion(server, client, request.getSource1()).rectangles(),
                requireRegion(server, client, request.getSource2()).rectangles()));
    }

    private void intersectRegion(XServer server, ClientConnection client, IntersectRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        destination.setRectangles(ShapeState.intersect(
                requireRegion(server, client, request.getSource1()).rectangles(),
                requireRegion(server, client, request.getSource2()).rectangles()));
    }

    private void subtractRegion(XServer server, ClientConnection client, SubtractRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        destination.setRectangles(ShapeState.subtract(
                requireRegion(server, client, request.getSource1()).rectangles(),
                requireRegion(server, client, request.getSource2()).rectangles()));
    }

    private void invertRegion(XServer server, ClientConnection client, InvertRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        Rectangle bounds = toAwt(request.getBounds());
        destination.setRectangles(ShapeState.invert(
                requireRegion(server, client, request.getSource()).rectangles(), bounds));
    }

    private void translateRegion(XServer server, ClientConnection client, TranslateRegion request) {
        RegionResource region = requireRegion(server, client, request.getRegion());
        region.setRectangles(ShapeState.translate(region.rectangles(), request.getDx(),
                request.getDy()));
    }

    private void regionExtents(XServer server, ClientConnection client, RegionExtents request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        Rectangle extents = ShapeState.extents(
                requireRegion(server, client, request.getSource()).rectangles());
        destination.setRectangles(extents == null ? List.of() : List.of(extents));
    }

    private void expandRegion(XServer server, ClientConnection client, ExpandRegion request) {
        RegionResource destination = requireRegion(server, client, request.getDestination());
        destination.setRectangles(ShapeState.expand(
                requireRegion(server, client, request.getSource()).rectangles(),
                Short.toUnsignedInt(request.getLeft()), Short.toUnsignedInt(request.getRight()),
                Short.toUnsignedInt(request.getTop()), Short.toUnsignedInt(request.getBottom())));
    }

    private void fetchRegion(XServer server, ClientConnection client, FetchRegion request) {
        List<Rectangle> rectangles = requireRegion(server, client, request.getRegion()).rectangles();
        Rectangle extents = ShapeState.extents(rectangles);
        List<com.github.moaxcp.x11.protocol.xproto.Rectangle> reply =
                new ArrayList<>(rectangles.size());
        for (Rectangle rectangle : rectangles) {
            reply.add(toProtocol(rectangle));
        }
        client.sendReply(FetchRegionReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .extents(extents == null ? emptyProtocolRectangle() : toProtocol(extents))
                .rectangles(Lists.immutable.ofAll(reply))
                .build());
    }

    private void createRegionFromBitmap(XServer server, ClientConnection client,
                                        CreateRegionFromBitmap request) {
        XPixmap bitmap = (XPixmap) server.resources().require(client, request.getBitmap(),
                XResource.CLASS_PIXMAP, false);
        if (bitmap.depth() != 1) {
            throw ProtocolException.badMatch();
        }
        create(server, client, request.getRegion(), ShapeState.maskRectangles(bitmap));
    }

    private void createRegionFromWindow(XServer server, ClientConnection client,
                                        CreateRegionFromWindow request) {
        XWindow window = requireWindow(server, client, request.getWindow());
        int kind = Byte.toUnsignedInt(request.getKind());
        checkShapeKind(kind);
        ShapeExtension shape = shape(server);
        if (shape == null) {
            throw ProtocolException.unimplemented("SHAPE is not registered");
        }
        create(server, client, request.getRegion(), shape.shapeRectangles(window, kind));
    }

    private void createRegionFromGC(XServer server, ClientConnection client,
                                    CreateRegionFromGC request) {
        XGC gc = (XGC) server.resources().require(client, request.getGc(), XResource.CLASS_GC, false);
        List<Rectangle> rectangles;
        if (gc.isClipByRectangles()) {
            rectangles = new ArrayList<>(gc.clipRectangles().size());
            for (XGC.Rectangle2 rectangle : gc.clipRectangles()) {
                rectangles.add(new Rectangle(rectangle.x() + gc.clipOriginX(),
                        rectangle.y() + gc.clipOriginY(), rectangle.width(), rectangle.height()));
            }
        } else if (gc.clipMask() instanceof XPixmap mask) {
            rectangles = ShapeState.translate(ShapeState.maskRectangles(mask), gc.clipOriginX(),
                    gc.clipOriginY());
        } else {
            // No clip at all means the region is the whole screen.
            rectangles = List.of(new Rectangle(0, 0, server.screen().width(),
                    server.screen().height()));
        }
        create(server, client, request.getRegion(), rectangles);
    }

    private void setGCClipRegion(XServer server, ClientConnection client, SetGCClipRegion request) {
        XGC gc = (XGC) server.resources().require(client, request.getGc(), XResource.CLASS_GC, false);
        if (request.getRegion() == 0) {
            // None resets the clip, which is a null mask rather than an empty rectangle list: an
            // empty list would clip everything away.
            gc.setClipMask(null);
            return;
        }
        // The rectangles are relative to the clip origin, which is where the region sits.
        List<XGC.Rectangle2> rectangles = new ArrayList<>();
        for (Rectangle rectangle : requireRegion(server, client, request.getRegion()).rectangles()) {
            rectangles.add(new XGC.Rectangle2(rectangle.x - request.getXOrigin(),
                    rectangle.y - request.getYOrigin(), rectangle.width, rectangle.height));
        }
        gc.setClipRectangles(rectangles, 0);
        gc.setClipOriginX(request.getXOrigin());
        gc.setClipOriginY(request.getYOrigin());
    }

    private void setWindowShapeRegion(XServer server, ClientConnection client,
                                      SetWindowShapeRegion request) {
        XWindow window = requireWindow(server, client, request.getDest());
        int kind = Byte.toUnsignedInt(request.getDestKind());
        checkShapeKind(kind);
        ShapeExtension shape = shape(server);
        if (shape == null) {
            throw ProtocolException.unimplemented("SHAPE is not registered");
        }
        // A None region removes the shape, which SHAPE spells as an empty rectangle list.
        List<Rectangle> rectangles = request.getRegion() == 0 ? List.of()
                : requireRegion(server, client, request.getRegion()).rectangles();
        shape.setShape(server, window, kind, rectangles, request.getXOffset(), request.getYOffset());
    }

    private void create(XServer server, ClientConnection client, int id, List<Rectangle> rectangles) {
        if (!client.ownsIdRange(id) || server.resources().contains(id)) {
            throw ProtocolException.badIdChoice(id);
        }
        server.resources().add(new RegionResource(id, client, rectangles));
    }

    private RegionResource requireRegion(XServer server, ClientConnection client, int id) {
        try {
            return (RegionResource) server.resources().require(client, id, REGION_CLASS, false);
        } catch (ProtocolException e) {
            // A region id belonging to another client stays an id choice error; anything else
            // about a region is the extension's own BadRegion.
            if (e.errorCode() == ProtocolException.ID_CHOICE) {
                throw e;
            }
            throw badRegion(id);
        }
    }

    private ProtocolException badRegion(int id) {
        return new ProtocolException((byte) (plugin.getFirstError() & 0xFF), id,
                "bad region 0x" + Integer.toHexString(id));
    }

    private static XWindow requireWindow(XServer server, ClientConnection client, int id) {
        return (XWindow) server.resources().require(client, id, XResource.CLASS_WINDOW, false);
    }

    private static void checkShapeKind(int kind) {
        if (kind < ShapeState.BOUNDING || kind > ShapeState.INPUT) {
            throw ProtocolException.badValue(kind);
        }
    }

    /** Resolves the SHAPE extension, which is where regions used as window shapes end up. */
    private ShapeExtension shape(XServer server) {
        if (shape == null) {
            shape = ShapeExtension.of(server);
        }
        return shape;
    }

    /**
     * A region is a client visible resource: the id is the client's own, and the region is what
     * {@code FetchRegion} reads back and {@code SetWindowShapeRegion} turns into a shape.
     */
    static final class RegionResource extends XResource {

        private List<Rectangle> rectangles;

        RegionResource(int id, ClientConnection owner, List<Rectangle> rectangles) {
            super(id, REGION_CLASS, owner);
            this.rectangles = List.copyOf(rectangles);
        }

        List<Rectangle> rectangles() {
            return rectangles;
        }

        void setRectangles(List<Rectangle> rectangles) {
            this.rectangles = List.copyOf(rectangles);
        }

        @Override
        public String typeName() {
            return "region";
        }
    }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    private static List<Rectangle> toAwt(
            ImmutableList<com.github.moaxcp.x11.protocol.xproto.Rectangle> source) {
        List<Rectangle> rectangles = new ArrayList<>(source.size());
        for (com.github.moaxcp.x11.protocol.xproto.Rectangle rectangle : source) {
            rectangles.add(toAwt(rectangle));
        }
        return rectangles;
    }

    private static Rectangle toAwt(com.github.moaxcp.x11.protocol.xproto.Rectangle rectangle) {
        return new Rectangle(rectangle.getX(), rectangle.getY(),
                Short.toUnsignedInt(rectangle.getWidth()),
                Short.toUnsignedInt(rectangle.getHeight()));
    }

    private static com.github.moaxcp.x11.protocol.xproto.Rectangle toProtocol(Rectangle rectangle) {
        return com.github.moaxcp.x11.protocol.xproto.Rectangle.builder()
                .x((short) rectangle.x)
                .y((short) rectangle.y)
                .width((short) rectangle.width)
                .height((short) rectangle.height)
                .build();
    }

    private static com.github.moaxcp.x11.protocol.xproto.Rectangle emptyProtocolRectangle() {
        return com.github.moaxcp.x11.protocol.xproto.Rectangle.builder().build();
    }
}
