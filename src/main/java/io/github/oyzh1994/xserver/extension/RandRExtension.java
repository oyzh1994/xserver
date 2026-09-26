package io.github.oyzh1994.xserver.extension;

import com.github.moaxcp.x11.protocol.XProtocolPlugin;
import com.github.moaxcp.x11.protocol.XRequest;
import com.github.moaxcp.x11.protocol.randr.GetCrtcGamma;
import com.github.moaxcp.x11.protocol.randr.GetCrtcGammaReply;
import com.github.moaxcp.x11.protocol.randr.GetCrtcGammaSize;
import com.github.moaxcp.x11.protocol.randr.ListOutputProperties;
import com.github.moaxcp.x11.protocol.randr.ListOutputPropertiesReply;
import com.github.moaxcp.x11.protocol.randr.GetCrtcGammaSizeReply;
import com.github.moaxcp.x11.protocol.randr.GetCrtcInfo;
import com.github.moaxcp.x11.protocol.randr.GetCrtcInfoReply;
import com.github.moaxcp.x11.protocol.randr.GetCrtcTransform;
import com.github.moaxcp.x11.protocol.randr.GetCrtcTransformReply;
import com.github.moaxcp.x11.protocol.randr.GetOutputPrimary;
import com.github.moaxcp.x11.protocol.randr.GetOutputPrimaryReply;
import com.github.moaxcp.x11.protocol.randr.GetOutputProperty;
import com.github.moaxcp.x11.protocol.randr.GetOutputPropertyReply;
import com.github.moaxcp.x11.protocol.randr.GetProviders;
import com.github.moaxcp.x11.protocol.randr.GetProvidersReply;
import com.github.moaxcp.x11.protocol.randr.GetPanning;
import com.github.moaxcp.x11.protocol.randr.GetPanningReply;
import com.github.moaxcp.x11.protocol.randr.GetMonitors;
import com.github.moaxcp.x11.protocol.randr.GetMonitorsReply;
import com.github.moaxcp.x11.protocol.randr.GetOutputInfo;
import com.github.moaxcp.x11.protocol.randr.GetOutputInfoReply;
import com.github.moaxcp.x11.protocol.randr.GetScreenInfo;
import com.github.moaxcp.x11.protocol.randr.GetScreenInfoReply;
import com.github.moaxcp.x11.protocol.randr.GetScreenResources;
import com.github.moaxcp.x11.protocol.randr.GetScreenResourcesCurrent;
import com.github.moaxcp.x11.protocol.randr.GetScreenResourcesCurrentReply;
import com.github.moaxcp.x11.protocol.randr.GetScreenResourcesReply;
import com.github.moaxcp.x11.protocol.randr.GetScreenSizeRange;
import com.github.moaxcp.x11.protocol.randr.GetScreenSizeRangeReply;
import com.github.moaxcp.x11.protocol.randr.ModeInfo;
import com.github.moaxcp.x11.protocol.randr.MonitorInfo;
import com.github.moaxcp.x11.protocol.randr.QueryVersion;
import com.github.moaxcp.x11.protocol.randr.QueryVersionReply;
import com.github.moaxcp.x11.protocol.randr.RandrPlugin;
import com.github.moaxcp.x11.protocol.randr.RefreshRates;
import com.github.moaxcp.x11.protocol.randr.ScreenChangeNotifyEvent;
import com.github.moaxcp.x11.protocol.randr.ScreenSize;
import com.github.moaxcp.x11.protocol.randr.SelectInput;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.display.Screen;

import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.eclipse.collections.impl.factory.primitive.IntLists;
import org.eclipse.collections.impl.factory.primitive.ShortLists;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RandR: the screen, described the way a modern client asks about it.
 *
 * <p>This server has exactly one screen of a size that can change while a client watches, and RandR
 * is how a client is told both things. Without it a browser or an Electron application cannot work out
 * the monitor's geometry or its device pixel ratio, a HiDPI-aware toolkit has nothing to read, and a
 * screen that grows (C1) has no way to say so - the core protocol's only answer there is a
 * {@code ConfigureNotify} for the root, which is not what any modern client listens for.
 *
 * <p>The model is deliberately the smallest one that is true: **one CRTC and one output, both covering
 * the whole screen, with one mode named after its size.** That is what a server with a single fixed
 * output has, and it is what {@code xrandr} prints for a reference server too (measured: one output
 * named {@code screen}, one mode {@code 1300x800}). The three ids are allocated from the server's own
 * resource space, so a client can pass them back in {@code GetCrtcInfo}/{@code GetOutputInfo} and be
 * answered.
 *
 * <p><b>Two places this deliberately differs from the reference.</b> The output reports a real
 * physical size, where Xvfb reports none, because this server's core setup reply already tells clients
 * a real one and RandR agreeing with it is what lets a client compute a sane DPI - the two must not
 * disagree, which is the item's own trap. And the mode's refresh rate is the server's tick rate rather
 * than Xvfb's {@code 0.00}, for the same reason: it is a number this server really has.
 *
 * <p>Version 1.6 is advertised because that is what the protocol has been since 2012 and a client that
 * asks for less is answered with its own version - the reply fields it then expects are the ones it
 * knows about, and the ones added later sit in the part of the reply a 1.1 client does not read.
 */
public final class RandRExtension implements Extension {

    /** The version every server has answered for a decade; a lower request is answered in kind. */
    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 6;

    /** {@code Rotation}'s values: the identity rotation is 1, not 0 (0 is "no rotation bits set"). */
    private static final short ROTATE_NONE = 1;
    /** Every rotation this output can do: none of them but the identity. */
    private static final short ROTATIONS = ROTATE_NONE;

    /** {@code Connection}: the output is there and working. */
    private static final byte CONNECTED = 0;
    /** {@code SubPixelOrder}: unknown, which is what an output with no physical panel reports. */
    private static final short SUBPIXEL_UNKNOWN = 0;

    /** {@code SelectInput}'s bits: the two things this server can announce. */
    private static final int SCREEN_CHANGE_MASK = 0x1;
    private static final int CRTC_CHANGE_MASK = 0x2;

    /** The output's name, which is what a reference server calls its single output. */
    private static final String OUTPUT_NAME = "screen";

    /** The gamma ramp's size: what a client expects to be able to read, and what a real one has. */
    private static final int GAMMA_SIZE = 256;

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RandRExtension.class);

    private final RandrPlugin plugin = new RandrPlugin();

    /**
     * The server, kept for its event clock. Every RandR reply carries a timestamp, and a client uses
     * one for what it is for: comparing two readings to see whether the configuration changed between
     * them. These replies used to carry the *sequence number* instead - a number that changes with
     * every request, so every reading looked like a change (measured: `xrandr --verbose` prints
     * `Timestamp: 931772` for a reference and printed a small sequence number here).
     */
    private XServer server;

    /** The three ids this output is made of, allocated once so a client can refer to them. */
    private int crtcId;
    private int outputId;
    private int modeId;

    /**
     * The atom a monitor is named by, which has to be a real one.
     *
     * <p>It is an atom id, not a string, and a client resolves it - so {@code None} is not "no name",
     * it is an atom no client can look up. Measured: {@code xrandr --listmonitors} against a reply
     * that named {@code None} died in its own library ({@code free(): invalid pointer}) rather than
     * printing anything, which is how this was found.
     */
    private int monitorName;

    /** The clients watching the screen, and the window each asked to be told through. */
    private final Map<ClientConnection, Selection> selections = new ConcurrentHashMap<>();

    private record Selection(int window, int mask) {
    }

    @Override
    public String name() {
        return "RANDR";
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
    public void init(XServer server) {
        this.server = server;
        crtcId = server.nextServerResourceId();
        outputId = server.nextServerResourceId();
        modeId = server.nextServerResourceId();
        monitorName = server.atoms().intern(OUTPUT_NAME);
    }

    @Override
    public void handleRequest(XServer server, ClientConnection client, XRequest request) {
        Screen screen = server.screenOf(client);
        if (request instanceof QueryVersion r) {
            queryVersion(client, r);
        } else if (request instanceof GetScreenSizeRange) {
            getScreenSizeRange(client, screen);
        } else if (request instanceof GetScreenResources) {
            client.sendReply(resources(client, server, screen));
        } else if (request instanceof GetScreenResourcesCurrent) {
            client.sendReply(currentResources(client, server, screen));
        } else if (request instanceof GetCrtcInfo r) {
            getCrtcInfo(client, screen, r);
        } else if (request instanceof GetCrtcGammaSize r) {
            getCrtcGammaSize(client, r);
        } else if (request instanceof GetCrtcGamma r) {
            getCrtcGamma(client, r);
        } else if (request instanceof ListOutputProperties) {
            // No properties on the output. There is nothing about it a client could set here: its
            // position and its modes are this server's, and an empty list is what says so.
            client.sendReply(ListOutputPropertiesReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .atoms(IntLists.immutable.empty())
                    .build());
        } else if (request instanceof GetOutputProperty) {
            // An output property, and this output has none (see ListOutputProperties above).
            //
            // **An empty reply, and that is measured rather than derived.** The first version of
            // this refused the request with BadName, read off Xorg's source; the reference server
            // does not do that. Measured 2026-09-26 with a hand-built request against both - a
            // valid output and a property that is not on it - Xvfb answers a *reply* with format 0,
            // type 0 (None), no items and nothing after it, and this server raised an error. The
            // difference is what a client reads: JavaFX asks for a property as it starts (it is how
            // a toolkit gets at a monitor's EDID), and an empty reply is an answer it acts on
            // where a protocol error is a line in its error handler.
            client.sendReply(GetOutputPropertyReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .format((byte) 0)
                    .type(0)
                    .bytesAfter(0)
                    .numItems(0)
                    .data(ByteLists.immutable.empty())
                    .build());
        } else if (request instanceof GetProviders) {
            // No providers: this screen has one output and it is the host window's, so there is
            // nothing to offload to and nothing to source from. An empty list is the honest answer
            // and the one a client acts on - `xrandr --listproviders` prints "number : 0" for a
            // reference server with no GPU driver too. (The provider *properties* are still refused
            // below: with no provider id to name, a client cannot ask for one.)
            client.sendReply(GetProvidersReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .timestamp(server.events().time())
                    .providers(IntLists.immutable.empty())
                    .build());
        } else if (request instanceof GetPanning r) {
            getPanning(client, screen, r);
        } else if (request instanceof GetCrtcTransform r) {
            getCrtcTransform(client, r);
        } else if (request instanceof GetOutputPrimary) {
            // No output is the primary one. With a single output covering everything the distinction
            // has nothing to say, and a reference server reports none either (measured: `xrandr
            // --verbose` prints no "Primary output" line for it).
            client.sendReply(GetOutputPrimaryReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .output(0)
                    .build());
        } else if (request instanceof GetOutputInfo r) {
            getOutputInfo(client, screen, r);
        } else if (request instanceof GetScreenInfo) {
            getScreenInfo(client, server, screen);
        } else if (request instanceof GetMonitors r) {
            getMonitors(client, screen, r);
        } else if (request instanceof SelectInput r) {
            selections.put(client, new Selection(r.getWindow(), r.getEnable()));
        } else {
            // Everything else is either a request to *change* the screen - SetScreenConfig,
            // SetCrtcConfig, CreateMode, the panning setters, the provider and lease calls - or one
            // there is nothing here to answer (an output property, which this server's outputs do
            // not have). Nothing can be changed: the screen's size is the host window's. Saying so
            // with `BadImplementation` is better than accepting a request that will not happen, and
            // it is what a client acts on rather than a half-answer. A `ProtocolException` rather
            // than a plain exception, so the refusal is one line in the log rather than a stack
            // trace: a client asking a question this server cannot answer is not a server error.
            throw ProtocolException
                    .unimplemented("RANDR " + request.getClass().getSimpleName()
                            + ": this server's screen is the host window");
        }
    }

    @Override
    public void clientDisconnected(XServer server, ClientConnection client) {
        selections.remove(client);
    }

    // ------------------------------------------------------------------
    // The screen a client is looking at
    // ------------------------------------------------------------------

    /** The reply version is the client's own when it asks for less than this server has. */
    private void queryVersion(ClientConnection client, QueryVersion r) {
        int major = (int) Math.min(MAJOR_VERSION, Integer.toUnsignedLong(r.getMajorVersion()));
        int minor = major < MAJOR_VERSION ? 0
                : (int) Math.min(MINOR_VERSION, Integer.toUnsignedLong(r.getMinorVersion()));
        client.sendReply(QueryVersionReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .majorVersion(major)
                .minorVersion(minor)
                .build());
    }

    /** How small and how large the screen may be. It never shrinks, so both ends are its size. */
    private void getScreenSizeRange(ClientConnection client, Screen screen) {
        client.sendReply(GetScreenSizeRangeReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .minWidth((short) screen.width())
                .minHeight((short) screen.height())
                .maxWidth((short) screen.width())
                .maxHeight((short) screen.height())
                .build());
    }

    /**
     * One CRTC, one output, one mode - and the names of the mode and the output, which is what the
     * reply's trailing blob is: the mode names first, in the order the mode list gives them, then the
     * output names.
     *
     * <p>The two replies a client can ask this of are different classes with the same body, and a
     * client that asked for the *current* one is given the same answer: there is no configuration in
     * progress here, because the screen is not something a client sets.
     */
    private GetScreenResourcesReply resources(ClientConnection client, XServer server, Screen screen) {
        return GetScreenResourcesReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .timestamp(server.events().time())
                .configTimestamp(client.sequenceNumber())
                .crtcs(IntLists.immutable.of(crtcId))
                .outputs(IntLists.immutable.of(outputId))
                .modes(Lists.immutable.of(mode(screen)))
                .names(ByteLists.immutable.of(names(screen)))
                .build();
    }

    private GetScreenResourcesCurrentReply currentResources(ClientConnection client, XServer server,
                                                            Screen screen) {
        return GetScreenResourcesCurrentReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .timestamp(server.events().time())
                .configTimestamp(client.sequenceNumber())
                .crtcs(IntLists.immutable.of(crtcId))
                .outputs(IntLists.immutable.of(outputId))
                .modes(Lists.immutable.of(mode(screen)))
                .names(ByteLists.immutable.of(names(screen)))
                .build();
    }

    /**
     * The one mode: a name that is its size, and no sync geometry.
     *
     * <p>A mode on a real server carries a pixel clock and the horizontal and vertical timings a CRT
     * needed. Nothing here has them - the picture is the host window's - so they are zero, which is
     * what a server whose output is not driven by a client reports.
     */
    private ModeInfo mode(Screen screen) {
        return ModeInfo.builder()
                .id(modeId)
                .width((short) screen.width())
                .height((short) screen.height())
                .dotClock(0)
                .hsyncStart((short) 0)
                .hsyncEnd((short) 0)
                .htotal((short) 0)
                .hskew((short) 0)
                .vsyncStart((short) 0)
                .vsyncEnd((short) 0)
                .vtotal((short) 0)
                .nameLen((short) modeName(screen).length())
                .modeFlags(0)
                .build();
    }

    /** The mode's name, then the output's, each NUL terminated: the blob's two names. */
    private static byte[] names(Screen screen) {
        return (modeName(screen) + "\0" + OUTPUT_NAME + "\0").getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The CRTC covers the whole screen, with the one mode. */
    private void getCrtcInfo(ClientConnection client, Screen screen, GetCrtcInfo r) {
        int status = r.getCrtc() == crtcId ? 0 : 1;     // 0 Success, 1 BadValue for another CRTC
        client.sendReply(GetCrtcInfoReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status((byte) status)
                .timestamp(server.events().time())
                .x((short) 0)
                .y((short) 0)
                .width((short) screen.width())
                .height((short) screen.height())
                .mode(status == 0 ? modeId : 0)
                .rotation(ROTATE_NONE)
                .rotations(ROTATIONS)
                .outputs(IntLists.immutable.of(outputId))
                .possible(IntLists.immutable.of(outputId))
                .build());
    }

    /**
     * The gamma ramp's size, and the ramp itself.
     *
     * <p>A ramp of {@link #GAMMA_SIZE} entries that maps every input to itself - the identity, which
     * is what "no gamma applied" looks like when a client asks. The alternative, a size of zero, is
     * what a server with no ramp would honestly say, but a size of zero is what clients read as
     * <em>an error</em>: {@code xrandr} prints "Failed to get size of gamma" and gives up on the rest
     * of the output's detail (measured on this server before the ramp was there). No other server
     * reports zero either - a reference answers a full ramp and prints {@code Gamma: 1.0:1.0:1.0} for
     * it.
     */
    private void getCrtcGammaSize(ClientConnection client, GetCrtcGammaSize r) {
        client.sendReply(GetCrtcGammaSizeReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .size((short) GAMMA_SIZE)
                .build());
    }

    private void getCrtcGamma(ClientConnection client, GetCrtcGamma r) {
        short[] ramp = new short[GAMMA_SIZE];
        for (int i = 0; i < GAMMA_SIZE; i++) {
            // The identity: input i scaled to the sixteen bit a ramp entry carries.
            ramp[i] = (short) Math.round(i * 65535.0 / (GAMMA_SIZE - 1));
        }
        client.sendReply(GetCrtcGammaReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .red(ShortLists.immutable.of(ramp))
                .green(ShortLists.immutable.of(ramp))
                .blue(ShortLists.immutable.of(ramp))
                .build());
    }

    /**
     * The CRTC's transform: the identity, and no filter.
     *
     * <p>A transform is what a client sets to scale or rotate the output's picture, and a server with
     * no transform hardware reports the identity rather than refusing - which is what a client asks
     * for when it wants to know whether anything is scaled. The matrix is sixteen point sixteen fixed
     * point, so one is {@code 1 << 16}.
     */
    private void getCrtcTransform(ClientConnection client, GetCrtcTransform r) {
        com.github.moaxcp.x11.protocol.render.Transform identity =
                com.github.moaxcp.x11.protocol.render.Transform.builder()
                        .matrix11(1 << 16)
                        .matrix12(0)
                        .matrix13(0)
                        .matrix21(0)
                        .matrix22(1 << 16)
                        .matrix23(0)
                        .matrix31(0)
                        .matrix32(0)
                        .matrix33(1 << 16)
                        .build();
        client.sendReply(GetCrtcTransformReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .pendingTransform(identity)
                .hasTransforms(r.getCrtc() == crtcId)
                .currentTransform(identity)
                .pendingFilterName(ByteLists.immutable.empty())
                .pendingParams(IntLists.immutable.empty())
                .currentFilterName(ByteLists.immutable.empty())
                .currentParams(IntLists.immutable.empty())
                .build());
    }

    /**
     * Panning: the mode is the screen, and there is nothing to pan around.
     *
     * <p>The reply says so with the whole screen as both the visible area and the track area, and no
     * borders - which is what a client reads as "this output is showing exactly what it is". A client
     * that gets an error here instead dies in its own library rather than reporting the display as
     * un-pannable (measured with {@code xrandr}: an error for this request is where it segfaults).
     */
    private void getPanning(ClientConnection client, Screen screen, GetPanning r) {
        short width = (short) screen.width();
        short height = (short) screen.height();
        client.sendReply(GetPanningReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status((byte) (r.getCrtc() == crtcId ? 0 : 1))
                .timestamp(server.events().time())
                .left((short) 0)
                .top((short) 0)
                .width(width)
                .height(height)
                .trackLeft((short) 0)
                .trackTop((short) 0)
                .trackWidth(width)
                .trackHeight(height)
                .borderLeft((short) 0)
                .borderTop((short) 0)
                .borderRight((short) 0)
                .borderBottom((short) 0)
                .build());
    }

    /** The output: connected, on the one CRTC, offering the one mode, with a real physical size. */
    private void getOutputInfo(ClientConnection client, Screen screen, GetOutputInfo r) {
        int status = r.getOutput() == outputId ? 0 : 1;
        if (log.isDebugEnabled()) {
            // The ids a client refers to are the ones this server handed out, and a client that asks
            // about one of them has to be answered as if it existed: a wrong status here is a client
            // that gives up on the output's detail without saying why.
            log.debug("RANDR GetOutputInfo for 0x{}: the output is 0x{}, status {}", 
                    Integer.toHexString(r.getOutput()), Integer.toHexString(outputId), status);
        }
        byte[] name = OUTPUT_NAME.getBytes(StandardCharsets.ISO_8859_1);
        client.sendReply(GetOutputInfoReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .status((byte) status)
                .timestamp(server.events().time())
                .crtc(status == 0 ? crtcId : 0)
                // The same millimetres the core setup reply reports: a client computing a DPI reads
                // both, and two answers that disagree are worse than no answer.
                .mmWidth((int) screen.widthMillimeters())
                .mmHeight((int) screen.heightMillimeters())
                .connection(CONNECTED)
                .subpixelOrder((byte) SUBPIXEL_UNKNOWN)
                .numPreferred((short) 1)
                .crtcs(IntLists.immutable.of(crtcId))
                // The output carries no modes of its own, which is what a reference server reports for
                // its single output (measured: `RandrProbe` reads "1 crtc(s), 0 mode(s)" there and read
                // "1 mode(s)" here). The mode is on the CRTC, which is where a client that wants one
                // looks - `GetCrtcInfo` answers it - and the output's list is the *optional* list of
                // modes it could be set to, which for an output the host window owns is nothing.
                .modes(IntLists.immutable.empty())
                .clones(IntLists.immutable.empty())
                .name(ByteLists.immutable.of(name))
                .build());
    }

    /** The screen's descriptor, whose sizes and rates are the one this server has. */
    private void getScreenInfo(ClientConnection client, XServer server, Screen screen) {
        RefreshRates rates = RefreshRates.builder()
                .rates(ShortLists.immutable.of((short) server.options().tickRate()))
                .build();
        client.sendReply(GetScreenInfoReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .rotations((byte) ROTATIONS)
                .root(screen.root().id())
                .timestamp(server.events().time())
                .configTimestamp(client.sequenceNumber())
                .sizeID((short) 0)
                .rotation(ROTATE_NONE)
                .rate((short) server.options().tickRate())
                .nInfo((short) 1)
                .sizes(Lists.immutable.of(ScreenSize.builder()
                        .width((short) screen.width())
                        .height((short) screen.height())
                        .mwidth((short) screen.widthMillimeters())
                        .mheight((short) screen.heightMillimeters())
                        .build()))
                .rates(Lists.immutable.of(rates))
                .build());
    }

    /** One monitor: the whole screen, automatically, not the primary (there is only one). */
    private void getMonitors(ClientConnection client, Screen screen, GetMonitors r) {
        MonitorInfo monitor = MonitorInfo.builder()
                .name(monitorName)
                .primary(false)
                .automatic(true)
                .x((short) 0)
                .y((short) 0)
                .width((short) screen.width())
                .height((short) screen.height())
                .widthInMillimeters(screen.widthMillimeters())
                .heightInMillimeters(screen.heightMillimeters())
                .outputs(IntLists.immutable.of(outputId))
                .build();
        client.sendReply(GetMonitorsReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .timestamp(server.events().time())
                // The reply's own output count, which is not the same thing as each monitor's: a
                // client reads it to know how far the array reaches, and a zero here made
                // `xrandr --listmonitors` die in its own library (measured) rather than print
                // anything at all.
                .nOutputs(1)
                .monitors(Lists.immutable.of(monitor))
                .build());
    }

    // ------------------------------------------------------------------
    // Telling a client the screen changed
    // ------------------------------------------------------------------

    /**
     * A screen that changed size, as the clients that asked to hear it are told.
     *
     * <p>Called from the core's own "the root is a different size" path (C1's screen growth), because
     * that is the one thing here that changes the screen: the host window was dragged, the framebuffer
     * grew, and a client that lays itself out against the display - a toolkit, a terminal that
     * reflows - has to hear about it. This is the event such a client actually listens for.
     */
    public void screenChanged(XServer server, Screen screen) {
        for (Map.Entry<ClientConnection, Selection> entry : selections.entrySet()) {
            ClientConnection client = entry.getKey();
            Selection selection = entry.getValue();
            if ((selection.mask() & SCREEN_CHANGE_MASK) == 0 || client.isClosed()) {
                continue;
            }
            server.events().send(client, ScreenChangeNotifyEvent.builder()
                    .firstEventOffset(firstEvent())
                    .sentEvent(false)
                    .sequenceNumber(client.sequenceNumber())
                    .rotation((byte) ROTATE_NONE)
                    .timestamp(server.events().time())
                    .configTimestamp(server.events().time())
                    .root(screen.root().id())
                    .requestWindow(selection.window())
                    .sizeID((short) 0)
                    .subpixelOrder(SUBPIXEL_UNKNOWN)
                    .width((short) screen.width())
                    .height((short) screen.height())
                    .mwidth((short) screen.widthMillimeters())
                    .mheight((short) screen.heightMillimeters())
                    .build());
        }
    }

    /** The mode's name is its size, which is what a reference server calls its one mode too. */
    private static String modeName(Screen screen) {
        return screen.width() + "x" + screen.height();
    }
}
