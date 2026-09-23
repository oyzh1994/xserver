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
        return connect("127.0.0.1", 6000 + display);
    }

    /**
     * Connects to a server by host and port, for comparing this one against another - a reference
     * server on a different machine answers the same questions, and the answers are the point.
     */
    public static RawXClient connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
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
        createWindow(window, parent, x, y, width, height, -1);
    }

    /** Creates a window, optionally with a background pixel - which a ClearArea then paints it in. */
    public void createWindow(int window, int parent, int x, int y, int width, int height,
                             int backgroundPixel) throws IOException {
        boolean withBackground = backgroundPixel >= 0;
        byte[] request = new byte[withBackground ? 36 : 32];
        request[0] = 1;                             // CreateWindow
        request[1] = 0;                             // depth: copy from the parent
        request[2] = (byte) (request.length / 4);
        writeInt(request, 4, window);
        writeInt(request, 8, parent);
        writeShort(request, 12, x);
        writeShort(request, 14, y);
        writeShort(request, 16, width);
        writeShort(request, 18, height);
        writeShort(request, 20, 0);                 // border width
        writeShort(request, 22, 1);                 // InputOutput
        writeInt(request, 24, 0);                   // visual: copy from the parent
        writeInt(request, 28, withBackground ? 0x2 : 0);        // CWBackPixel
        if (withBackground) {
            writeInt(request, 32, backgroundPixel);
        }
        send(request);
    }

    /** Fills a rectangle of a window with its background pixel. */
    public void clearArea(int window, int x, int y, int width, int height) throws IOException {
        byte[] request = new byte[16];
        request[0] = 61;                            // ClearArea
        request[1] = 0;                             // no exposures
        request[2] = 4;
        writeInt(request, 4, window);
        writeShort(request, 8, x);
        writeShort(request, 10, y);
        writeShort(request, 12, width);
        writeShort(request, 14, height);
        send(request);
    }

    public void configureWindow(int window, int x, int y, int width, int height)
            throws IOException {
        byte[] request = new byte[28];
        request[0] = 12;                            // ConfigureWindow
        request[2] = 7;
        writeInt(request, 4, window);
        writeInt(request, 8, 0xF);                  // x, y, width, height
        writeInt(request, 12, x);                   // every ConfigureWindow value is 32 bit
        writeInt(request, 16, y);
        writeInt(request, 20, width);
        writeInt(request, 24, height);
        send(request);
    }

    public void destroyWindow(int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 4;                             // DestroyWindow
        request[2] = 2;
        writeInt(request, 4, window);
        send(request);
    }

    /** The root a window belongs to, which is how a test tells two screens apart. */
    public int rootOf(int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 14;                            // GetGeometry
        request[2] = 2;
        writeInt(request, 4, window);
        send(request);
        return readInt(readReply(), 8);
    }

    /** Looks up an extension's major opcode, or 0 when the server does not have it. */
    public int queryExtension(String name) throws IOException {
        byte[] request = new byte[(8 + name.length() + 3) & ~3];
        request[0] = 98;                            // QueryExtension
        request[2] = (byte) (request.length / 4);
        writeShort(request, 4, name.length());
        for (int i = 0; i < name.length(); i++) {
            request[8 + i] = (byte) name.charAt(i);
        }
        send(request);
        byte[] reply = readReply();
        return reply[8] == 0 ? 0 : reply[9] & 0xFF;
    }

    /** Moves the pointer through XTEST, which is how a test plays the part of the user. */
    public void fakeMotion(int opcode, int x, int y) throws IOException {
        fakeInput(opcode, 6, 0, x, y);               // MotionNotify, absolute
    }

    /** Presses or releases a button through XTEST, at wherever the pointer already is. */
    public void fakeButton(int opcode, int button, boolean pressed) throws IOException {
        fakeInput(opcode, pressed ? 4 : 5, button, 0, 0);
    }

    private void fakeInput(int opcode, int type, int detail, int x, int y) throws IOException {
        byte[] request = new byte[36];
        request[0] = (byte) opcode;
        request[1] = 2;                             // X_XTestFakeInput
        request[2] = 9;
        request[4] = (byte) type;
        request[5] = (byte) detail;
        writeShort(request, 24, x);
        writeShort(request, 26, y);
        send(request);
    }

    /**
     * Grabs the pointer, as a menu does while it is open.
     *
     * @param ownerEvents true when the client wants the crossings of its own windows rather than
     *     every event reported against the grab window
     */
    public int grabPointer(int window, boolean ownerEvents, int eventMask) throws IOException {
        byte[] request = new byte[24];
        request[0] = 26;                            // GrabPointer
        request[1] = (byte) (ownerEvents ? 1 : 0);
        request[2] = 6;
        writeInt(request, 4, window);
        writeShort(request, 8, eventMask);
        request[10] = 1;                            // pointer mode: Async
        request[11] = 1;                            // keyboard mode: Async
        send(request);
        return readReply()[1] & 0xFF;               // the grab status
    }

    public void ungrabPointer() throws IOException {
        byte[] request = new byte[8];
        request[0] = 27;                            // UngrabPointer
        request[2] = 2;
        send(request);
    }

    /** Creates a graphics context on a screen with the given foreground pixel. */
    public int createGC(int gc, int screenRoot, int foreground) throws IOException {
        byte[] request = new byte[20];
        request[0] = 55;                            // CreateGC
        request[2] = 5;
        writeInt(request, 4, gc);
        writeInt(request, 8, screenRoot);           // the GC takes its screen from this drawable
        writeInt(request, 12, 0x4);                 // GCForeground
        writeInt(request, 16, foreground);
        send(request);
        return gc;
    }

    /** Fills a rectangle of a drawable with the GC's foreground. */
    public void fillRectangle(int window, int gc, int x, int y, int width, int height)
            throws IOException {
        // One request, rectangles included: the length field has to cover them or the server reads
        // the request as truncated.
        byte[] request = new byte[20];
        request[0] = 70;                            // PolyFillRectangle
        request[2] = 5;
        writeInt(request, 4, window);
        writeInt(request, 8, gc);
        writeShort(request, 12, x);
        writeShort(request, 14, y);
        writeShort(request, 16, width);
        writeShort(request, 18, height);
        send(request);
    }

    /**
     * Sends a whole ZPixmap image, which is what a toolkit's software renderer uploads each frame.
     * The data is BGRA, one int per pixel, as the server's image byte order expects.
     */
    public void putImage(int drawable, int gc, int x, int y, int width, int height, byte[] bgra)
            throws IOException {
        int dataLength = width * height * 4;
        byte[] request = new byte[24 + ((dataLength + 3) & ~3)];
        request[0] = 72;                            // PutImage
        request[1] = 2;                             // ZPixmap
        // The length field is 16 bit, so the image has to fit one request unless the client goes
        // through BIG-REQUESTS; tests keep theirs small enough not to need it.
        int units = request.length / 4;
        if (units > 65535) {
            throw new IllegalArgumentException("image too large for a single request: " + units
                    + " units");
        }
        writeShort(request, 2, units);
        writeInt(request, 4, drawable);
        writeInt(request, 8, gc);
        writeShort(request, 12, width);
        writeShort(request, 14, height);
        writeShort(request, 16, x);
        writeShort(request, 18, y);
        request[20] = 0;                            // left pad
        request[21] = 24;                           // depth
        System.arraycopy(bgra, 0, request, 24, Math.min(bgra.length, dataLength));
        out.write(request);
        out.flush();
    }

    /** The children of a window, which for a root is every window the client has made. */
    public int[] queryTree(int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 15;                            // QueryTree
        request[2] = 2;
        writeInt(request, 4, window);
        send(request);
        byte[] reply = readReply();
        int count = readShort(reply, 16);
        int[] children = new int[count];
        for (int i = 0; i < count; i++) {
            children[i] = readInt(reply, 32 + i * 4);
        }
        return children;
    }

    /** Reads pixels back from a drawable, as ZPixmap bytes. */
    public byte[] getImage(int drawable, int x, int y, int width, int height) throws IOException {
        byte[] request = new byte[20];
        request[0] = 73;                            // GetImage
        request[1] = 2;                             // ZPixmap
        request[2] = 5;
        writeInt(request, 4, drawable);
        writeShort(request, 8, x);
        writeShort(request, 10, y);
        writeShort(request, 12, width);
        writeShort(request, 14, height);
        writeInt(request, 16, 0xFFFFFFFF);           // plane mask: all of them
        send(request);

        return readReply();
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

    public void unmapWindow(int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 10;                            // UnmapWindow
        request[2] = 2;
        writeInt(request, 4, window);
        send(request);
    }

    /** Makes a window override-redirect, which is what a menu or a tooltip is. */
    public void setOverrideRedirect(int window) throws IOException {
        byte[] request = new byte[12];
        request[0] = 2;                             // ChangeWindowAttributes
        request[2] = 3;
        writeInt(request, 4, window);
        writeInt(request, 8, 0x200);                // CWOverrideRedirect
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

    /**
     * SetInputFocus: {@code focus} is a window id, 0 for None or 1 for PointerRoot.
     *
     * <p>Twelve bytes, not sixteen: a reference server answers a request whose length disagrees with
     * the protocol with {@code BadLength}, and this one does not check - it reads the length and hands
     * the payload on - so an over-long request here passes against this server and is rejected by a
     * real one. That is exactly the kind of difference the probes against a reference server exist to
     * catch, and it caught this.
     */
    public void setInputFocus(int focus, int revertTo) throws IOException {
        byte[] request = new byte[12];
        request[0] = 42;                            // SetInputFocus
        request[1] = (byte) revertTo;
        request[2] = 3;
        writeInt(request, 4, focus);
        writeInt(request, 8, 0);                    // time: CurrentTime
        send(request);
    }

    /**
     * How long a read waits for an event before giving up.
     *
     * <p>Five seconds by default, which is what a test that waits for an event wants. A test that
     * has to prove nothing came, or that drains what a setup produced before reading its own, wants
     * it much shorter - waiting out the default costs a test five seconds an assertion.
     */
    public void readTimeoutMillis(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** GetInputFocus: the focus window, 0 for None or 1 for PointerRoot. */
    public int queryInputFocus() throws IOException {
        byte[] request = new byte[4];
        request[0] = 43;                            // GetInputFocus
        request[2] = 1;
        send(request);
        return readInt(readReply(), 8);
    }

    /** ChangeProperty in replace mode, with 8 bit items: how a window title is published. */
    public void setStringProperty(int window, int property, int type, String value)
            throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] request = new byte[(24 + bytes.length + 3) & ~3];
        request[0] = 18;                            // ChangeProperty
        request[1] = 0;                             // replace
        request[2] = (byte) (request.length / 4);
        writeInt(request, 4, window);
        writeInt(request, 8, property);
        writeInt(request, 12, type);
        request[16] = 8;                            // format
        writeInt(request, 20, bytes.length);
        System.arraycopy(bytes, 0, request, 24, bytes.length);
        send(request);
    }

    /** ChangeProperty in replace mode, with 32 bit items: how an icon is published. */
    public void setCardinalProperty(int window, int property, int type, int... values)
            throws IOException {
        byte[] request = new byte[(24 + values.length * 4 + 3) & ~3];
        request[0] = 18;                            // ChangeProperty
        request[1] = 0;                             // replace
        request[2] = (byte) (request.length / 4);
        writeInt(request, 4, window);
        writeInt(request, 8, property);
        writeInt(request, 12, type);
        request[16] = 32;                           // format
        writeInt(request, 20, values.length);
        for (int i = 0; i < values.length; i++) {
            writeInt(request, 24 + i * 4, values[i]);
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
        byte[] reply;
        while (true) {
            reply = readFully(32);
            int type = reply[0] & 0xFF;
            if (type == 1) {
                break;                              // the reply
            }
            if (type == 0) {
                throw new IOException("the server refused the request: error "
                        + (reply[1] & 0xFF) + " value 0x" + Integer.toHexString(readInt(reply, 4)));
            }
            // Anything else is an event the client never asked to read separately - a NoExposure
            // arrives after every drawing request, for one - so it is dropped rather than mistaken
            // for the answer.
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
