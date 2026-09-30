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
 * item describes: **raw and ZRLE** (no Tight, no CoRRE, no JPEG - every client can decode raw, so a
 * client that offers nothing else still works, and ZRLE is used when the client lists it), **true
 * colour only**, and one rectangle per update.
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

    /**
     * Where a session's bytes come from and go to.
     *
     * <p>Two implementations, and that is the whole of the browser path (D2): a plain socket for a
     * VNC client, and the same bytes inside WebSocket frames for a page. Everything below this line -
     * the handshake, the updates, the input, the clipboard - is written against the streams and does
     * not know which one it has.
     */
    interface RfbTransport {

        String peer();

        InputStream in() throws IOException;

        OutputStream out() throws IOException;

        /** A read timeout for the next byte, in milliseconds; 0 for none. */
        void readTimeout(int millis) throws IOException;

        void close();
    }

    private static final Logger log = LoggerFactory.getLogger(RfbSession.class);

    /** What this server speaks; a client that asks for less is answered in its own terms. */
    private static final int MAJOR = 3;
    private static final int MINOR = 8;

    /** RFB's security types: no authentication at all, and the DES challenge the VNC scheme uses. */
    private static final int SECURITY_NONE = 1;
    private static final int SECURITY_VNC = 2;

    /**
     * The longest clipboard text this server will read from a client.
     *
     * <p>A length field is the client's word, and a client that says "four gigabytes" must not make
     * the server try to allocate them: measured while writing the live check, where a client whose
     * padding was one byte short had its text length read from the wrong offset. 16 MB is far more
     * than a clipboard, and a client that claims more is told so rather than believed.
     */
    private static final int MAX_CUT_TEXT_BYTES = 16 * 1024 * 1024;

    /** Client to server message types. */
    private static final int SET_PIXEL_FORMAT = 0;
    private static final int SET_ENCODINGS = 2;
    private static final int FRAMEBUFFER_UPDATE_REQUEST = 3;
    private static final int KEY_EVENT = 4;
    private static final int POINTER_EVENT = 5;
    private static final int CLIENT_CUT_TEXT = 6;

    /**
     * How long the message loop waits for a client's next byte before it looks at its own pending
     * work. It is what lets a session that is idle - no update request outstanding, so its thread is
     * blocked reading - still deliver a {@code ServerCutText} within half a second (D2's clipboard).
     * The timeout is set for the first byte of a message and cleared for the body, so a client that
     * dribbles a message out can never have it split by a timeout.
     */
    private static final int IDLE_POLL_MILLIS = 500;

    /** The encodings this server sends, and the pseudo-encoding a resize is told with. */
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_ZRLE = 16;
    private static final int ENCODING_CURSOR = -239;
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

    private final VncBackend backend;
    private final RfbTransport transport;
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
     * True when the client listed ZRLE (16), which decides how every update is sent.
     *
     * <p>Offered and not demanded, and chosen once: a client that lists ZRLE can always decode it, so
     * there is nothing to negotiate per update - and a client that does not list it gets raw frames,
     * which the protocol requires every client to understand.
     */
    private volatile boolean zrleWanted;

    /**
     * True when the client listed the Cursor pseudo-encoding (-239), which is how it is told what
     * the pointer looks like rather than drawing one of its own.
     */
    private volatile boolean cursorWanted;

    /**
     * This session's zlib stream, which outlives the updates (see {@link ZrleEncoder}).
     *
     * <p>Created with the session and ended with it: a {@code Deflater} holds memory outside the
     * JVM's heap, so a viewer that comes and goes must not leave one behind.
     */
    private final java.util.zip.Deflater deflater = new java.util.zip.Deflater(
            java.util.zip.Deflater.BEST_SPEED);

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
    /** Clipboard text waiting to be sent to this viewer, from the bridge's listener. */
    private String pendingCutText;

    /**
     * The pointer's image as this session last saw it, waiting to be sent, and the image it has
     * already sent.
     *
     * <p>{@code currentCursor} is remembered whether or not the client wants it, because the client
     * says so in its {@code SetEncodings} - which arrives after the session has been handed a few
     * frames. Without that, a viewer that asked for the cursor got one only the next time the pointer
     * changed shape, which for a pointer parked over a text field is never.
     */
    private CursorSprite pendingCursor;
    private CursorSprite currentCursor;
    private CursorSprite sentCursor;
    private int[] lastCursorPixels;

    /**
     * What this client has already been sent, one entry per screen pixel, and a scratch buffer for
     * one update's pixels.
     *
     * <p>This is the half of "much slower than Xvfb+x11vnc" that the pixel loops were not: an
     * application that repaints identical content (EasyShell's aliyun tab does it ~300 times a
     * second) marked damage every time, and this session sent the rectangle every time, 61 times a
     * second and 5.4 MB/s of pixels the viewer already had - measured against the same screen on
     * {@code x11vnc}, which sends nothing at all in that state because it compares before it sends.
     *
     * <p>{@code sentPixels} is null until the first frame goes out and is dropped on a resize, so a
     * region that has never been sent is always sent. {@code regionBuffer} is reused because a
     * full-screen update allocated 4 MB per frame before this, which is a garbage collector's worth
     * of work for a frame that is usually skipped.
     */
    private volatile int[] sentPixels;
    private int[] regionBuffer;
    /** One ZRLE tile's packed pixels, reused for the same reason {@link #regionBuffer} is. */
    private int[] tileScratch;

    private volatile boolean running = true;

    /** The pointer's last position and button state, so a change can be told from a repeat. */
    private int pointerX;
    private int pointerY;
    private int buttonMask;

    private byte[] pixels = new byte[0];

    RfbSession(VncBackend backend, RfbTransport transport, Screen screen,
               DisplayBackend.InputSink input, String name, String password) throws IOException {
        this.backend = backend;
        this.transport = transport;
        this.screen = screen;
        this.input = input;
        this.name = name;
        this.password = password;
        this.in = new BufferedInputStream(transport.in(), 8192);
        this.out = new BufferedOutputStream(transport.out(), 64 * 1024);
    }

    String peer() {
        return transport.peer();
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

    /**
     * The clipboard changed; this viewer is told, from <em>its own</em> thread.
     *
     * <p>Queued rather than written here because the caller is the clipboard bridge, holding the
     * server lock: a write to a socket that a viewer has stopped reading would block the clipboard
     * for every client. The session's thread picks it up on its next wake-up - which is also why a
     * session that is waiting for damage is woken by this.
     */
    void setClipboardText(String text) {
        synchronized (lock) {
            pendingCutText = text == null ? "" : text;
            lock.notifyAll();
        }
    }

    /**
     * The pointer's appearance changed; this viewer is told from its own thread, like the clipboard.
     *
     * <p>Compared by the pixel array and not by the sprite: the sprite is rebuilt every frame with
     * the pointer's position in it, so its identity changes whenever the mouse moves - and the image
     * is the same image. The input state caches the array per cursor, so two sprites built from one
     * cursor share it and a different cursor does not.
     */
    void setCursor(CursorSprite sprite) {
        if (sprite == null) {
            log.debug("VNC client {} has no pointer sprite yet", peer());
            return;
        }
        log.debug("VNC client {} was handed a {}x{} sprite", peer(), sprite.width(),
                sprite.height());
        synchronized (lock) {
            if (sprite.pixels() != lastCursorPixels) {
                lastCursorPixels = sprite.pixels();
                currentCursor = sprite;
            }
            queueCursor();
        }
    }

    /** Queues the pointer's image if the client wants one and has not been sent this one. */
    private void queueCursor() {
        if (!cursorWanted || currentCursor == null || currentCursor == sentCursor) {
            return;
        }
        sentCursor = currentCursor;
        pendingCursor = currentCursor;
        lock.notifyAll();
    }

    /** The screen changed size, which the client can only be told if it asked to hear it. */
    void screenResized() {
        synchronized (lock) {
            resized = true;
            // The pixels this client holds are of a screen that no longer exists, so nothing may be
            // skipped against them - the next update sends whatever the new screen is made of, and
            // the one after that compares against those pixels again.
            sentPixels = null;
            lock.notifyAll();
        }
    }

    void close() {
        running = false;
        deflater.end();
        synchronized (lock) {
            lock.notifyAll();
        }
        transport.close();
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
            transport.readTimeout(IDLE_POLL_MILLIS);
            int type;
            try {
                type = in.read();
            } catch (java.net.SocketTimeoutException idle) {
                // Nothing from the client, and that is the moment to send what is waiting: a
                // clipboard change is not damage, so it arrives whether or not an update was asked
                // for.
                flushPending();
                continue;
            }
            transport.readTimeout(0);                    // the rest of the message is not on a clock
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
            if (encoding == ENCODING_ZRLE) {
                zrleWanted = true;
            }
            if (encoding == ENCODING_CURSOR) {
                // A client that has just asked for the cursor is owed the one that is on the screen
                // now, not the next one to change.
                cursorWanted = true;
            }
        }
        synchronized (lock) {
            queueCursor();
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
        while (true) {
            // Whatever is queued that is not damage goes first, and on every pass: a fresh viewer's
            // first request is a full one, which is where a client that asked for the cursor expects
            // to hear about it. Outside the lock, because this writes to a socket and the display
            // tick is on the other side of that lock.
            flushPending();
            Rectangle send = null;
            boolean announceResize = false;
            synchronized (lock) {
                if (incremental) {
                    while (running && !resized && pendingCutText == null && pendingCursor == null
                            && (pending == null || pending.intersection(asked).isEmpty())) {
                        try {
                            lock.wait(1000);         // a wake-up is also how a closed socket is noticed
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
                        announceResize = desktopSizeWanted;
                    }
                    // Cleared *before* the pixels are read, so a draw that lands during the read is
                    // damage again and is sent by the update after this one.
                    if (pending != null) {
                        send = pending.intersection(asked);
                        pending = null;
                    }
                } else {
                    // A full request is answered with the pixels it asked about, so damage inside
                    // that area is no longer unsent - leaving it pending would send the same
                    // rectangle again to the next incremental request, and a viewer that just
                    // connected would see the damage it already has twice. Damage outside the area
                    // stays: the client did not ask about it and has not seen it.
                    send = asked;
                    if (pending != null && asked.contains(pending)) {
                        pending = null;
                    }
                    resized = false;
                }
            }
            if (announceResize) {
                sendDesktopSize();
            }
            if (send != null && !send.isEmpty() && sendUpdate(send, incremental)) {
                return;
            }
            if (!incremental) {
                // A full request is asked and answered once: a client that wants the pixels again
                // asks again, and one that got nothing new is told nothing.
                return;
            }
            // Nothing went out: the pixels are the ones this client already has, so the request
            // stays outstanding and the wait starts again. That is what a viewer sitting on a screen
            // that its application repaints identical content onto costs - which is nothing, and the
            // same answer `x11vnc` gives.
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

    /**
     * {@code ClientCutText}: the viewer copied something, and it belongs in the X clipboard.
     *
     * <p>It goes to the server's clipboard bridge, which is where the X selection's text lives; the
     * bridge then serves it to X clients and the Mac gets it too. With no bridge - a server without
     * a host pasteboard, or {@code --no-clipboard} - the text has nowhere to go and says so at debug
     * level rather than being silently dropped.
     */
    private void clientCutText() throws IOException {
        in.readNBytes(3);                            // padding
        int length = readInt();
        if (length < 0 || length > MAX_CUT_TEXT_BYTES) {
            throw new IOException("a clipboard of " + length + " bytes is not one this server will"
                    + " read (at most " + MAX_CUT_TEXT_BYTES + ")");
        }
        byte[] text = new byte[length];
        in.readNBytes(text, 0, text.length);
        String copied = new String(text, java.nio.charset.StandardCharsets.UTF_8);
        java.util.function.Consumer<String> sink = backend.clipboardSink();
        if (sink == null) {
            log.debug("VNC client {} copied {} byte(s), and this server has no clipboard to put"
                    + " them in", peer(), text.length);
            return;
        }
        log.debug("VNC client {} copied {} byte(s) into the X clipboard", peer(), text.length);
        sink.accept(copied);
    }

    /**
     * Sends whatever is queued that is not damage - a clipboard change, or the pointer's new shape.
     * Called from the session's own thread.
     */
    private void flushPending() throws IOException {
        String text;
        CursorSprite cursor;
        synchronized (lock) {
            text = pendingCutText;
            pendingCutText = null;
            cursor = pendingCursor;
            pendingCursor = null;
        }
        if (text != null) {
            sendCutText(text);
        }
        if (cursor != null) {
            sendCursor(cursor);
        }
    }

    /**
     * {@code Cursor} (-239): the pointer's image, so the viewer shows what the X session's pointer
     * looks like rather than a pointer of its own.
     *
     * <p>The rectangle's <b>x and y are the hotspot</b>, not a position - which is easy to get wrong,
     * because every other update's x and y are the corner of the region it describes. The client
     * reads them as the hotspot and draws the image so that point lands on the pointer (measured in
     * noVNC's own source, which is the client this has to satisfy).
     */
    private void sendCursor(CursorSprite sprite) throws IOException {
        int width = sprite.width();
        int height = sprite.height();
        if (width <= 0 || height <= 0 || sprite.pixels().length < width * height) {
            // Nothing to draw: a cursor with no image is not one a viewer can be sent, and sending a
            // zero sized one is how this was found - an index out of bounds on the empty pixel array.
            return;
        }
        int bytesPerPixel = Math.max(1, format.bitsPerPixel / 8);
        // Not named `pixels`: that is the session's own raw-frame buffer, which `store` writes into,
        // and a local of the same name shadows it - so the images were written into the empty field
        // and the first pixel threw. Measured, and it is the kind of bug a name like `image` prevents.
        byte[] image = new byte[width * height * bytesPerPixel];
        int at = 0;
        for (int i = 0; i < width * height; i++) {
            at = store(image, packedPixel(sprite.pixels()[i]), at, bytesPerPixel);
        }
        // A one bit mask says which pixels are drawn at all: a cursor with an alpha channel gets its
        // transparency from this, because the pseudo-encoding has no room for a per-pixel alpha.
        byte[] mask = new byte[((width + 7) / 8) * height];
        for (int i = 0; i < width * height; i++) {
            if ((sprite.pixels()[i] >>> 24) != 0) {
                mask[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }

        out.write(0);                                // FramebufferUpdate
        out.write(0);                                // padding
        writeU16(1);                                 // one rectangle
        writeU16(sprite.hotX());
        writeU16(sprite.hotY());
        writeU16(width);
        writeU16(height);
        writeInt(ENCODING_CURSOR);
        out.write(image);
        out.write(mask);
        out.flush();
        log.debug("VNC client {} sent a {}x{} cursor, hotspot {},{}", peer(), width, height,
                sprite.hotX(), sprite.hotY());
    }

    /** {@code ServerCutText}: what the clipboard holds now, so the viewer can paste it. */
    private void sendCutText(String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(3);                                // ServerCutText
        out.write(new byte[3]);                      // padding
        writeInt(bytes.length);
        out.write(bytes);
        out.flush();
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
     *
     * <p>{@code compare} asks for the pixels to be held up against what this client already has
     * before anything is written: the rectangle that goes out is the box the differences are in (see
     * {@link #changedBox}), and false means there was no difference at all - nothing went out, and
     * the caller waits for the next damage instead of answering the request. A full request passes
     * false: a client that asks for the pixels is given them, whatever it already holds.
     */
    private boolean sendUpdate(Rectangle region, boolean compare) throws IOException {
        if (compare) {
            region = changedBox(region);
            if (region == null) {
                return false;
            }
        }
        int width = region.width;
        int height = region.height;
        int[] argb = regionPixels(region, width, height);

        out.write(0);                                // FramebufferUpdate
        out.write(0);                                // padding
        writeU16(1);                                 // one rectangle
        writeU16(region.x);
        writeU16(region.y);
        writeU16(width);
        writeU16(height);
        if (zrleWanted) {
            int cpxel = compressedPixelBytes();
            if (tileScratch == null) {
                tileScratch = new int[64 * 64];
            }
            byte[] zrle = ZrleEncoder.encode(argb, width, height, this::packedPixel, cpxel,
                    deflater, tileScratch);
            writeInt(ENCODING_ZRLE);
            // The length of the compressed data, which ZRLE carries in the rectangle itself rather
            // than in the message header - a reader that took it for pixels would lose the frame.
            writeInt(zrle.length);
            out.write(zrle);
        } else {
            int bytesPerPixel = Math.max(1, format.bitsPerPixel / 8);
            int needed = width * height * bytesPerPixel;
            if (pixels.length != needed) {
                // One buffer per session, reused: an update that reallocated 4 MB each time would put
                // the alliance of the collector and the socket in the way of every frame.
                pixels = new byte[needed];
            }
            int at = 0;
            // `width * height` and not `argb.length`: the region buffer is reused and may be longer
            // than this update, and a stale tail would be sent as pixels.
            for (int i = 0; i < width * height; i++) {
                at = store(pixels, packedPixel(argb[i]), at, bytesPerPixel);
            }
            writeInt(ENCODING_RAW);
            out.write(pixels);
        }
        out.flush();
        rememberSent(region, argb, width, height);
        return true;
    }

    /**
     * The part of {@code region} this client has not been sent, or null when it has all of it.
     *
     * <p>The comparison is against the framebuffer directly and not against a copy, and it is the
     * reason a viewer watching an application that redraws identical content costs no bandwidth: the
     * drawing still marks damage, so the display tick still wakes this session up, and this answers
     * "the viewer has those pixels already", so no update is built at all.
     *
     * <p>The answer is the <b>bounding box of the pixels that really differ</b> and not the damage
     * rectangle. An application that repaints a whole window to blink a text cursor damages the whole
     * window, and sending that is ~100 KB of ZRLE for two cells of pixels - measured on the bed, as
     * twice a second with the aliyun tab sitting still (down from 61 times a second before the
     * comparison existed at all, which is what the "less smooth than x11vnc" report was). `x11vnc`
     * answers the same way at 64x64 tile granularity; a rectangle of any size is a legal update, so
     * this is that idea without the tiles.
     *
     * <p>Comparing ARGB rather than the client's packed pixel is deliberately the stricter test: two
     * colours that pack to the same 16 bit value are still a difference here, which sends an update
     * a packed comparison would have skipped. Sending too much is a cost; skipping a real change is a
     * wrong screen. (Which is also why a row that differs has its extent found pixel by pixel while a
     * row that does not is one vectorised comparison.)
     */
    private Rectangle changedBox(Rectangle region) {
        int[] sent = sentPixels;
        if (sent == null) {
            return region;
        }
        io.github.oyzh1994.xserver.draw.Framebuffer framebuffer = screen.framebuffer();
        if (!framebuffer.allocated()) {
            return region;
        }
        int screenWidth = screen.width();
        if (sent.length < screenWidth * screen.height()) {
            return region;
        }
        int[] source = framebuffer.pixels();
        int stride = framebuffer.stride();
        int left = Integer.MAX_VALUE;
        int right = -1;
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < region.height; y++) {
            int row = (region.y + y) * stride + region.x;
            int base = (region.y + y) * screenWidth + region.x;
            if (java.util.Arrays.equals(sent, base, base + region.width,
                    source, row, row + region.width)) {
                continue;
            }
            int first = 0;
            int last = region.width - 1;
            while (first < region.width && sent[base + first] == source[row + first]) {
                first++;
            }
            while (last > first && sent[base + last] == source[row + last]) {
                last--;
            }
            left = Math.min(left, region.x + first);
            right = Math.max(right, region.x + last);
            if (top < 0) {
                top = region.y + y;
            }
            bottom = region.y + y;
        }
        if (right < 0) {
            return null;
        }
        return new Rectangle(left, top, right - left + 1, bottom - top + 1);
    }

    /** Remembers the pixels that went out, so the next update can be skipped if nothing changed. */
    private void rememberSent(Rectangle region, int[] argb, int width, int height) {
        int screenWidth = screen.width();
        int[] sent = sentPixels;
        if (sent == null || sent.length < screenWidth * screen.height()) {
            sent = new int[screenWidth * screen.height()];
            sentPixels = sent;
        }
        for (int y = 0; y < height; y++) {
            System.arraycopy(argb, y * width, sent, (region.y + y) * screenWidth + region.x, width);
        }
    }

    /**
     * How many bytes one pixel takes inside a ZRLE update.
     *
     * <p>The spec's compressed pixel: a 32 bit format whose channels all live in the low three bytes
     * sheds the unused one, so the common client - 32bpp, little endian, red at 16 - sends three
     * bytes per pixel rather than four. Getting this wrong is not an error a client reports; it is a
     * screen full of the wrong colours.
     */
    private int compressedPixelBytes() {
        int bytes = Math.max(1, format.bitsPerPixel / 8);
        if (bytes != 4 || format.depth > 24) {
            return bytes;
        }
        int highest = Math.max(Math.max(format.redShift + bitsFor(format.redMax),
                format.greenShift + bitsFor(format.greenMax)),
                format.blueShift + bitsFor(format.blueMax));
        return highest <= 24 ? 3 : 4;
    }

    private static int bitsFor(int max) {
        int bits = 0;
        while (max > 0) {
            bits++;
            max >>= 1;
        }
        return bits;
    }

    /** One pixel in the client's format, which both the raw and the ZRLE path send. */
    private int packedPixel(int argb) {
        return (scale((argb >> 16) & 0xFF, format.redMax) << format.redShift)
                | (scale((argb >> 8) & 0xFF, format.greenMax) << format.greenShift)
                | (scale(argb & 0xFF, format.blueMax) << format.blueShift);
    }

    /**
     * The region's pixels as ARGB, in row-major order, for whichever encoding sends them.
     *
     * <p>The buffer is the session's and reused - only its first {@code width * height} entries are
     * this update's - because a full-screen frame here was 4 MB of allocation per update before it.
     * Callers must not keep it past the next call.
     */
    private int[] regionPixels(Rectangle region, int width, int height) {
        io.github.oyzh1994.xserver.draw.Framebuffer framebuffer = screen.framebuffer();
        int count = width * height;
        int[] pixels = regionBuffer;
        if (pixels == null || pixels.length < count) {
            pixels = regionBuffer = new int[count];
        }
        if (!framebuffer.allocated()) {
            // Nothing has ever been drawn on this screen, and reading it through `pixels()` would
            // allocate the whole thing (E4 is about exactly that). Every pixel is the background.
            java.util.Arrays.fill(pixels, 0, count, framebuffer.pixel(0, 0));
            return pixels;
        }
        int[] source = framebuffer.pixels();
        int stride = framebuffer.stride();
        for (int y = 0; y < height; y++) {
            int row = (region.y + y) * stride + region.x;
            System.arraycopy(source, row, pixels, y * width, width);
        }
        return pixels;
    }

    /**
     * One pixel into {@code target}, in the client's byte order and width.
     *
     * <p>The target is a parameter and not the session's own buffer: it was read from a field, and a
     * caller with a local array of its own - the cursor image - then wrote into the session's raw
     * frame buffer instead, which is empty until the first frame. The first pixel of the first cursor
     * threw, which is how it was found.
     */
    private int store(byte[] target, int value, int at, int bytesPerPixel) {
        if (format.bigEndian != 0) {                 // big endian
            for (int i = bytesPerPixel - 1; i >= 0; i--) {
                target[at++] = (byte) (value >> (8 * i));
            }
        } else {
            for (int i = 0; i < bytesPerPixel; i++) {
                target[at++] = (byte) (value >> (8 * i));
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
