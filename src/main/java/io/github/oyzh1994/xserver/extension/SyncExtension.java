package io.github.oyzh1994.xserver.extension;

import com.github.moaxcp.x11.protocol.XProtocolPlugin;
import com.github.moaxcp.x11.protocol.XRequest;
import com.github.moaxcp.x11.protocol.sync.AwaitFence;
import com.github.moaxcp.x11.protocol.sync.ChangeCounter;
import com.github.moaxcp.x11.protocol.sync.CreateCounter;
import com.github.moaxcp.x11.protocol.sync.CreateFence;
import com.github.moaxcp.x11.protocol.sync.DestroyCounter;
import com.github.moaxcp.x11.protocol.sync.DestroyFence;
import com.github.moaxcp.x11.protocol.sync.GetPriority;
import com.github.moaxcp.x11.protocol.sync.GetPriorityReply;
import com.github.moaxcp.x11.protocol.sync.Initialize;
import com.github.moaxcp.x11.protocol.sync.InitializeReply;
import com.github.moaxcp.x11.protocol.sync.Int64;
import com.github.moaxcp.x11.protocol.sync.ListSystemCounters;
import com.github.moaxcp.x11.protocol.sync.ListSystemCountersReply;
import com.github.moaxcp.x11.protocol.sync.QueryCounter;
import com.github.moaxcp.x11.protocol.sync.QueryCounterReply;
import com.github.moaxcp.x11.protocol.sync.QueryFence;
import com.github.moaxcp.x11.protocol.sync.QueryFenceReply;
import com.github.moaxcp.x11.protocol.sync.ResetFence;
import com.github.moaxcp.x11.protocol.sync.SetCounter;
import com.github.moaxcp.x11.protocol.sync.SetPriority;
import com.github.moaxcp.x11.protocol.sync.SyncPlugin;
import com.github.moaxcp.x11.protocol.sync.TriggerFence;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;
import io.github.oyzh1994.xserver.client.ProtocolException;
import io.github.oyzh1994.xserver.resource.XResource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SYNC: counters and fences, which is how a client asks the server "has this happened yet".
 *
 * <p>A fence is what a toolkit reaches for when it wants to know that a frame has been *seen* rather
 * than merely sent: it triggers the fence, hands it to the server with the drawing, and waits. This
 * server draws synchronously - every request is finished, on the screen and in the window's store,
 * before the next is read - so **a fence is already triggered when the request that would have waited
 * on it is read**, and both {@code AwaitFence} and {@code Await} return without delaying anything.
 * That is not a stub: it is the truth about a server with no asynchronous pipeline in it, and it is
 * what a client that wanted the wait gets.
 *
 * <p>Counters are the other half, and they are real values here: a client creates one, sets it,
 * changes it by a delta and reads it back, with the 64 bit arithmetic X uses (the wire carries an
 * {@code INT64} as two words, and it is signed).
 *
 * <p><b>The version is the server's, measured.</b> {@code Initialize} is answered 3.1 both to a
 * client that asks for 3.1 and to one that asks for 3.0 ({@code tools/SyncProbe} against Xvfb), where
 * the obvious implementation echoes the client's own version - which would tell a 3.0 client that
 * {@code AwaitFence} (a 3.1 request) is available to it. The same rule as Composite's
 * {@code QueryVersion}, and the same measurement.
 *
 * <p><b>{@code ListSystemCounters} answers none, deliberately.</b> A reference keeps eight - they are
 * elapsed-time counters for its own scheduling - and this server has no such notion: it has no
 * scheduler to report on and no server-side clock a client would act on. Answering a count with no
 * entries, or entries a client cannot parse, would be worse than answering none, and the alternative
 * - packing the reference's own variable-length entries by trial - is what B6's entry records as the
 * way a client ends up reading its own field boundaries wrong. It is one line of the probe, and it is
 * listed in {@code tools/README.md} as a deliberate difference.
 */
public final class SyncExtension implements Extension {

    /** Measured: the reference answers 3.1 to a client asking for 3.1 and to one asking for 3.0. */
    private static final int MAJOR_VERSION = 3;
    private static final int MINOR_VERSION = 1;

    private final SyncPlugin plugin = new SyncPlugin();

    /** The priority each client set for itself, which is what {@code GetPriority}/{@code SetPriority}
     * are about - a client asks about its own scheduling, not another connection's. */
    private final Map<ClientConnection, Integer> priorities = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "SYNC";
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
        if (request instanceof Initialize init) {
            client.sendReply(InitializeReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .majorVersion((byte) MAJOR_VERSION)
                    .minorVersion((byte) MINOR_VERSION)
                    .build());
        } else if (request instanceof ListSystemCounters) {
            client.sendReply(ListSystemCountersReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .counters(org.eclipse.collections.impl.factory.Lists.immutable.empty())
                    .build());
        } else if (request instanceof CreateCounter r) {
            counter(server, client, r.getId(), value(r.getInitialValue()));
        } else if (request instanceof SetCounter r) {
            counterResource(server, client, r.getCounter()).setValue(value(r.getValue()));
        } else if (request instanceof ChangeCounter r) {
            CounterResource counter = counterResource(server, client, r.getCounter());
            counter.setValue(counter.value() + value(r.getAmount()));
        } else if (request instanceof QueryCounter r) {
            long v = counterResource(server, client, r.getCounter()).value();
            client.sendReply(QueryCounterReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .counterValue(int64(v))
                    .build());
        } else if (request instanceof DestroyCounter r) {
            counterResource(server, client, r.getCounter());
            server.resources().free(r.getCounter());
        } else if (request instanceof SetPriority r) {
            priorities.put(client, r.getPriority());
        } else if (request instanceof GetPriority) {
            client.sendReply(GetPriorityReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .priority(priorities.getOrDefault(client, 0))
                    .build());
        } else if (request instanceof CreateFence r) {
            // The drawable is named because a fence belongs to a screen; it is validated so a client
            // that names a window that does not exist hears about it, which is what the reference
            // does - and it is how the field order was found: a probe that had fence and drawable the
            // other way round was answered BadDrawable on its own fence id.
            server.resources().requireDrawable(client, r.getDrawable());
            server.resources().add(new FenceResource(r.getFence(), client, r.isInitiallyTriggered()));
        } else if (request instanceof TriggerFence r) {
            fence(server, client, r.getFence()).setTriggered(true);
        } else if (request instanceof ResetFence r) {
            fence(server, client, r.getFence()).setTriggered(false);
        } else if (request instanceof DestroyFence r) {
            fence(server, client, r.getFence());
            server.resources().free(r.getFence());
        } else if (request instanceof QueryFence r) {
            client.sendReply(QueryFenceReply.builder()
                    .sequenceNumber(client.sequenceNumber())
                    .triggered(fence(server, client, r.getFence()).isTriggered())
                    .build());
        } else if (request instanceof AwaitFence r) {
            // Never waits: everything this server was asked to do is already done by the time the
            // request is read, so every fence named here is either triggered or will never be. The
            // fences are still resolved, so a client that names one it never created hears BadFence.
            org.eclipse.collections.api.list.primitive.IntList fences = r.getFenceList();
            for (int i = 0; i < fences.size(); i++) {
                fence(server, client, fences.get(i));
            }
        } else {
            throw new IllegalArgumentException("unhandled SYNC request " + request.getClass());
        }
    }

    @Override
    public void clientDisconnected(XServer server, ClientConnection client) {
        priorities.remove(client);
    }

    // ------------------------------------------------------------------

    private void counter(XServer server, ClientConnection client, int id, long initial) {
        if (!client.ownsIdRange(id) || server.resources().contains(id)) {
            throw ProtocolException.badIdChoice(id);
        }
        server.resources().add(new CounterResource(id, client, initial));
    }

    private CounterResource counterResource(XServer server, ClientConnection client, int id) {
        return (CounterResource) server.resources().require(client, id, CounterResource.CLASS, false);
    }

    private FenceResource fence(XServer server, ClientConnection client, int id) {
        return (FenceResource) server.resources().require(client, id, FenceResource.CLASS, false);
    }

    /** The wire's {@code INT64}: two words, signed, low word first. */
    private static long value(Int64 value) {
        return (value.getLo() & 0xFFFFFFFFL) | ((long) value.getHi() << 32);
    }

    private static Int64 int64(long value) {
        return Int64.builder().lo((int) value).hi((int) (value >> 32)).build();
    }

    /** A counter a client created: a 64 bit value, which only this server writes. */
    static final class CounterResource extends XResource {

        static final int CLASS = XResource.newExtensionClass();

        private long value;

        CounterResource(int id, ClientConnection owner, long value) {
            super(id, CLASS, owner);
            this.value = value;
        }

        long value() {
            return value;
        }

        void setValue(long value) {
            this.value = value;
        }

        @Override
        public String typeName() {
            return "counter";
        }
    }

    /**
     * A fence: one bit that a client sets and the server reports, and that a client waits on.
     *
     * <p>The waiting is the part this server cannot honestly do and does not pretend to: there is
     * nothing asynchronous for a fence to be waiting for, so {@code AwaitFence} finds every fence
     * already in its final state.
     */
    static final class FenceResource extends XResource {

        static final int CLASS = XResource.newExtensionClass();

        private boolean triggered;

        FenceResource(int id, ClientConnection owner, boolean triggered) {
            super(id, CLASS, owner);
            this.triggered = triggered;
        }

        boolean isTriggered() {
            return triggered;
        }

        void setTriggered(boolean triggered) {
            this.triggered = triggered;
        }

        @Override
        public String typeName() {
            return "fence";
        }
    }
}
