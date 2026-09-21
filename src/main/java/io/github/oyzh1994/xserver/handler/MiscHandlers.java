package io.github.oyzh1994.xserver.handler;

import com.github.moaxcp.x11.protocol.xproto.Bell;
import com.github.moaxcp.x11.protocol.xproto.ChangeHosts;
import com.github.moaxcp.x11.protocol.xproto.ChangeKeyboardControl;
import com.github.moaxcp.x11.protocol.xproto.ChangePointerControl;
import com.github.moaxcp.x11.protocol.xproto.ForceScreenSaver;
import com.github.moaxcp.x11.protocol.xproto.GetKeyboardControl;
import com.github.moaxcp.x11.protocol.xproto.GetKeyboardControlReply;
import com.github.moaxcp.x11.protocol.xproto.GetPointerControl;
import com.github.moaxcp.x11.protocol.xproto.GetPointerControlReply;
import com.github.moaxcp.x11.protocol.xproto.GetScreenSaver;
import com.github.moaxcp.x11.protocol.xproto.GetScreenSaverReply;
import com.github.moaxcp.x11.protocol.xproto.GrabServer;
import com.github.moaxcp.x11.protocol.xproto.Host;
import com.github.moaxcp.x11.protocol.xproto.KillClient;
import com.github.moaxcp.x11.protocol.xproto.ListExtensions;
import com.github.moaxcp.x11.protocol.xproto.ListExtensionsReply;
import com.github.moaxcp.x11.protocol.xproto.ListHosts;
import com.github.moaxcp.x11.protocol.xproto.ListHostsReply;
import com.github.moaxcp.x11.protocol.xproto.QueryExtension;
import com.github.moaxcp.x11.protocol.xproto.QueryExtensionReply;
import com.github.moaxcp.x11.protocol.xproto.SetAccessControl;
import com.github.moaxcp.x11.protocol.xproto.SetCloseDownMode;
import com.github.moaxcp.x11.protocol.xproto.SetScreenSaver;
import com.github.moaxcp.x11.protocol.xproto.Str;
import com.github.moaxcp.x11.protocol.xproto.UngrabServer;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.event.EventMasks;
import io.github.oyzh1994.xserver.extension.Extension;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.eclipse.collections.impl.factory.primitive.IntLists;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Server level requests: extension lookup, screen saver and pointer/keyboard tuning, access
 * control, and the connection control requests.
 */
public final class MiscHandlers {

    private final XServer server;

    public MiscHandlers(XServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Extensions
    // ------------------------------------------------------------------

    public void queryExtension(ClientConnection client, QueryExtension r) {
        String name = ascii(r.getName());
        Extension extension = server.extensions().byName(name);
        boolean present = extension != null;
        client.sendReply(QueryExtensionReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .present(present)
                .majorOpcode(present ? extension.majorOpcode() : (byte) 0)
                .firstEvent(present ? extension.firstEvent() : (byte) 0)
                .firstError(present ? extension.firstError() : (byte) 0)
                .build());
    }

    public void listExtensions(ClientConnection client, ListExtensions r) {
        List<Str> names = new ArrayList<>();
        for (String name : server.extensions().names()) {
            names.add(Str.builder()
                    .name(ByteLists.immutable.of(name.getBytes(StandardCharsets.ISO_8859_1)))
                    .build());
        }
        client.sendReply(ListExtensionsReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .names(Lists.immutable.withAll(names))
                .build());
    }

    // ------------------------------------------------------------------
    // Connection control
    // ------------------------------------------------------------------

    public void grabServer(ClientConnection client, GrabServer r) {
        // The request is already running with the lock held; the grab takes one more hold so it
        // survives this request and blocks every other client until UngrabServer.
        server.acquireServerGrab(client);
    }

    public void ungrabServer(ClientConnection client, UngrabServer r) {
        // A client that never grabbed, or one that has already released every hold, is ignored.
        server.releaseServerGrab(client);
    }

    public void setCloseDownMode(ClientConnection client, SetCloseDownMode r) {
        int mode = r.getMode();
        if (mode < 0 || mode > 2) {
            throw ProtocolException.badValue(mode);
        }
        client.setCloseDownMode(mode);
    }

    public void killClient(ClientConnection client, KillClient r) {
        int resource = r.getResource();
        if (resource == 0) {
            // AllTemporary: drop resources a client asked to keep after disconnecting.
            for (ClientConnection other : server.clients()) {
                if (other != client && other.closeDownMode() != 2) {
                    continue;
                }
                server.resources().freeAll(other);
            }
            return;
        }
        for (ClientConnection other : server.clients()) {
            if (other != client && other.ownsIdRange(resource)) {
                other.close();
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Screen saver, pointer and keyboard tuning
    // ------------------------------------------------------------------

    public void bell(ClientConnection client, Bell r) {
        server.input().ringBell(r.getPercent());
    }

    public void getPointerControl(ClientConnection client, GetPointerControl r) {
        client.sendReply(GetPointerControlReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .accelerationNumerator((short) 2)
                .accelerationDenominator((short) 1)
                .threshold((short) 4)
                .build());
    }

    public void changePointerControl(ClientConnection client, ChangePointerControl r) {
        int numerator = r.getAccelerationNumerator();
        int denominator = r.getAccelerationDenominator();
        if (denominator == 0 && numerator != -1) {
            throw ProtocolException.badValue(denominator);
        }
        if (r.getThreshold() < -1) {
            throw ProtocolException.badValue(r.getThreshold());
        }
        // Accepted and ignored: pointer acceleration is a host concern, not the clients'.
    }

    public void getScreenSaver(ClientConnection client, GetScreenSaver r) {
        client.sendReply(GetScreenSaverReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .timeout((short) 600)
                .interval((short) 600)
                .preferBlanking((byte) 1)
                .allowExposures((byte) 1)
                .build());
    }

    public void setScreenSaver(ClientConnection client, SetScreenSaver r) {
        if (r.getTimeout() < -1 || r.getInterval() < -1) {
            throw ProtocolException.badValue(r.getTimeout());
        }
        // The host window owns blanking; the server accepts the settings and does nothing.
    }

    public void forceScreenSaver(ClientConnection client, ForceScreenSaver r) {
        int mode = r.getMode();
        if (mode != 0 && mode != 1) {
            throw ProtocolException.badValue(mode);
        }
    }

    public void getKeyboardControl(ClientConnection client, GetKeyboardControl r) {
        byte[] autoRepeats = new byte[32];
        java.util.Arrays.fill(autoRepeats, (byte) 1);
        client.sendReply(GetKeyboardControlReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .globalAutoRepeat((byte) 1)
                .ledMask(0)
                .keyClickPercent((byte) 0)
                .bellPercent((byte) 50)
                .bellPitch((short) 400)
                .bellDuration((short) 100)
                .autoRepeats(ByteLists.immutable.of(autoRepeats))
                .build());
    }

    public void changeKeyboardControl(ClientConnection client, ChangeKeyboardControl r) {
        // LED and bell settings are accepted and not acted on; there is no host keyboard to drive.
    }

    // ------------------------------------------------------------------
    // Access control
    // ------------------------------------------------------------------

    public void changeHosts(ClientConnection client, ChangeHosts r) {
        // Host based access control is not enforced: the display is either open or requires a
        // cookie, and adding hosts after startup would not change that.
    }

    public void listHosts(ClientConnection client, ListHosts r) {
        List<Host> hosts = new ArrayList<>();
        if (server.options().listenAll()) {
            hosts.add(Host.builder()
                    .family((byte) 0)
                    .address(ByteLists.immutable.empty())
                    .build());
        }
        client.sendReply(ListHostsReply.builder()
                .sequenceNumber(client.sequenceNumber())
                .mode((byte) (server.options().authRequired() ? 0 : 1))
                .hosts(Lists.immutable.withAll(hosts))
                .build());
    }

    public void setAccessControl(ClientConnection client, SetAccessControl r) {
        if (r.getMode() != 0 && r.getMode() != 1) {
            throw ProtocolException.badValue(r.getMode());
        }
    }

    private static String ascii(org.eclipse.collections.api.list.primitive.ByteList bytes) {
        return new String(bytes.toArray(), StandardCharsets.ISO_8859_1);
    }
}
