package io.github.oyzh1994.xserver.client;

import com.github.moaxcp.x11.protocol.X11BigEndianInputStream;
import com.github.moaxcp.x11.protocol.X11Input;
import com.github.moaxcp.x11.protocol.X11LittleEndianInputStream;
import com.github.moaxcp.x11.protocol.XProtocolPlugin;
import com.github.moaxcp.x11.protocol.xproto.SetupRequest;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.net.ClientOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;

/**
 * One connected client: its socket, its byte order, its resource id range and its request loop.
 *
 * <p>Each connection runs on its own thread. Requests are decoded by the generated protocol
 * classes and executed while holding the server lock, so the resource model never needs finer
 * locking than that.
 */
public final class ClientConnection implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    /** Client index occupies the top bits of a resource id, giving 256 clients. */
    public static final int CLIENT_BITS = 8;
    public static final int CLIENT_SHIFT = 29 - CLIENT_BITS;
    public static final int MAX_CLIENTS = 1 << CLIENT_BITS;
    /** Bits available for a single client's resources. This is the mask reported in the setup. */
    public static final int RESOURCE_MASK = (1 << CLIENT_SHIFT) - 1;

    private static final int MAX_REQUEST_UNITS = 0xFFFF;
    private static final int MAX_BIG_REQUEST_UNITS = (1 << 22) - 1;

    private final XServer server;
    private final Socket socket;
    private final SocketChannel channel;
    private final String peer;
    private final int index;

    /** Inclusive range of resource ids this client may use. */
    private final int resourceIdBase;

    private X11Input in;
    private OutputStream rawOut;
    private ClientOutput output;

    private boolean bigEndian;
    private boolean bigRequestsEnabled;
    private boolean closed;

    private short sequenceNumber;
    private byte currentOpcode;
    private byte currentMinorOpcode;

    /** Next id handed out on the client's behalf for server-created resources. */
    private int nextServerId;

    private int closeDownMode;

    /** Set while a client connection is being torn down, so late events are dropped. */
    private volatile boolean shuttingDown;

    public ClientConnection(XServer server, Socket socket, int index) throws IOException {
        this(server, socket, null, index);
    }

    public ClientConnection(XServer server, SocketChannel channel, int index) throws IOException {
        this(server, null, channel, index);
    }

    private ClientConnection(XServer server, Socket socket, SocketChannel channel, int index)
            throws IOException {
        this.server = server;
        this.socket = socket;
        this.channel = channel;
        this.index = index;
        this.resourceIdBase = index << CLIENT_SHIFT;
        this.nextServerId = resourceIdBase | RESOURCE_MASK;
        if (socket != null) {
            this.peer = String.valueOf(socket.getRemoteSocketAddress());
            socket.setTcpNoDelay(true);
            this.rawOut = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            this.in = null; // set during the handshake, once the byte order is known
        } else {
            this.peer = String.valueOf(channel.getRemoteAddress());
            this.rawOut = new BufferedOutputStream(
                    java.nio.channels.Channels.newOutputStream(channel), 64 * 1024);
            this.in = null;
        }
    }

    private InputStream inputStream() throws IOException {
        if (socket != null) {
            return socket.getInputStream();
        }
        return java.nio.channels.Channels.newInputStream(channel);
    }

    public int index() {
        return index;
    }

    public String peer() {
        return peer;
    }

    public int resourceIdBase() {
        return resourceIdBase;
    }

    public int resourceIdMask() {
        return RESOURCE_MASK;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    public short sequenceNumber() {
        return sequenceNumber;
    }

    public ClientOutput output() {
        return output;
    }

    public byte currentOpcode() {
        return currentOpcode;
    }

    public byte currentMinorOpcode() {
        return currentMinorOpcode;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public boolean isClosed() {
        return closed;
    }

    public int closeDownMode() {
        return closeDownMode;
    }

    public void setCloseDownMode(int closeDownMode) {
        this.closeDownMode = closeDownMode;
    }

    /** True when {@code id} falls inside this client's resource id range. */
    public boolean ownsIdRange(int id) {
        return (id & ~RESOURCE_MASK) == resourceIdBase;
    }

    /**
     * Allocates an id on the client's behalf, for resources the server creates itself such as the
     * default graphics context or the root window.
     */
    public synchronized int allocateServerId() {
        int candidate = nextServerId;
        for (int attempts = 0; attempts <= RESOURCE_MASK; attempts++) {
            if (!server.resources().contains(candidate)) {
                nextServerId = candidate == resourceIdBase ? resourceIdBase | RESOURCE_MASK
                        : candidate - 1;
                return candidate;
            }
            candidate = candidate == resourceIdBase ? resourceIdBase | RESOURCE_MASK : candidate - 1;
        }
        throw ProtocolException.badAlloc();
    }

    @Override
    public void run() {
        try {
            handshake();
            server.registerClient(this);
            requestLoop();
        } catch (EOFException | java.net.SocketException e) {
            log.debug("client {} disconnected", peer);
        } catch (IOException e) {
            if (!closed) {
                log.info("client {} connection error: {}", peer, e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("client {} failed", peer, e);
        } finally {
            shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Connection setup
    // ------------------------------------------------------------------

    private void handshake() throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(
                new BufferedInputStream(inputStream(), 64 * 1024), 1);
        int firstByte = pushback.read();
        if (firstByte < 0) {
            throw new EOFException("client closed before sending a setup request");
        }
        pushback.unread(firstByte);
        // 'B' selects big endian; anything else means little endian ('l' from Xlib).
        bigEndian = firstByte == 'B';
        in = bigEndian ? new X11BigEndianInputStream(pushback)
                : new X11LittleEndianInputStream(pushback);
        output = new ClientOutput(rawOut, bigEndian);

        SetupRequest request = SetupRequest.readSetupRequest(in);
        server.connectionSetup().accept(this, request);
    }

    // ------------------------------------------------------------------
    // Request loop
    // ------------------------------------------------------------------

    private void requestLoop() throws IOException {
        while (!closed && !shuttingDown) {
            int opcode = in.readByte() & 0xFF;
            int data = in.readByte() & 0xFF;
            int lengthUnits = in.readCard16() & 0xFFFF;

            int headerBytes = 4;
            if (lengthUnits == 0) {
                if (!bigRequestsEnabled) {
                    // A zero length is only meaningful once BIG-REQUESTS has been enabled.
                    sendError(ProtocolException.LENGTH, 0);
                    return;
                }
                // BIG-REQUESTS: the real length follows and counts the whole request including
                // both 4 byte header words.
                long bigLength = Integer.toUnsignedLong(in.readInt32());
                if (bigLength > MAX_BIG_REQUEST_UNITS) {
                    sendError(ProtocolException.LENGTH, 0);
                    return;
                }
                lengthUnits = (int) bigLength;
                headerBytes = 8;
            }

            int bodyBytes = lengthUnits * 4 - headerBytes;
            if (bodyBytes < 0) {
                sendError(ProtocolException.LENGTH, 0);
                return;
            }
            byte[] body = new byte[bodyBytes];
            for (int i = 0; i < bodyBytes; i++) {
                body[i] = in.readByte();
            }

            sequenceNumber++;
            currentOpcode = (byte) opcode;
            currentMinorOpcode = (byte) data;

            dispatch(opcode, data, value(lengthUnits), body);

            if (in.available() == 0) {
                output.flush();
            }
        }
    }

    /** Clamps the request length into the card16 the generated decoders expect. */
    private static int value(int lengthUnits) {
        return lengthUnits > MAX_REQUEST_UNITS ? MAX_REQUEST_UNITS : lengthUnits;
    }

    private void dispatch(int opcode, int data, int lengthUnits, byte[] body) {
        // Rebuild the request without its opcode byte, which is the shape the generated decoders
        // expect: they read the data byte, the length and then the payload.
        byte[] parserBytes = new byte[3 + body.length];
        parserBytes[0] = (byte) data;
        putCard16(parserBytes, 1, lengthUnits);
        System.arraycopy(body, 0, parserBytes, 3, body.length);

        ByteArrayInputStream bytes = new ByteArrayInputStream(parserBytes);
        X11Input requestIn = bigEndian ? new X11BigEndianInputStream(bytes)
                : new X11LittleEndianInputStream(bytes);

        server.executeRequest(this, (byte) opcode, (byte) data, requestIn, parserBytes.length);
    }

    private void putCard16(byte[] bytes, int offset, int value) {
        if (bigEndian) {
            bytes[offset] = (byte) (value >> 8);
            bytes[offset + 1] = (byte) value;
        } else {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >> 8);
        }
    }

    /** Decodes a request with a generated plugin, returning null if the opcode is not supported. */
    public com.github.moaxcp.x11.protocol.XRequest decode(XProtocolPlugin plugin, byte majorOpcode,
                                                          byte minorOpcode, X11Input in) {
        try {
            return plugin.readRequest(majorOpcode, minorOpcode, in);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Outbound protocol messages
    // ------------------------------------------------------------------

    /** Sends a reply to the request currently being processed. */
    public void sendReply(com.github.moaxcp.x11.protocol.XReply reply) {
        if (shuttingDown) {
            return;
        }
        try {
            output.writeReply(reply);
        } catch (IOException e) {
            log.debug("failed to send reply to {}: {}", peer, e.getMessage());
            close();
            throw new RuntimeException(e);
        }
    }

    public void sendError(byte errorCode, int badValue) {
        if (shuttingDown) {
            return;
        }
        try {
            output.writeError(errorCode, badValue, sequenceNumber, currentOpcode, currentMinorOpcode);
        } catch (IOException e) {
            log.debug("failed to send error to {}: {}", peer, e.getMessage());
            close();
        }
    }

    public void flush() {
        try {
            output.flush();
        } catch (IOException e) {
            close();
        }
    }

    public void enableBigRequests() {
        this.bigRequestsEnabled = true;
    }

    public boolean isBigRequestsEnabled() {
        return bigRequestsEnabled;
    }

    /** Largest request the client may send, in 4 byte units. */
    public int maximumRequestUnits() {
        return bigRequestsEnabled ? MAX_BIG_REQUEST_UNITS : MAX_REQUEST_UNITS;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (socket != null) {
                socket.close();
            } else if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    private void shutdown() {
        shuttingDown = true;
        server.removeClient(this);
        close();
    }

    @Override
    public String toString() {
        return "client[" + index + " " + peer + "]";
    }
}
