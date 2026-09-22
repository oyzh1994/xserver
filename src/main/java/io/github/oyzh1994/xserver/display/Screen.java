package io.github.oyzh1994.xserver.display;

import com.github.moaxcp.x11.protocol.xproto.Depth;
import com.github.moaxcp.x11.protocol.xproto.Format;
import com.github.moaxcp.x11.protocol.xproto.Visualtype;
import io.github.oyzh1994.xserver.draw.Framebuffer;
import io.github.oyzh1994.xserver.resource.XColormap;
import io.github.oyzh1994.xserver.resource.XCursor;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.impl.factory.Lists;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One screen: its framebuffer, the visuals it offers and its root window.
 *
 * <p>Only a single screen is exposed, which is what essentially every client expects; the type
 * exists so multi-screen support is a matter of adding entries rather than a rewrite.
 */
public final class Screen {

    /** Size of the root window's border, always zero by convention. */
    public static final int ROOT_BORDER = 0;

    private final int number;
    private final int width;
    private final int height;
    private final int widthMillimeters;
    private final int heightMillimeters;
    private final Framebuffer framebuffer;
    private final List<VisualInfo> visuals;
    private final List<DepthInfo> depths;
    private final int rootDepth;
    private final VisualInfo rootVisual;

    private XWindow root;
    private XColormap defaultColormap;
    /** The cursor a window on this screen gets when it has none of its own. */
    private XCursor rootCursor;
    private int whitePixel;
    private int blackPixel;

    public Screen(int number, int width, int height, List<VisualInfo> visuals, int rootDepth,
                  int rootVisualId) {
        this.number = number;
        this.width = width;
        this.height = height;
        // Report a plausible physical size at roughly 96 dpi so clients compute sane font sizes.
        this.widthMillimeters = (int) Math.round(width * 25.4 / 96.0);
        this.heightMillimeters = (int) Math.round(height * 25.4 / 96.0);
        this.visuals = List.copyOf(visuals);
        this.depths = groupByDepth(this.visuals);
        this.rootDepth = rootDepth;
        this.rootVisual = visuals.stream()
                .filter(v -> v.id() == rootVisualId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no visual 0x"
                        + Integer.toHexString(rootVisualId)));
        this.framebuffer = new Framebuffer(width, height, 0xFF000000);
    }

    private static List<DepthInfo> groupByDepth(List<VisualInfo> visuals) {
        Map<Integer, List<VisualInfo>> byDepth = new LinkedHashMap<>();
        for (VisualInfo visual : visuals) {
            byDepth.computeIfAbsent(visual.depth(), k -> new ArrayList<>()).add(visual);
        }
        List<DepthInfo> result = new ArrayList<>();
        byDepth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(new DepthInfo(entry.getKey(), entry.getValue())));
        return result;
    }

    public int number() {
        return number;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Framebuffer framebuffer() {
        return framebuffer;
    }

    public List<VisualInfo> visuals() {
        return visuals;
    }

    public List<DepthInfo> depths() {
        return depths;
    }

    public int rootDepth() {
        return rootDepth;
    }

    public VisualInfo rootVisual() {
        return rootVisual;
    }

    public VisualInfo visualById(int id) {
        for (VisualInfo visual : visuals) {
            if (visual.id() == id) {
                return visual;
            }
        }
        return null;
    }

    /** The visual a new window of the given depth gets when the client says "copy from parent". */
    public VisualInfo defaultVisualForDepth(int depth) {
        if (depth == rootDepth) {
            return rootVisual;
        }
        for (DepthInfo info : depths) {
            if (info.depth() == depth && !info.visuals().isEmpty()) {
                return info.visuals().get(0);
            }
        }
        return null;
    }

    public boolean supportsDepth(int depth) {
        for (DepthInfo info : depths) {
            if (info.depth() == depth) {
                return true;
            }
        }
        return false;
    }

    public XWindow root() {
        return root;
    }

    public void setRoot(XWindow root) {
        this.root = root;
    }

    public XCursor rootCursor() {
        return rootCursor;
    }

    public void setRootCursor(XCursor rootCursor) {
        this.rootCursor = rootCursor;
    }

    public XColormap defaultColormap() {
        return defaultColormap;
    }

    public void setDefaultColormap(XColormap defaultColormap) {
        this.defaultColormap = defaultColormap;
    }

    public int whitePixel() {
        return whitePixel;
    }

    public void setWhitePixel(int whitePixel) {
        this.whitePixel = whitePixel;
    }

    public int blackPixel() {
        return blackPixel;
    }

    public void setBlackPixel(int blackPixel) {
        this.blackPixel = blackPixel;
    }

    /**
     * Pixmap formats advertised in the connection setup: how many bits each depth occupies in an
     * image. Depth 24 is stored in 32 bits, which is what every real server reports and what
     * clients use to size their {@code PutImage} buffers - reporting 24 would make them send three
     * bytes per pixel into a four byte slot.
     */
    public List<Format> pixmapFormats() {
        List<Format> formats = new ArrayList<>();
        for (DepthInfo info : depths) {
            int depth = info.depth();
            int bitsPerPixel = switch (depth) {
                case 1 -> 1;
                case 24, 30, 32 -> 32;
                default -> depth;
            };
            formats.add(Format.builder()
                    .depth((byte) depth)
                    .bitsPerPixel((byte) bitsPerPixel)
                    .scanlinePad((byte) (bitsPerPixel == 1 ? 8 : 32))
                    .build());
        }
        return formats;
    }

    /** The {@code Screen} struct sent during connection setup. */
    public com.github.moaxcp.x11.protocol.xproto.Screen toProtocolScreen(int rootWindowId,
                                                                        int defaultColormapId) {
        List<Depth> protocolDepths = new ArrayList<>();
        for (DepthInfo info : depths) {
            List<Visualtype> visualtypes = new ArrayList<>();
            for (VisualInfo visual : info.visuals()) {
                visualtypes.add(Visualtype.builder()
                        .visualId(visual.id())
                        .clazz(visual.visualClass())
                        .bitsPerRgbValue(visual.bitsPerRgbValue())
                        .colormapEntries(visual.colormapEntries())
                        .redMask(visual.redMask())
                        .greenMask(visual.greenMask())
                        .blueMask(visual.blueMask())
                        .build());
            }
            protocolDepths.add(Depth.builder()
                    .depth((byte) info.depth())
                    .visuals(Lists.immutable.withAll(visualtypes))
                    .build());
        }
        return com.github.moaxcp.x11.protocol.xproto.Screen.builder()
                .root(rootWindowId)
                .defaultColormap(defaultColormapId)
                .whitePixel(whitePixel)
                .blackPixel(blackPixel)
                .currentInputMasks(0)
                .widthInPixels((short) width)
                .heightInPixels((short) height)
                .widthInMillimeters((short) widthMillimeters)
                .heightInMillimeters((short) heightMillimeters)
                .minInstalledMaps((short) 1)
                .maxInstalledMaps((short) 1)
                .rootVisual(rootVisual.id())
                .backingStores((byte) 0)
                .saveUnders(false)
                .rootDepth((byte) rootDepth)
                .allowedDepths(Lists.immutable.withAll(protocolDepths))
                .build();
    }

    /** The visuals available at a given depth, grouped for the setup reply. */
    public record DepthInfo(int depth, List<VisualInfo> visuals) {
    }
}
