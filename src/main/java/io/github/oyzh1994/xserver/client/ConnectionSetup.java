package io.github.oyzh1994.xserver.client;

import com.github.moaxcp.x11.protocol.XAuthority;
import com.github.moaxcp.x11.protocol.xproto.Setup;
import com.github.moaxcp.x11.protocol.xproto.SetupRequest;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.Screen;
import org.eclipse.collections.api.list.primitive.ByteList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.factory.primitive.ByteLists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The connection setup handshake: validates the client's credentials and answers with the
 * {@code Setup} reply describing the server's screens, visuals and formats.
 */
public final class ConnectionSetup {

    private static final Logger log = LoggerFactory.getLogger(ConnectionSetup.class);

    public static final String AUTH_COOKIE = "MIT-MAGIC-COOKIE-1";

    /** Protocol version we speak. 11.0 is the version every client understands. */
    private static final short PROTOCOL_MAJOR = 11;
    private static final short PROTOCOL_MINOR = 0;

    /** Reported as the server's release number; arbitrary but stable. */
    private static final int RELEASE_NUMBER = 12100000;

    private static final byte MIN_KEYCODE = 8;
    private static final byte MAX_KEYCODE = (byte) 0xFF;

    private static final byte IMAGE_BYTE_ORDER_LSB = 0;
    private static final byte IMAGE_BYTE_ORDER_MSB = 1;
    private static final byte BITMAP_BIT_ORDER_LSB = 0;
    private static final byte BITMAP_SCANLINE_UNIT = 32;
    private static final byte BITMAP_SCANLINE_PAD = 32;

    private static final String VENDOR = "xserver";

    private final XServer server;

    public ConnectionSetup(XServer server) {
        this.server = server;
    }

    /** Handles a fresh connection, sending either the setup reply or a failure. */
    public void accept(ClientConnection client, SetupRequest request) throws IOException {
        String authName = asAscii(request.getAuthorizationProtocolName());
        byte[] authData = toBytes(request.getAuthorizationProtocolData());

        if (!authenticate(client, authName, authData)) {
            log.info("rejecting client {}: authentication failed", client.peer());
            sendFailed(client, "Authentication rejected");
            return;
        }
        if (request.getProtocolMajorVersion() != PROTOCOL_MAJOR) {
            log.info("rejecting client {}: protocol version {}.{}", client.peer(),
                    request.getProtocolMajorVersion(), request.getProtocolMinorVersion());
            sendFailed(client, "Protocol version not supported");
            return;
        }
        sendSetup(client);
    }

    private boolean authenticate(ClientConnection client, String authName, byte[] authData) {
        if (!server.options().authRequired() || authName.isEmpty()) {
            // No credentials asked for, or none offered: accept, matching an -ac server.
            return true;
        }
        if (AUTH_COOKIE.equals(authName)) {
            return checkCookie(authData);
        }
        log.info("unknown authorization protocol '{}'", authName);
        return false;
    }

    private boolean checkCookie(byte[] authData) {
        File file = server.options().authFile();
        if (file != null && file.exists() && checkCookieIn(file, authData)) {
            return true;
        }
        File defaultFile = XAuthority.getXAuthorityFile();
        if (defaultFile != null && defaultFile.exists() && checkCookieIn(defaultFile, authData)) {
            return true;
        }
        log.warn("no matching MIT-MAGIC-COOKIE-1 entry found; rejecting client");
        return false;
    }

    private boolean checkCookieIn(File file, byte[] authData) {
        List<XAuthority> authorities = XAuthority.getAuthorities(file);
        for (XAuthority authority : authorities) {
            if (!AUTH_COOKIE.equals(asAscii(authority.getProtocolName()))) {
                continue;
            }
            if (authority.getDisplayNumber() != server.options().displayNumber()
                    && authority.getDisplayNumber() != -1) {
                continue;
            }
            if (java.util.Arrays.equals(toBytes(authority.getProtocolData()), authData)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a setup failure. The reply is encoded here rather than with the generated class
     * because its length field counts the padded reason, which the generated writer does not
     * produce.
     */
    private void sendFailed(ClientConnection client, String reason) throws IOException {
        byte[] text = reason.getBytes(StandardCharsets.ISO_8859_1);
        int padded = (text.length + 3) & ~3;
        byte[] bytes = new byte[8 + padded];
        bytes[0] = 0;                                   // 0 means "failed"
        bytes[1] = (byte) text.length;
        putCard16(client, bytes, 2, PROTOCOL_MAJOR);
        putCard16(client, bytes, 4, PROTOCOL_MINOR);
        putCard16(client, bytes, 6, padded / 4);        // reason length in 4 byte units
        System.arraycopy(text, 0, bytes, 8, text.length);
        client.output().writeRaw(bytes);
        client.flush();
        client.close();
    }

    private void sendSetup(ClientConnection client) throws IOException {
        Screen screen = server.screen();
        int vendorPadded = (VENDOR.length() + 3) & ~3;

        var formats = screen.pixmapFormats();
        var root = screen.toProtocolScreen(server.rootWindow().id(), server.rootColormap().id());

        // The length field counts everything after the 8 byte prefix (status, unused, and the two
        // version words): the rest of the 40 byte header plus all the variable data. Verified
        // against Xvfb, whose length times four plus eight is exactly its reply size.
        int headerRemainder = 32;
        int additionalBytes = headerRemainder + vendorPadded
                + formats.stream().mapToInt(f -> f.getSize()).sum()
                + root.getSize();
        short length = (short) (additionalBytes / 4);

        Setup setup = Setup.builder()
                .status((byte) 1)
                .protocolMajorVersion(PROTOCOL_MAJOR)
                .protocolMinorVersion(PROTOCOL_MINOR)
                .length(length)
                .releaseNumber(RELEASE_NUMBER)
                .resourceIdBase(client.resourceIdBase())
                .resourceIdMask(client.resourceIdMask())
                .motionBufferSize(0)
                .maximumRequestLength((short) client.maximumRequestUnits())
                .imageByteOrder(client.isBigEndian() ? IMAGE_BYTE_ORDER_MSB : IMAGE_BYTE_ORDER_LSB)
                .bitmapFormatBitOrder(client.isBigEndian() ? IMAGE_BYTE_ORDER_MSB
                        : BITMAP_BIT_ORDER_LSB)
                .bitmapFormatScanlineUnit(BITMAP_SCANLINE_UNIT)
                .bitmapFormatScanlinePad(BITMAP_SCANLINE_PAD)
                .minKeycode(MIN_KEYCODE)
                .maxKeycode(MAX_KEYCODE)
                .vendor(ByteLists.immutable.of(VENDOR.getBytes(StandardCharsets.ISO_8859_1)))
                .pixmapFormats(Lists.immutable.withAll(formats))
                .roots(Lists.immutable.of(root))
                .build();

        // The setup reply's own length field is at bytes 4..8, so it must not be rewritten the
        // way ordinary replies are.
        client.output().writeStruct(setup);
        client.flush();
        log.debug("setup sent to {}", client.peer());
    }

    private void putCard16(ClientConnection client, byte[] bytes, int offset, int value) {
        if (client.isBigEndian()) {
            bytes[offset] = (byte) (value >> 8);
            bytes[offset + 1] = (byte) value;
        } else {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >> 8);
        }
    }

    private static String asAscii(ByteList bytes) {
        return new String(toBytes(bytes), StandardCharsets.ISO_8859_1);
    }

    private static byte[] toBytes(ByteList bytes) {
        return bytes == null ? new byte[0] : bytes.toArray();
    }

    /** The display name clients should use, e.g. {@code :1}. */
    public String displayName() {
        return ":" + server.options().displayNumber();
    }
}
