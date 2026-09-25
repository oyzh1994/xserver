package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The VNC backend: a real RFB client watching a real X client (backlog D2).
 *
 * <p>The point of this backend is that somebody else's software can look at this server, so the test
 * is somebody else's software - a hand-written RFB client that does the handshake, asks for frames
 * and reads them - rather than a call into the backend. What it can then assert is the whole chain:
 * a pixel an X client drew arrives in a VNC update, the client's own pixel format is honoured, an
 * incremental request waits for damage instead of spinning, and a pointer or key event from the
 * viewer reaches the X client as the event it should be.
 *
 * <p>A real client (macOS Screen Sharing) was used for the live check; this is what makes the
 * behaviour a property of the suite rather than of one afternoon.
 */
class VncBackendTest {

    /**
     * A display number the rest of the suite does not use - and it has to be checked, not assumed:
     * the first version of this test picked 92, which {@code AuthenticationTest} already had, and
     * the two collided only when the whole suite ran (a client of one reached the other's server and
     * the handshake it got back was a refusal).
     */
    private static final int DISPLAY = 94;

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;

    @Test
    void aViewerSeesWhatTheClientDrew() throws Exception {
        try (Server server = new Server()) {
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = server.connect()) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 200, 100, 0xFFFFFF);
                client.mapWindow(window);
                int gc = client.allocateId();
                client.createGC(gc, client.root(), RED);
                client.fillRectangle(window, gc, 20, 30, 40, 10);
                client.flush();
                Thread.sleep(150);                   // the display tick presents the damage

                viewer.requestFullFrame();
                Frame frame = viewer.readFrame();
                assertEquals(server.screen.width(), frame.width, "the whole screen comes back");
                assertEquals(server.screen.height(), frame.height);
                assertEquals(RED, frame.pixel(25, 35), "the rectangle the client drew");
                assertEquals(0xFFFFFF, frame.pixel(5, 5), "and the window's white background");
            }
        }
    }

    @Test
    void anIncrementalRequestWaitsForDamageInsteadOfSpinning() throws Exception {
        try (Server server = new Server()) {
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = server.connect()) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 200, 100, 0xFFFFFF);
                client.mapWindow(window);
                client.flush();
                Thread.sleep(150);
                viewer.requestFullFrame();
                viewer.readFrame();                  // everything so far

                // Nothing has changed, so an incremental request is answered with nothing at all -
                // which is the protocol's own idle: the client waits, and so does the server.
                viewer.requestIncrementalFrame();
                viewer.socket.setSoTimeout(400);
                assertEquals(null, viewer.readFrameOrNull(), "no damage means no update");

                // And a draw wakes it up, because the tick adds the damage to the viewer's pending
                // rectangle whatever that viewer happens to be waiting for.
                viewer.socket.setSoTimeout(4000);
                int gc = client.allocateId();
                client.createGC(gc, client.root(), GREEN);
                client.fillRectangle(window, gc, 50, 50, 20, 20);
                client.flush();
                Frame frame = viewer.readFrame();
                assertNotNull(frame, "the draw should have woken the waiting request");
                assertEquals(GREEN, frame.pixel(55, 55), "and the frame carries it");
                assertTrue(frame.width <= 200 && frame.height <= 100,
                        "the update is the damage, not the whole screen: " + frame.width + "x"
                                + frame.height);
            }
        }
    }

    @Test
    void theCustomersOwnPixelFormatIsHonoured() throws Exception {
        try (Server server = new Server()) {
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = server.connect()) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 100, 100, 0x0000FF);
                client.mapWindow(window);
                client.flush();
                Thread.sleep(150);

                // 16 bits, 565, red in the high five: what a client with a narrow link asks for.
                viewer.setPixelFormat(16, 24, false, true, 31, 63, 31, 11, 5, 0);
                viewer.requestFullFrame();
                Frame frame = viewer.readFrame();
                assertEquals(2, frame.bytesPerPixel);
                assertEquals(0x001F, frame.pixel(50, 50), "pure blue in 565");
            }
        }
    }

    @Test
    void aServerWithAPasswordRefusesTheWrongOneAndServesTheRightOne() throws Exception {
        try (Server server = new Server("s3cret")) {
            // The wrong password is refused, and the client is told so rather than left hanging.
            try (VncClient refused = new VncClient(server.backend.boundPort(), "wrong")) {
                assertTrue(refused.wasRefused, "a wrong password must be refused");
            }
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = server.connect()) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 100, 100, 0x0000FF);
                client.mapWindow(window);
                client.flush();
                Thread.sleep(150);
                viewer.requestFullFrame();
                assertEquals(0x0000FF, viewer.readFrame().pixel(50, 50),
                        "the right password gets the screen");
            }
        }
    }

    /**
     * A client that speaks **3.3**, which is what macOS Screen Sharing announces.
     *
     * <p>Found by the live check, and it is the branch no test covered: 3.3 has no security-type
     * list (a single four byte type instead) and **no SecurityResult** after it, so a server that
     * answers a 3.3 client the 3.8 way leaves it waiting for a byte that never comes. Measured on
     * the wire before this test existed: {@code RFB 003.003\n} from Screen Sharing, then silence.
     */
    @Test
    void aClientThatSpeaksThirtyThreeIsAnsweredInItsOwnTerms() throws Exception {
        try (Server server = new Server()) {
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = new VncClient(server.backend.boundPort(), null, true)) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 100, 100, 0x0000FF);
                client.mapWindow(window);
                client.flush();
                Thread.sleep(150);
                viewer.requestFullFrame();
                assertEquals(0x0000FF, viewer.readFrame().pixel(50, 50),
                        "a 3.3 client gets the screen too, with no SecurityResult in between");
            }
        }
    }

    /**
     * The DES itself, against a vector computed outside this codebase.
     *
     * <p>The scheme is the one every VNC server implements and no DES library does by default: the
     * password's bytes are the key **with each byte's bits reversed**. The vector below is
     * {@code openssl enc -des-ecb -K ceccc64ea62e0000 -nopad} over the bytes 00..0f - the key being
     * "s3cret" (73 33 63 72 65 74) reversed and zero padded - so it is an independent answer to the
     * question "is this server's VNC authentication the standard one". Getting the reversal wrong
     * does not fail loudly anywhere: the server just refuses every password, which is exactly what
     * a live client showed before this was pinned.
     */
    @Test
    void theChallengeIsEncryptedTheWayTheProtocolSays() throws Exception {
        byte[] challenge = new byte[16];
        for (int i = 0; i < 16; i++) {
            challenge[i] = (byte) i;
        }
        byte[] expected = new byte[16];
        String hex = "fc9a2bb8546a63388eb45b530d3a6337";
        for (int i = 0; i < 16; i++) {
            expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        assertArrayEquals(expected, RfbSession.encryptChallenge(challenge, "s3cret"),
                "the VNC challenge encrypted with the password as a bit-reversed DES key");
    }

    @Test
    void aPointerAndAKeyFromTheViewerReachTheClient() throws Exception {
        try (Server server = new Server()) {
            try (RawXClient client = RawXClient.connect(DISPLAY);
                    VncClient viewer = server.connect()) {
                int window = client.allocateId();
                client.createWindow(window, client.root(), 0, 0, 200, 200, 0xFFFFFF);
                client.selectInput(window, 0x03 | (1 << 2));    // key, button and pointer events
                client.mapWindow(window);
                client.setInputFocus(window, 1);
                client.flush();
                client.readTimeoutMillis(2000);
                Thread.sleep(150);
                viewer.requestFullFrame();
                viewer.readFrame();

                // A press at 30,40: the X client is told where the pointer is and what happened.
                viewer.pointer(1, 30, 40);
                byte[] press = client.readEvent(4);             // ButtonPress
                assertNotNull(press, "the viewer's click should arrive as a button press");
                assertEquals(1, press[1] & 0xFF, "button 1, which is the event's detail byte");
                assertEquals(30, (short) RawXClient.readShort(press, 24), "at the x the viewer sent");
                assertEquals(40, (short) RawXClient.readShort(press, 26), "and the y");
                viewer.pointer(0, 30, 40);                      // release

                // And a key: RFB sends the keysym, and the keycode is the server's own map's.
                viewer.key(true, 'a');
                byte[] key = client.readEvent(2);               // KeyPress
                assertNotNull(key, "the viewer's key should arrive as a key press");
                assertEquals(io.github.oyzh1994.xserver.input.Keymap.keycodeForLetter('a'),
                        key[1] & 0xFF, "the keycode 'a' has in this server's keymap");
                viewer.key(false, 'a');
            }
        }
    }

    // ------------------------------------------------------------------
    // The harness
    // ------------------------------------------------------------------

    /**
     * A server on the VNC backend, with the backend itself in hand.
     *
     * <p>{@code --vnc-port 0} asks for any free port and {@code VncBackend.boundPort} answers which
     * one it got: a test that hard-coded 5900 would fail on any machine already running a VNC server.
     */
    private static final class Server implements AutoCloseable {

        final XServer xserver;
        final VncBackend backend;
        final Screen screen;

        final String password;

        Server() throws Exception {
            this(null);
        }

        Server(String password) throws Exception {
            this.password = password;
            // Through the real path: `--backend vnc` is parsed, implies single-window (which is what
            // puts a backend on the master screen before any client connects), and the factory makes
            // the backend itself. A test that built one by hand would not be testing any of that.
            java.nio.file.Path passwordFile = java.nio.file.Files.createTempFile("vnc-pass", ".txt");
            passwordFile.toFile().deleteOnExit();
            if (password != null) {
                java.nio.file.Files.writeString(passwordFile, password);
            }
            xserver = new XServer(ServerOptions.parse(password == null
                    ? new String[] {"--display", ":" + DISPLAY, "--backend", "vnc", "--vnc-port", "0",
                            "--no-unix", "--geometry", "320x240"}
                    : new String[] {"--display", ":" + DISPLAY, "--backend", "vnc", "--vnc-port", "0",
                            "--no-unix", "--geometry", "320x240", "--vnc-password-file",
                            passwordFile.toString()}));
            xserver.start();
            xserver.startEventLoop();
            screen = xserver.screen();
            backend = (VncBackend) xserver.sessions().get(0).backend();
            assertNotNull(backend, "the VNC backend should be listening before a client connects");
        }

        VncClient connect() throws IOException {
            return new VncClient(backend.boundPort(), password);
        }

        @Override
        public void close() {
            xserver.stop();
        }
    }

    /**
     * One decoded update: the rectangle and its pixels, read the way the format they were sent in
     * says to read them.
     *
     * <p>The endianness matters and is easy to forget: the handshake's default is little endian - it
     * is the framebuffer's own layout - so a test that assembled the bytes high-first read every
     * colour reversed, which looks like a server that swapped red and blue rather than a reader that
     * was one line wrong.
     */
    private record Frame(int x, int y, int width, int height, int bytesPerPixel, boolean bigEndian,
                         byte[] pixels) {
        int pixel(int px, int py) {
            int at = ((py - y) * width + (px - x)) * bytesPerPixel;
            int value = 0;
            if (bigEndian) {
                for (int i = 0; i < bytesPerPixel; i++) {
                    value = (value << 8) | (pixels[at + i] & 0xFF);
                }
            } else {
                for (int i = bytesPerPixel - 1; i >= 0; i--) {
                    value = (value << 8) | (pixels[at + i] & 0xFF);
                }
            }
            return value;
        }
    }

    /** Just enough RFB to be a client: 3.8, security None, raw frames, pointer and key. */
    private static final class VncClient implements AutoCloseable {

        final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private int bytesPerPixel = 4;
        private boolean bigEndian;

        boolean wasRefused;

        VncClient(int port, String password) throws IOException {
            this(port, password, false);
        }

        /** {@code thirtyThree} announces RFB 003.003, the version Apple's client speaks. */
        VncClient(int port, String password, boolean thirtyThree) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 4000);
            socket.setSoTimeout(4000);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            String version = readAscii(12);
            assertEquals("RFB 003.008\n", version, "the server announces the version it speaks");
            out.write((thirtyThree ? "RFB 003.003\n" : "RFB 003.008\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (thirtyThree) {
                // 3.3: the security type is four bytes and there is no result after the exchange.
                assertEquals(password == null ? 1 : 2, readInt(), "the 3.3 security type");
                if (password != null) {
                    byte[] challenge = readBytes(16);
                    out.write(vncEncrypt(challenge, password));
                    out.flush();
                }
            } else {
            assertEquals(1, in.read(), "one security type");
            int type = in.read();
            if (password == null) {
                assertEquals(1, type, "and it is None");
                out.write(1);                               // choose it
                out.flush();
            } else {
                assertEquals(2, type, "with a password configured the offer is VNC authentication");
                out.write(2);
                out.flush();
                byte[] challenge = readBytes(16);
                out.write(vncEncrypt(challenge, password));
                out.flush();
            }
            }
            if (!thirtyThree) {
            int result = readInt();
            if (result != 0) {
                // Refused: the server sends the reason after the result and then closes, so there is
                // nothing else to do here and the caller asserts on wasRefused.
                wasRefused = true;
                return;
            }
            }
            out.write(1);                                   // ClientInit: shared
            out.flush();

            readU16();                                      // width
            readU16();                                      // height
            setPixelFormatBytes(readBytes(16));             // the default format
            int nameLength = readInt();
            readAscii(nameLength);
        }

        void setPixelFormat(int bpp, int depth, boolean bigEndian, boolean trueColour, int redMax,
                            int greenMax, int blueMax, int redShift, int greenShift, int blueShift)
                throws IOException {
            byte[] format = new byte[16];
            format[0] = (byte) bpp;
            format[1] = (byte) depth;
            format[2] = (byte) (bigEndian ? 1 : 0);
            format[3] = (byte) (trueColour ? 1 : 0);
            writeU16(format, 4, redMax);
            writeU16(format, 6, greenMax);
            writeU16(format, 8, blueMax);
            format[10] = (byte) redShift;
            format[11] = (byte) greenShift;
            format[12] = (byte) blueShift;
            out.write(0);                                   // SetPixelFormat
            out.write(new byte[3]);                         // padding
            out.write(format);
            out.flush();
            setPixelFormatBytes(format);
        }

        private void setPixelFormatBytes(byte[] format) {
            bytesPerPixel = Math.max(1, (format[0] & 0xFF) / 8);
            bigEndian = (format[2] & 0xFF) != 0;
        }

        void requestFullFrame() throws IOException {
            request(false);
        }

        void requestIncrementalFrame() throws IOException {
            request(true);
        }

        private void request(boolean incremental) throws IOException {
            out.write(3);                                   // FramebufferUpdateRequest
            out.write(incremental ? 1 : 0);
            writeU16(out, 0);
            writeU16(out, 0);
            writeU16(out, 0xFFFF);
            writeU16(out, 0xFFFF);
            out.flush();
        }

        void pointer(int buttonMask, int x, int y) throws IOException {
            out.write(5);
            out.write(buttonMask);
            writeU16(out, x);
            writeU16(out, y);
            out.flush();
        }

        void key(boolean down, int character) throws IOException {
            out.write(4);
            out.write(down ? 1 : 0);
            out.write(new byte[2]);
            writeInt(out, character);                       // an ASCII keysym is the character
            out.flush();
        }

        Frame readFrame() throws IOException {
            Frame frame = readFrameOrNull();
            assertNotNull(frame, "expected a frame, and the connection closed instead");
            return frame;
        }

        /** Null when nothing arrived before the socket's own timeout, and null at end of stream. */
        Frame readFrameOrNull() throws IOException {
            int type;
            try {
                type = in.read();
            } catch (java.net.SocketTimeoutException e) {
                return null;
            }
            if (type < 0) {
                return null;
            }
            assertEquals(0, type, "FramebufferUpdate");
            in.read();
            int rectangles = readU16();
            Frame last = null;
            for (int i = 0; i < rectangles; i++) {
                int x = readU16();
                int y = readU16();
                int width = readU16();
                int height = readU16();
                int encoding = readInt();
                if (encoding == -223) {                     // DesktopSize: no pixels follow it
                    continue;
                }
                assertEquals(0, encoding, "raw encoding");
                byte[] data = new byte[width * height * bytesPerPixel];
                in.readNBytes(data, 0, data.length);
                last = new Frame(x, y, width, height, bytesPerPixel, bigEndian, data);
            }
            return last;
        }

        /**
         * The RFB challenge encrypted the way the protocol says, written out here rather than
         * called from the server: a test that used the server's own method would agree with it on
         * any mistake they shared. The bit reversal is the part that is easy to get wrong.
         */
        private static byte[] vncEncrypt(byte[] challenge, String password) throws IOException {
            byte[] key = new byte[8];
            byte[] bytes = password.getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 8; i++) {
                int value = i < bytes.length ? bytes[i] & 0xFF : 0;
                int reversed = 0;
                for (int bit = 0; bit < 8; bit++) {
                    reversed = (reversed << 1) | ((value >> bit) & 1);
                }
                key[i] = (byte) reversed;
            }
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "DES"));
                return cipher.doFinal(challenge);
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        // --- the wire, both directions -----------------------------------

        private int readU16() throws IOException {
            int high = in.read();
            int low = in.read();
            if ((high | low) < 0) {
                throw new EOFException("the server closed the connection");
            }
            return (high << 8) | low;
        }

        private int readInt() throws IOException {
            int a = in.read();
            int b = in.read();
            int c = in.read();
            int d = in.read();
            if ((a | b | c | d) < 0) {
                throw new EOFException("the server closed the connection");
            }
            return (a << 24) | (b << 16) | (c << 8) | d;
        }

        private byte[] readBytes(int count) throws IOException {
            byte[] bytes = new byte[count];
            in.readNBytes(bytes, 0, count);
            return bytes;
        }

        private String readAscii(int count) throws IOException {
            return new String(readBytes(count), StandardCharsets.US_ASCII);
        }

        private static void writeU16(OutputStream out, int value) throws IOException {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private static void writeU16(byte[] bytes, int at, int value) {
            bytes[at] = (byte) (value >> 8);
            bytes[at + 1] = (byte) value;
        }

        private static void writeInt(OutputStream out, int value) throws IOException {
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }
    }
}
