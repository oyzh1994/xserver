package io.github.oyzh1994.xserver.test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A minimal X client for tests: a socket, the connection setup, and the handful of requests a test
 * needs to build a window a client would recognise.
 *
 * <p>Written against the wire protocol rather than Xlib so a test needs nothing installed, and so a
 * test can send exactly the requests it means to - a toolkit would add its own traffic around them.
 */
public final class RawXClient implements AutoCloseable {

    /** The root window of the screen, for requests that name a parent. */
    private final int root;
    /** The first id this client may use; ids above it are ours to hand out. */
    private final int idBase;
    private int nextId = 1;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    private RawXClient(Socket socket, int root, int idBase) throws IOException {
        this.socket = socket;
        this.root = root;
        this.idBase = idBase;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects to a display's TCP port and completes the connection setup. */
    public static RawXClient connect(int display) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", 6000 + display), 5000);
        socket.setSoTimeout(5000);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();

        byte[] setup = new byte[12];
        setup[0] = 'l';                             // little endian client
        setup[2] = 11;                              // protocol major
        out.write(setup);
        out.flush();

        byte[] header = new byte[8];
        in.readFully(header);
        if ((header[0] & 0xFF) != 1) {
            throw new IOException("the connection setup was refused");
        }
        int length = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
        byte[] rest = new byte[length * 4];
        in.readFully(rest);

        // The setup reply: id base right after the release, then the screens after the vendor
        // string and the pixmap formats.
        int idBase = readInt(rest, 12 - 8);
        int vendor = readShort(rest, 24 - 8);
        int formats = rest[29 - 8] & 0xFF;
        int screensAt = 40 - 8 + ((vendor + 3) & ~3) + formats * 8;
        return new RawXClient(socket, readInt(rest, screensAt), idBase);
    }

    public int root() {
        return root;
    }

    /** An id from this client's range, for a window it creates. */
    public int allocateId() {
        return idBase + nextId++;
    }

    public void createWindow(int window, int parent, int x, int y, int width, int height)
            throws IOException {
        byte[] request = new byte[32];
        request[0] = 1;                             // CreateWindow
        request[1] = 0;                             // depth: copy from the parent
        request[2] = 8;                             // length in four byte units, no values
        writeInt(request, 4, window);
        writeInt(request, 8, parent);
        writeShort(request, 12, x);
        writeShort(request, 14, y);
        writeShort(request, 16, width);
        writeShort(request, 18, height);
        writeShort(request, 20, 0);                 // border width
        writeShort(request, 22, 1);                 // InputOutput
        writeInt(request, 24, 0);                   // visual: copy from the parent
        writeInt(request, 28, 0);                   // value mask: nothing to set
        send(request);
    }

    /** ChangeWindowAttributes with an event mask, which is how a client says what it wants to hear. */
    public void selectInput(int window, int mask) throws IOException {
        byte[] request = new byte[16];
        request[0] = 2;                             // ChangeWindowAttributes
        request[2] = 4;
        writeInt(request, 4, window);
        writeInt(request, 8, 0x800);                // CWEventMask
        writeInt(request, 12, mask);
        send(request);
    }

    public void mapWindow(int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 8;                             // MapWindow
        request[2] = 2;
        writeInt(request, 4, window);
        send(request);
    }

    /** Interns a name and returns the atom, since the reply is the only place it is reported. */
    public int internAtom(String name) throws IOException {
        byte[] request = new byte[(8 + name.length() + 3) & ~3];
        request[0] = 16;                            // InternAtom
        request[1] = 0;                             // create the atom if it does not exist
        request[2] = (byte) (request.length / 4);
        request[4] = (byte) name.length();
        request[5] = (byte) (name.length() >> 8);
        for (int i = 0; i < name.length(); i++) {
            request[8 + i] = (byte) name.charAt(i);
        }
        send(request);
        return readInt(readReply(), 8);
    }

    /** ChangeProperty in replace mode, with 32 bit items. */
    public void setAtomListProperty(int window, int property, int type, int... atoms)
            throws IOException {
        byte[] request = new byte[(24 + atoms.length * 4 + 3) & ~3];
        request[0] = 18;                            // ChangeProperty
        request[1] = 0;                             // replace
        request[2] = (byte) (request.length / 4);
        writeInt(request, 4, window);
        writeInt(request, 8, property);
        writeInt(request, 12, type);
        request[16] = 32;                           // format
        writeInt(request, 20, atoms.length);
        for (int i = 0; i < atoms.length; i++) {
            writeInt(request, 24 + i * 4, atoms[i]);
        }
        send(request);
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** Reads one event, or returns null when the server sends nothing within the socket's timeout. */
    public byte[] readEvent() throws IOException {
        byte[] event = new byte[32];
        try {
            in.readFully(event);
        } catch (java.net.SocketTimeoutException quiet) {
            return null;
        }
        return event;
    }

    /** Reads an event and keeps reading until it is the wanted one, or the server goes quiet. */
    public byte[] readEvent(int type) throws IOException {
        for (byte[] event = readEvent(); event != null; event = readEvent()) {
            if ((event[0] & 0x7F) == type) {
                return event;
            }
        }
        return null;
    }

    /**
     * Reads the next reply, complete, so that every field sits at its protocol offset.
     *
     * <p>A reply is thirty two bytes whatever its length field says: that field counts only the extra
     * data that follows, which is nothing at all for a reply like {@code InternAtom} whose values all
     * fit in the fixed part.
     */
    public byte[] readReply() throws IOException {
        byte[] reply = readFully(32);
        if ((reply[0] & 0xFF) != 1) {
            throw new IOException("expected a reply, got " + (reply[0] & 0xFF));
        }
        int extra = readInt(reply, 4) * 4;
        if (extra > 0) {
            byte[] all = new byte[32 + extra];
            System.arraycopy(reply, 0, all, 0, 32);
            System.arraycopy(readFully(extra), 0, all, 32, extra);
            return all;
        }
        return reply;
    }

    /** True once the server has closed the connection, which is how a disconnect shows up here. */
    public boolean atEndOfStream() throws IOException {
        try {
            return in.read() < 0;
        } catch (java.net.SocketTimeoutException quiet) {
            return false;
        }
    }

    public static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    public static int readShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private void send(byte[] request) throws IOException {
        out.write(request);
        flush();
    }

    private byte[] readFully(long count) throws IOException {
        byte[] bytes = new byte[(int) count];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
        bytes[offset + 2] = (byte) (value >> 16);
        bytes[offset + 3] = (byte) (value >> 24);
    }

    private static void writeShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
