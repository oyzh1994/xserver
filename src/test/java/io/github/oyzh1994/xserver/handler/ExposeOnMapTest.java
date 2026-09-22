package io.github.oyzh1994.xserver.handler;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window mapped before its parent still has to be told to draw itself.
 *
 * <p>Toolkits do this the other way round from what a server finds convenient: the children are
 * created and mapped first, while the window that holds them is not on screen yet, and the toplevel
 * is mapped last. Nothing of a child shows until then - and the moment it does is the moment it is
 * owed an Expose, because that is what a toolkit draws itself from. Miss it and the window is
 * black: {@code xclock}, {@code xeyes} and {@code xcalc} all come up empty, and the parts that
 * appear while the pointer is dragged across them are the pieces some other path happened to
 * repaint.
 *
 * <p>The check runs over a real connection rather than against the window tree, because what is
 * broken is not the region a window has but the event it is sent: a child whose region is perfectly
 * correct is still left blank when the map reports it as gaining nothing, since it "already" had
 * that region by the time the parent's map reached it.
 */
class ExposeOnMapTest {

    /** A display number nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 96;

    private static final int EXPOSE = 12;
    /** Reply and error are the two other things the stream can carry, and neither is expected. */
    private static final int REPLY = 1;
    private static final int ERROR = 0;

    /** ExposureMask, the event a toolkit draws itself from. */
    private static final int EXPOSURE_MASK = 1 << 15;

    @Test
    void aChildMappedBeforeItsParentIsExposedWhenTheParentIsMapped() throws Exception {
        XServer server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix"}));
        server.start();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6000 + DISPLAY), 5000);
            socket.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            Setup setup = handshake(in, out);
            int parent = setup.idBase() + 1;
            int child = setup.idBase() + 2;

            createWindow(out, parent, setup.root(), 0, 0, 200, 200);
            createWindow(out, child, parent, 0, 0, 200, 200);
            // The child is the one that draws: it asks for Expose and nothing else.
            selectInput(out, child, EXPOSURE_MASK);
            // Mapped while the parent is not, so nothing of it shows yet ...
            mapWindow(out, child);
            // ... and then the map that puts both of them on screen.
            mapWindow(out, parent);
            out.flush();

            int[] expose = awaitExpose(in, child);

            assertNotNull(expose, "the child was never told to draw itself");
            assertEquals(0, expose[0], "the exposure starts at the child's own origin");
            assertEquals(0, expose[1]);
            assertEquals(200, expose[2], "the whole child is new to the screen");
            assertEquals(200, expose[3]);
        } finally {
            server.stop();
        }
    }

    /** Reads events until the child's Expose arrives, or gives up when the server goes quiet. */
    private static int[] awaitExpose(DataInputStream in, int window) throws IOException {
        try {
            while (true) {
                byte[] event = new byte[32];
                in.readFully(event);
                int type = event[0] & 0xFF;
                if (type == REPLY || type == ERROR) {
                    // Nothing this test asked for answers or fails, so either is the server telling
                    // us something other than what we are waiting for.
                    throw new IllegalStateException("unexpected " + (type == REPLY ? "reply" : "error"));
                }
                int eventWindow = readInt(event, 4);
                if (type == EXPOSE && eventWindow == window) {
                    return new int[] {readShort(event, 8), readShort(event, 10),
                            readShort(event, 12), readShort(event, 14)};
                }
            }
        } catch (java.net.SocketTimeoutException quiet) {
            return null;
        }
    }

    private static void createWindow(OutputStream out, int window, int parent, int x, int y,
            int width, int height) throws IOException {
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
        out.write(request);
    }

    /** ChangeWindowAttributes with an event mask, which is how a client says what it wants to hear. */
    private static void selectInput(OutputStream out, int window, int mask) throws IOException {
        byte[] request = new byte[16];
        request[0] = 2;                             // ChangeWindowAttributes
        request[2] = 4;
        writeInt(request, 4, window);
        writeInt(request, 8, 0x800);                // CWEventMask
        writeInt(request, 12, mask);
        out.write(request);
    }

    private static void mapWindow(OutputStream out, int window) throws IOException {
        byte[] request = new byte[8];
        request[0] = 8;                             // MapWindow
        request[2] = 2;
        writeInt(request, 4, window);
        out.write(request);
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

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    /** What the setup reply says about the connection: the root window and the id range to use. */
    private record Setup(int root, int idBase) {
    }

    private static Setup handshake(DataInputStream in, OutputStream out) throws IOException {
        byte[] setup = new byte[12];
        setup[0] = 'l';
        setup[2] = 11;
        out.write(setup);
        out.flush();

        byte[] header = new byte[8];
        in.readFully(header);
        assertTrue((header[0] & 0xFF) == 1, "the server refused the connection");
        int length = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
        byte[] rest = new byte[length * 4];
        in.readFully(rest);

        int idBase = readInt(rest, 12 - 8);
        int vendor = readShort(rest, 24 - 8);
        int formats = rest[29 - 8] & 0xFF;
        int screensAt = 40 - 8 + ((vendor + 3) & ~3) + formats * 8;
        return new Setup(readInt(rest, screensAt), idBase);
    }
}
