package io.github.oyzh1994.xserver.display;

import io.github.oyzh1994.xserver.input.Keymap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * One remote framebuffer (RFB, "VNC") connection: the handshake, the client's own messages, and the
 * updates that go back.
 *
 * <p>This is the protocol half of backlog D2, so that the framebuffer this server already has can be
 * watched and driven from another machine with software that is already on it - anything that speaks
 * VNC, which is every desktop and every phone. It is deliberately the *smallest honest* version the
 * item describes: **Raw encoding only** (no ZRLE, no Tight, no JPEG), **true colour only**, and one
 * rectangle per update.
 *
 * <p><b>The shape of the thing is the client's, not ours.</b> RFB is request-driven: a client asks
 * for an update and the server answers when the area it asked about has changed. So nothing here
 * sends anything on its own, and a client that stops asking costs nothing - which is also this
 * server's backpressure story (E5) arriving for free, since the only thread that ever waits for a
 * client is that client's own.
 *
 * <p><b>What it does not do, and why each is a decision rather than an omission.</b>
 *
 * <ul>
 *   <li><b>No security type but "None".</b> A VNC client that connects gets the display, which is the
 *       same posture the X port has by default ({@code -ac}, every interface). {@code VNC
 *       Authentication} (DES with the X cookie as the password) is the next step and is recorded in
 *       the backlog; until then the honest thing is a log line at startup and a client-side prompt
 *       that says what it is connecting to.
 *   <li><b>The pointer is the client's own.</b> The server does not composite the X cursor sprite
 *       into the pixels it sends - VNC clients draw the local pointer themselves - so the *shape* of
 *       the X cursor (an I-beam over a text field) is not shown. The `Cursor` pseudo-encoding would
 *       fix that; it is not what the first version needs.
 *   <li><b>A key that no keycode carries is dropped.</b> RFB sends X keysyms, and this server's
 *       keymap is a US one, so an accented character or a Chinese one has no keycode to press.
 *       {@code InputState.keyTyped} is how the server types such a character when *it* is the one
 *       that composed it, and RFB 3.8 has no message for "here is a character" - the extended key
 *       event extension does.
 *   <li><b>An update can catch a draw in flight.</b> The pixels are copied outside the server lock,
 *       like every other backend's, so a frame can contain a row from the draw that is happening
 *       while it is copied. The draw that did it marks damage, that damage becomes the next pending
 *       rectangle, and the next update repairs it - VNC has no stronger promise than that.
 * </ul>
 */
final class RfbSession implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RfbSession.class);

    /** What this server speaks; a client that asks for less is answered in its own terms. */
    private static final int MAJOR = 3;
    private static final int MINOR = 8;

    /** RFB's security types: no authentication at all, and the DES challenge the VNC scheme uses. */
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC = 2;

    /** Client to server message types. */
    private static final int SET_PIXEL_FORMAT = 0;
    private static final int SET_ENCODINGS = 2;
    private static final int FRAMEBUFFER_UPDATE_REQUEST = 3;
    private static final int KEY_EVENT = 4;
    private static final int POINTER_EVENT = 5;
    private static final int CLIENT_CUT_TEXT = 6;

    /** The one encoding this server sends, and the pseudo-encoding a resize is told with. */
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_DESKTOP_SIZE = -223;

    /**
     * The pixel format a client asked for, in the values the protocol sends.
     *
     * <p>Values rather than the sixteen bytes, because the bytes are what the wire wants and these
     * are what the conversion wants: a class that tried to be both is how a format with two-byte
     * maxima ends up thirteen bytes long and takes the connection with it (the handshake after it
     * fails, and the client sees a socket that closed for no reason).
     */
    private static final class PixelFormat {
        int bitsPerPixel = 32;
        int depth = 24;
        int bigEndian;
        int trueColour = 1;
        int redMax = 255;
        int greenMax = 255;
        int blueMax = 255;
        int redShift = 16;
        int greenShift = 8;
        int blueShift;

        /** The sixteen bytes of the handshake's ServerInit, in the order it wants them. */
        byte[] toBytes() {
            return new byte[] {
                (byte) bitsPerPixel, (byte) depth, (byte) bigEndian, (byte) trueColour,
                (byte) (redMax >> 8), (byte) redMax,
                (byte) (greenMax >> 8), (byte) greenMax,
                (byte) (blueMax >> 8), (byte) blueMax,
                (byte) redShift, (byte) greenShift, (byte) blueShift,
                0, 0, 0,
            };
        }
    }

    private final Socket socket;
    private final Screen screen;
    private final DisplayBackend.InputSink input;
    private final String name;
    /** The password a client must type, or null when the display is served to anyone who asks. */
    private final String password;

    private final InputStream in;
    private final OutputStream out;

    /** The client's pixel format, as it asked for it in {@code SetPixelFormat}. */
    private final PixelFormat format = new PixelFormat();

    /** True once the client listed the DesktopSize pseudo-encoding, which is how a resize is told. */
    private volatile boolean desktopSizeWanted;

    /**
     * Damage not yet sent to this client, and the lock that makes waiting on it possible.
     *
     * <p>It is a union rather than a list: a client that is behind gets everything that changed,
     * which is the right answer for a screen - the pixels are what matter, not the order they were
     * drawn in - and it is bounded by the screen, so a client that never asks cannot grow it.
     */
    private final Object lock = new Object();
    private Rectangle pending;
    private boolean resized;

    private volatile boolean running = true;

    /** The pointer's last position and button state, so a change can be told from a repeat. */
    private int pointerX;
    private int pointerY;
    private int buttonMask;

    private byte[] pixels = new byte[0];

    RfbSession(Socket socket, Screen screen, DisplayBackend.InputSink input, String name,
               String password) throws IOException {
        this.socket = socket;
        this.screen = screen;
        this.input = input;
        this.name = name;
        this.password = password;
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream(), 8192);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    String peer() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void run() {
        try {
            handshake();
            log.info("VNC client {} connected: {}x{} at {}bpp, {}", peer(), screen.width(),
                    screen.height(), format.bitsPerPixel,
                    desktopSizeWanted ? "desktop size supported" : "no desktop size");
            serve();
        } catch (EOFException e) {
            log.debug("VNC client {} went away", peer());
        } catch (IOException e) {
            if (running) {
                log.debug("VNC client {} failed", peer(), e);
            }
        } catch (RuntimeException e) {
            log.warn("VNC client {} failed", peer(), e);
        } finally {
            close();
        }
    }

    /** The damage this client has to be told about, from the display tick. */
    void addDamage(Rectangle damage) {
        synchronized (lock) {
            pending = pending == null ? new Rectangle(damage) : pending.union(damage);
            lock.notifyAll();
        }
    }

    /** The screen changed size, which the client can only be told if it asked to hear it. */
    void screenResized() {
        synchronized (lock) {
            resized = true;
            lock.notifyAll();
        }
    }

    void close() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("closing the VNC socket", e);
        }
    }

    // ------------------------------------------------------------------
    // The handshake
    // ------------------------------------------------------------------

    private void handshake() throws IOException {
        writeAscii("RFB 003.008\n");
        out.flush();
        String version = readAscii(12);
        if (!version.startsWith("RFB ") || !version.endsWith("\n")) {
            throw new IOException("not an RFB client: " + version.trim());
        }
        int minor = parseMinor(version);
        int offered = password == null ? SECURITY_NONE : SECURITY_VNC;
        if (minor >= 7) {
            // 3.7 and later: the server lists its security types and the client chooses one. With a
            // password configured there is exactly one offer and it is not None - offering both would
            // let a client skip the password, which is the same as not having one.
            out.write(1);
            out.write(offered);
            out.flush();
            int chosen = in.read();
            if (chosen == 0) {
                // The client read the list and refused all of it; it sends the reason next.
                throw new IOException("the client refused the security types: " + readReason());
            }
            if (chosen != offered) {
                throw new IOException("the client chose security type " + chosen
                        + ", and this server offers only " + offered);
            }
            if (chosen == SECURITY_VNC && !vncAuthentication()) {
                writeInt(1);                        // SecurityResult: failed
                writeReason("Authentication failed");
                out.flush();
                throw new IOException("VNC authentication failed");
            }
            if (minor >= 8) {
                writeInt(0);                        // SecurityResult: OK
                out.flush();
            }
        } else {
            // 3.3: one four byte security type instead of a list, and no result after it.
            writeInt(offered);
            out.flush();
            if (offered == SECURITY_VNC && !vncAuthentication()) {
                writeInt(1);
                writeReason("Authentication failed");
                out.flush();
                throw new IOException("VNC authentication failed");
            }
        }
        in.read();                                   // ClientInit: the shared flag, which changes nothing here

        byte[] init = new byte[24];
        init[0] = (byte) (screen.width() >> 8);
        init[1] = (byte) screen.width();
        init[2] = (byte) (screen.height() >> 8);
        init[3] = (byte) screen.height();
        System.arraycopy(format.toBytes(), 0, init, 4, 16);
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        init[20] = (byte) (nameBytes.length >> 24);
        init[21] = (byte) (nameBytes.length >> 16);
        init[22] = (byte) (nameBytes.length >> 8);
        init[23] = (byte) nameBytes.length;
        out.write(init);
        out.write(nameBytes);
        out.flush();
    }

    /**
     * The VNC scheme: a sixteen byte challenge, and the client's answer is the challenge encrypted
     * with the password as a DES key.
     *
     * <p><b>The trick is the key's bit order</b>: RFB uses the password bytes with the bits of each
     * byte reversed, which is not what any DES library does by default and not something a wrong
     * implementation fails loudly at - it just refuses every password. It is why this code is
     * written out rather than tucked away, and why the test encrypts the challenge itself.
     */
    private boolean vncAuthentication() throws IOException {
        byte[] challenge = new byte[16];
        new java.security.SecureRandom().nextBytes(challenge);
        out.write(challenge);
        out.flush();
        byte[] response = new byte[16];
        in.readNBytes(response, 0, 16);
        byte[] expected = encryptChallenge(challenge, password);
        boolean ok = java.security.MessageDigest.isEqual(expected, response);
        if (!ok) {
            log.info("VNC client {} gave the wrong password", peer());
        }
        return ok;
    }

    /** The challenge encrypted the way RFB defines it: password as a DES key, bits reversed. */
    static byte[] encryptChallenge(byte[] challenge, String password) throws IOException {
        byte[] key = new byte[8];
        byte[] bytes = password.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 8; i++) {
            int value = i < bytes.length ? bytes[i] & 0xFF : 0;
            key[i] = (byte) (Integer.reverse(value) >>> 24);
        }
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key,
                    "DES"));
            return cipher.doFinal(challenge);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot run the VNC authentication: " + e.getMessage(), e);
        }
    }

    private void writeReason(String reason) throws IOException {
        byte[] bytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeInt(bytes.length);
        out.write(bytes);
    }

    /** The minor version a client announces, or 3 when it says something unparseable. */
    private static int parseMinor(String version) {
        try {
            String[] parts = version.substring(4).trim().split("\\.");
            return Integer.parseInt(parts[1]);
        } catch (RuntimeException e) {
            return 3;
        }
    }

    private String readReason() throws IOException {
        int length = readInt();
        byte[] reason = new byte[Math.max(0, Math.min(length, 4096))];
        in.readNBytes(reason, 0, reason.length);
        return new String(reason, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // The messages a client sends
    // ------------------------------------------------------------------

    private void serve() throws IOException {
        while (running) {
            int type = in.read();
            if (type < 0) {
                return;
            }
            switch (type) {
                case SET_PIXEL_FORMAT -> setPixelFormat();
                case SET_ENCODINGS -> setEncodings();
                case FRAMEBUFFER_UPDATE_REQUEST -> updateRequest();
                case KEY_EVENT -> keyEvent();
                case POINTER_EVENT -> pointerEvent();
                case CLIENT_CUT_TEXT -> clientCutText();
                default -> throw new IOException("unknown RFB message type " + type
                        + ": this server sends raw frames and speaks 3.8, and a message it cannot"
                        + " skip is one it cannot stay in step with");
            }
        }
    }

    /**
     * {@code SetPixelFormat}: what the client wants its pixels in.
     *
     * <p>Honoured rather than ignored, because it is how a client says what it can draw: the
     * handshake's default is the format the framebuffer already holds, and a client that asks for
     * 16 bits or for blue in the high byte gets it converted. A **colour-mapped** client (true colour
     * off) is refused: the server would have to maintain and send the map, and every client this is
     * for asks for true colour.
     */
    private void setPixelFormat() throws IOException {
        in.readNBytes(3);                            // padding
        int bitsPerPixel = in.read();
        int depth = in.read();
        int bigEndian = in.read();
        int trueColour = in.read();
        int redMax = readU16();
        int greenMax = readU16();
        int blueMax = readU16();
        int redShift = in.read();
        int greenShift = in.read();
        int blueShift = in.read();
        in.readNBytes(3);                            // padding
        if (trueColour == 0) {
            throw new IOException("a colour-mapped client is not supported: this server sends true"
                    + " colour only, and a client that needs a map would guess at every colour");
        }
        if (bitsPerPixel != 8 && bitsPerPixel != 16 && bitsPerPixel != 32) {
            throw new IOException("unsupported pixel format: " + bitsPerPixel + " bits per pixel");
        }
        format.bitsPerPixel = bitsPerPixel;
        format.depth = depth;
        format.bigEndian = bigEndian;
        format.trueColour = trueColour;
        format.redMax = redMax;
        format.greenMax = greenMax;
        format.blueMax = blueMax;
        format.redShift = redShift;
        format.greenShift = greenShift;
        format.blueShift = blueShift;
        log.debug("VNC client {} asked for {}bpp {}, r/g/b max {}/{}/{} at shifts {}/{}/{}", peer(),
                bitsPerPixel, bigEndian != 0 ? "big endian" : "little endian", redMax, greenMax,
                blueMax, redShift, greenShift, blueShift);
    }

    private void setEncodings() throws IOException {
        in.read();                                   // padding
        int count = readU16();
        for (int i = 0; i < count; i++) {
            int encoding = readInt();
            if (encoding == ENCODING_DESKTOP_SIZE) {
                desktopSizeWanted = true;
            }
        }
        // Everything else is left unsaid: a client that lists ZRLE or Tight gets raw frames, which
        // every one of them can read - the list is what a client *can* decode, not what it demands.
    }

    /**
     * {@code FramebufferUpdateRequest}: the client's own pace, and the whole of the server's.
     *
     * <p>A non-incremental request is answered at once; an incremental one waits for damage inside
     * the area asked about. That wait is what makes an idle viewer cost nothing at all.
     */
    private void updateRequest() throws IOException {
        boolean incremental = in.read() != 0;
        int x = readU16();
        int y = readU16();
        int width = readU16();
        int height = readU16();
        Rectangle asked = new Rectangle(x, y, width, height)
                .intersection(new Rectangle(0, 0, screen.width(), screen.height()));
        if (asked.isEmpty()) {
            return;
        }
        Rectangle send;
        synchronized (lock) {
            if (incremental) {
                while (running && !resized && (pending == null || pending.intersection(asked).isEmpty())) {
                    try {
                        lock.wait(1000);             // a wake-up is also how a closed socket is noticed
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                if (resized) {
                    resized = false;
                    if (desktopSizeWanted) {
                        sendDesktopSize();
                    }
                }
                if (pending == null) {
                    return;
                }
                // Cleared *before* the pixels are copied, so a draw that lands during the copy is
                // damage again and is sent by the update after this one.
                send = pending.intersection(asked);
                pending = null;
            } else {
                // A full request is answered with the pixels it asked about, so damage inside that
                // area is no longer unsent - leaving it pending would send the same rectangle again
                // to the next incremental request, and a viewer that just connected would see the
                // damage it already has twice. Damage outside the area stays: the client did not
                // ask about it and has not seen it.
                send = asked;
                if (pending != null && asked.contains(pending)) {
                    pending = null;
                }
                resized = false;
            }
        }
        if (!send.isEmpty()) {
            sendUpdate(send);
        }
    }

    private void keyEvent() throws IOException {
        boolean pressed = in.read() != 0;
        in.readNBytes(2);                            // padding
        int keysym = readInt();
        int keycode = Keymap.keycodeForKeysym(keysym);
        if (keycode == 0) {
            log.debug("VNC client {} pressed keysym 0x{}: no keycode carries it, and RFB has no"
                    + " message for a character", peer(), Integer.toHexString(keysym));
            return;
        }
        input.keyChanged(keycode, pressed, pointerX, pointerY);
    }

    private void pointerEvent() throws IOException {
        int mask = in.read();
        int x = readU16();
        int y = readU16();
        boolean moved = x != pointerX || y != pointerY;
        pointerX = x;
        pointerY = y;
        if (moved) {
            input.pointerMoved(x, y);
        }
        int changed = mask ^ buttonMask;
        buttonMask = mask;
        for (int bit = 0; bit < 8; bit++) {
            if ((changed & (1 << bit)) != 0) {
                // RFB numbers the buttons as X does, wheel included: 1 left, 2 middle, 3 right, 4 and
                // 5 the wheel - which is exactly what InputSink.buttonChanged expects.
                input.buttonChanged(bit + 1, (mask & (1 << bit)) != 0, x, y);
            }
        }
    }

    private void clientCutText() throws IOException {
        in.readNBytes(3);                            // padding
        int length = readInt();
        byte[] text = new byte[Math.max(0, length)];
        in.readNBytes(text, 0, text.length);
        // The host clipboard bridge is not wired to this: a viewer's clipboard and the X selection
        // are two bridges away from each other (backlog D2 says so, and the C4 bridge is the X side).
        log.debug("VNC client {} sent {} byte(s) of clipboard text, which this server does not bridge",
                peer(), text.length);
    }

    // ------------------------------------------------------------------
    // What goes back
    // ------------------------------------------------------------------

    /** A resize, told the way RFB tells it: a rectangle of no pixel data at all. */
    private void sendDesktopSize() throws IOException {
        out.write(0);                                // FramebufferUpdate
        out.write(0);                                // padding
        writeU16(1);                                 // one rectangle
        writeU16(0);
        writeU16(0);
        writeU16(screen.width());
        writeU16(screen.height());
        writeInt(ENCODING_DESKTOP_SIZE);
        out.flush();
        log.debug("VNC client {} told the screen is now {}x{}", peer(), screen.width(),
                screen.height());
    }

    /**
     * One raw rectangle: the header, then every pixel in the client's own format.
     *
     * <p>Raw is <em>w * h * (bits per pixel / 8)</em> bytes with no compression, which is why the
     * updates are damage-shaped rather than screen-shaped: a full 1300x800 frame is 4 MB, and a
     * keystroke in a terminal is a few hundred bytes.
     */
    private void sendUpdate(Rectangle region) throws IOException {
        int width = region.width;
        int height = region.height;
        int bytesPerPixel = Math.max(1, format.bitsPerPixel / 8);
        int needed = width * height * bytesPerPixel;
        if (pixels.length != needed) {
            // One buffer per session, reused: an update that reallocated 4 MB each time would put
            // the alliance of the collector and the socket in the way of every frame.
            pixels = new byte[needed];
        }
        copyPixels(region, width, height, bytesPerPixel);

        out.write(0);                                // FramebufferUpdate
        out.write(0);                                // padding
        writeU16(1);                                 // one rectangle
        writeU16(region.x);
        writeU16(region.y);
        writeU16(width);
        writeU16(height);
        writeInt(ENCODING_RAW);
        out.write(pixels);
        out.flush();
    }

    private void copyPixels(Rectangle region, int width, int height, int bytesPerPixel) {
        io.github.oyzh1994.xserver.draw.Framebuffer framebuffer = screen.framebuffer();
        if (!framebuffer.allocated()) {
            // Nothing has ever been drawn on this screen, and reading it through `pixels()` would
            // allocate the whole thing (E4 is about exactly that). Every pixel is the background.
            int background = framebuffer.pixel(0, 0);
            int at = 0;
            for (int i = 0; i < width * height; i++) {
                at = store(background, at, bytesPerPixel);
            }
            return;
        }
        int[] source = framebuffer.pixels();
        int stride = framebuffer.stride();
        int redMax = format.redMax;
        int greenMax = format.greenMax;
        int blueMax = format.blueMax;
        int redShift = format.redShift;
        int greenShift = format.greenShift;
        int blueShift = format.blueShift;
        int at = 0;
        for (int y = 0; y < height; y++) {
            int row = (region.y + y) * stride + region.x;
            for (int x = 0; x < width; x++) {
                int argb = source[row + x];
                int value = (scale((argb >> 16) & 0xFF, redMax) << redShift)
                        | (scale((argb >> 8) & 0xFF, greenMax) << greenShift)
                        | (scale(argb & 0xFF, blueMax) << blueShift);
                at = store(value, at, bytesPerPixel);
            }
        }
    }

    /** One pixel into the buffer, in the client's byte order and width. */
    private int store(int value, int at, int bytesPerPixel) {
        if (format.bigEndian != 0) {                 // big endian
            for (int i = bytesPerPixel - 1; i >= 0; i--) {
                pixels[at++] = (byte) (value >> (8 * i));
            }
        } else {
            for (int i = 0; i < bytesPerPixel; i++) {
                pixels[at++] = (byte) (value >> (8 * i));
            }
        }
        return at;
    }

    /** A channel of ours as a channel of the client's, which may be narrower than eight bits. */
    private static int scale(int channel, int max) {
        return max >= 255 ? channel : (channel * max + 127) / 255;
    }

    // ------------------------------------------------------------------
    // The wire
    // ------------------------------------------------------------------

    private int readU16() throws IOException {
        int high = in.read();
        int low = in.read();
        if ((high | low) < 0) {
            throw new EOFException("the client went away");
        }
        return (high << 8) | low;
    }

    private int readInt() throws IOException {
        int a = in.read();
        int b = in.read();
        int c = in.read();
        int d = in.read();
        if ((a | b | c | d) < 0) {
            throw new EOFException("the client went away");
        }
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private void writeU16(int value) throws IOException {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeInt(int value) throws IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeAscii(String text) throws IOException {
        out.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private String readAscii(int length) throws IOException {
        byte[] bytes = new byte[length];
        in.readNBytes(bytes, 0, length);
        return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
