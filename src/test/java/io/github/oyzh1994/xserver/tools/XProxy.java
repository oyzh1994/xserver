package io.github.oyzh1994.xserver.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A byte-level X11 proxy that logs the protocol both ways, so a real client's request and event
 * streams can be read off a real server that cannot log them itself.
 *
 * <p>Usage: {@code XProxy listenPort targetHost targetPort logFile [rawFile]}. Point the client at
 * {@code DISPLAY=<host>:<listenPort - 6000>} and read the log. This is the only way to answer "does
 * the client send different requests to Xvfb than this server" - the question behind several
 * backlog items (A5, A4, B2) - because it puts the *reference* server's own traffic in a file.
 *
 * <p>Only enough of the framing is understood to walk the streams: a request's length is in bytes
 * 2..3 (in four byte units), a reply's extra data is in bytes 4..7, and everything else is a fixed
 * thirty two byte event or error. The one-line-per-message log carries no bodies; when the *bytes*
 * are what is being hunted, pass a fifth argument and every message, whole, is written there as
 * {@code <direction> <message hex>} - which is how B1's `GetKbdByName` reply was read off a
 * reference server rather than derived from a header (the same reason `XkbProbe` prints bytes).
 *
 * <p><b>Reconstructed 2026-09-24</b> after the original was lost with `/tmp`: the framing, the setup
 * exchange, the BigRequests handling and the request descriptions are the original's (they were read
 * back from a session log), but its opcode/event name table was longer - here anything not in the
 * short table prints as {@code op-<n>}. The behaviour that matters (walk the byte stream, log one
 * line per message) is unchanged.
 */
public final class XProxy {

    static OutputStream log;
    static OutputStream raw;
    static final AtomicLong started = new AtomicLong();

    public static void main(String[] args) throws Exception {
        int listenPort = Integer.parseInt(args[0]);
        String targetHost = args[1];
        int targetPort = Integer.parseInt(args[2]);
        Path logPath = Path.of(args[3]);
        log = Files.newOutputStream(logPath);
        if (args.length > 4) {
            raw = Files.newOutputStream(Path.of(args[4]));
        }
        started.set(System.currentTimeMillis());
        try (ServerSocket server = new ServerSocket(listenPort)) {
            System.out.println("proxy on " + listenPort + " -> " + targetHost + ":" + targetPort);
            while (true) {
                Socket client = server.accept();
                Socket target = new Socket(targetHost, targetPort);
                System.out.println("client connected");
                Thread toServer = new Thread(() -> pump(client, target, true));
                Thread toClient = new Thread(() -> pump(target, client, false));
                toServer.start();
                toClient.start();
            }
        }
    }

    static void pump(Socket from, Socket to, boolean clientToServer) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            boolean first = true;
            while (true) {
                if (first) {
                    // The setup exchange is framed differently from everything after it: the
                    // request is twelve bytes with no length field, and the reply counts its extra
                    // data in a sixteen bit field.
                    first = false;
                    byte[] setup = readLogged(in, clientToServer ? 12 : 8, "setup");
                    if (setup == null) {
                        return;
                    }
                    int extra = 0;
                    if (clientToServer) {
                        logSetup(setup);
                    } else {
                        int length = (setup[6] & 0xFF) | ((setup[7] & 0xFF) << 8);
                        extra = Math.max(0, length * 4);
                    }
                    byte[] body = extra == 0 ? new byte[0] : readLogged(in, extra, "setup body");
                    if (body == null) {
                        return;
                    }
                    byte[] whole = new byte[setup.length + extra];
                    System.arraycopy(setup, 0, whole, 0, setup.length);
                    System.arraycopy(body, 0, whole, setup.length, extra);
                    dump(whole, clientToServer);
                    out.write(whole);
                    out.flush();
                    continue;
                }
                // **A request is not always eight bytes.** The head is read as four - the opcode, the
                // data byte and the length - and the rest follows from that length, because a request
                // whose length field says one unit is exactly those four bytes. That is not a corner
                // case: it is `BIG-REQUESTS`' `Enable`, which libxcb sends on *every* connection as
                // the third request, so a proxy that assumes eight desynchronises one message in and
                // hangs the client with no error on either side - which is what `XProxy` did to every
                // GTK client, and what the kit's notes recorded as "EasyShell hangs through it, the
                // reason was never found" (2026-09-27).
                byte[] head = readFully(in, 4);
                if (head == null) {
                    System.out.println((clientToServer ? "C>S" : "S>C") + " end of stream");
                    return;
                }
                int units = (head[2] & 0xFF) | ((head[3] & 0xFF) << 8);
                int whole4 = units * 4;
                byte[] header;
                // The four byte case is a *request* only: everything a server sends is a reply, an
                // error or an event, and all three have the full header.
                if (clientToServer && whole4 == 4) {
                    header = head;
                } else {
                    byte[] rest = readFully(in, 8 - 4);
                    if (rest == null) {
                        return;
                    }
                    header = new byte[8];
                    System.arraycopy(head, 0, header, 0, 4);
                    System.arraycopy(rest, 0, header, 4, 4);
                }
                System.out.println((clientToServer ? "C>S" : "S>C") + " op=" + (header[0] & 0xFF)
                        + " b2b3=" + ((head[2] & 0xFF) | ((head[3] & 0xFF) << 8))
                        + (header.length > 4 ? " b4b7=" + card32(header, 4) : ""));
                int extra = 0;
                if (clientToServer) {
                    if (units == 0) {
                        // BigRequests' extended form, and **a zero length field is what triggers it** -
                        // not the opcode. The opcode alone is the wrong test: 139 is also the ordinary
                        // `Enable` request, whose length field is three, and reading its body as an
                        // extended length asks for gigabytes and hangs the pump.
                        byte[] extended = readFully(in, 4);
                        if (extended == null) {
                            return;
                        }
                        int bigUnits = card32(extended, 0);
                        int body = Math.max(0, bigUnits * 4 - 8);
                        byte[] whole = new byte[8 + body];
                        System.arraycopy(header, 0, whole, 0, 8);
                        byte[] rest = body == 0 ? new byte[0] : readFully(in, body);
                        if (rest == null) {
                            return;
                        }
                        System.arraycopy(rest, 0, whole, 8, body);
                        dump(whole, clientToServer);
                        out.write(whole);
                        out.flush();
                        continue;
                    }
                    extra = Math.max(0, whole4 - 8);
                } else if ((header[0] & 0x7F) == 1) {
                    // A reply is thirty two bytes plus whatever its length field counts.
                    int length = (header[4] & 0xFF) | ((header[5] & 0xFF) << 8)
                            | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 24);
                    extra = 24 + Math.max(0, length * 4);
                } else {
                    extra = 24;
                }
                byte[] body = extra == 0 ? new byte[0] : readFully(in, extra);
                if (body == null) {
                    return;
                }
                byte[] whole = new byte[header.length + extra];
                System.arraycopy(header, 0, whole, 0, header.length);
                System.arraycopy(body, 0, whole, header.length, extra);
                describe(whole, clientToServer);
                dump(whole, clientToServer);
                out.write(whole);
                out.flush();
            }
        } catch (Exception e) {
            System.out.println((clientToServer ? "C>S" : "S>C") + " pump ended: " + e);
        }
    }

    /** Every message, whole, when the bytes are what is being hunted - a reply's shape, say. */
    static synchronized void dump(byte[] message, boolean clientToServer) {
        if (raw == null) {
            return;
        }
        StringBuilder hex = new StringBuilder(message.length * 3);
        for (byte b : message) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        try {
            raw.write((clientToServer ? "C>S " : "S>C ").getBytes());
            raw.write(hex.toString().getBytes());
            raw.write('\n');
            raw.flush();
        } catch (Exception e) {
            // best effort
        }
    }

    static synchronized void logSetup(byte[] message) {
        try {
            log.write(String.format("%6d C>S setup byteOrder=%c protocol=%d.%d%n",
                    System.currentTimeMillis() - started.get(), (char) message[0],
                    message[2] & 0xFF, message[4] & 0xFF).getBytes());
            log.flush();
        } catch (Exception e) {
            // best effort
        }
    }

    static synchronized void describe(byte[] message, boolean clientToServer) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("%6d %s ", System.currentTimeMillis() - started.get(),
                clientToServer ? "C>S" : "S>C"));
        int type = message[0] & 0xFF;
        if (clientToServer) {
            line.append(String.format("req %-3d %-24s", type, name(type)));
            if (message.length >= 12) {
                line.append(" win=0x").append(Integer.toHexString(card32(message, 4)));
            }
            if (message.length >= 28 && type == 26) {                   // GrabPointer
                line.append(" ownerEvents=").append(message[9] & 0xFF)
                        .append(" mask=0x").append(Integer.toHexString(
                                (message[10] & 0xFF) | ((message[11] & 0xFF) << 8)))
                        .append(" pointerMode=").append(message[12] & 0xFF);
            }
            if (type == 12 && message.length >= 20) {                   // ConfigureWindow
                int mask = (message[8] & 0xFF) | ((message[9] & 0xFF) << 8);
                line.append(" mask=0x").append(Integer.toHexString(mask));
                int at = 12;
                if ((mask & 1) != 0) {
                    line.append(" x=").append(card16(message, at));
                }
                if ((mask & 2) != 0) {
                    line.append(" y=").append(card16(message, at));
                }
                if ((mask & 4) != 0) {
                    line.append(" w=").append(card16(message, at));
                }
                if ((mask & 8) != 0) {
                    line.append(" h=").append(card16(message, at));
                }
                if ((mask & 0x10) != 0) {
                    line.append(" border=").append(card16(message, at));
                }
            }
            if (type == 1 && message.length >= 40) {                    // CreateWindow
                line.append(" parent=0x").append(Integer.toHexString(card32(message, 8)))
                        .append(" ").append(card16(message, 16)).append("x")
                        .append(card16(message, 18));
            }
        } else if (type == 1) {
            line.append(String.format("reply      %-20s", ""));
        } else if (type == 0) {
            line.append(String.format("error %-18s", "code=" + (message[1] & 0xFF)
                    + " request=" + (message[10] & 0xFF)));
        } else {
            line.append(String.format("evt %-3d %-20s", type, name(type)));
            if (message.length >= 32) {
                line.append(" win=0x").append(Integer.toHexString(card32(message, 12)));
                if (type == 7 || type == 8) {                           // EnterNotify/LeaveNotify
                    line.append(" mode=").append(message[30] & 0xFF)
                            .append(" detail=").append(message[1] & 0xFF)
                            .append(" at (").append((short) card16(message, 24)).append(",")
                            .append((short) card16(message, 26)).append(")");
                }
                if (type == 4 || type == 5) {                           // ButtonPress/Release
                    line.append(" button=").append(message[1] & 0xFF)
                            .append(" at (").append((short) card16(message, 24)).append(",")
                            .append((short) card16(message, 26)).append(")");
                }
            }
        }
        line.append('\n');
        try {
            log.write(line.toString().getBytes());
            log.flush();
        } catch (Exception e) {
            // Logging is best effort; the protocol stream must not depend on it.
        }
    }

    /** The names worth having in the log; anything else prints as {@code op-<n>}. */
    static String name(int opcode) {
        return switch (opcode) {
            case 1 -> "CreateWindow";
            case 2 -> "ChangeWindowAttributes";
            case 3 -> "GetWindowAttributes";
            case 4 -> "DestroyWindow";
            case 8 -> "MapWindow";
            case 10 -> "UnmapWindow";
            case 11 -> "UnmapSubwindows";
            case 12 -> "ConfigureWindow";
            case 13 -> "CirculateWindow";
            case 14 -> "GetGeometry";
            case 15 -> "QueryTree";
            case 16 -> "InternAtom";
            case 18 -> "ChangeProperty";
            case 19 -> "DeleteProperty";
            case 20 -> "GetProperty";
            case 22 -> "SetSelectionOwner";
            case 23 -> "GetSelectionOwner";
            case 24 -> "ConvertSelection";
            case 25 -> "SendEvent";
            case 26 -> "GrabPointer";
            case 27 -> "UngrabPointer";
            case 28 -> "GrabButton";
            case 33 -> "GrabKeyboard";
            case 34 -> "UngrabKeyboard";
            case 37 -> "GrabServer";
            case 38 -> "UngrabServer";
            case 39 -> "QueryPointer";
            case 40 -> "GetMotionEvents";
            case 41 -> "TranslateCoordinates";
            case 42 -> "SetInputFocus";
            case 43 -> "GetInputFocus";
            case 53 -> "CreatePixmap";
            case 54 -> "FreePixmap";
            case 55 -> "CreateGC";
            case 56 -> "ChangeGC";
            case 60 -> "FreeGC";
            case 62 -> "CopyArea";
            case 63 -> "CopyPlane";
            case 64 -> "PolyPoint";
            case 65 -> "PolyLine";
            case 66 -> "PolySegment";
            case 67 -> "PolyRectangle";
            case 68 -> "PolyArc";
            case 69 -> "FillPoly";
            case 70 -> "PolyFillRectangle";
            case 71 -> "PolyFillArc";
            case 72 -> "PutImage";
            case 73 -> "GetImage";
            case 74 -> "PolyText8";
            case 75 -> "PolyText16";
            case 76 -> "ImageText8";
            case 77 -> "ImageText16";
            case 88 -> "CreateCursor";
            case 93 -> "CreateColormap";
            case 98 -> "QueryExtension";
            case 99 -> "ListExtensions";
            case 101 -> "GetKeyboardMapping";
            case 116 -> "NoOperation";
            case 128 -> "BIG-REQUESTS Enable";
            case 139 -> "RENDER";
            default -> "op-" + opcode;
        };
    }

    static int card16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    static int card32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    static byte[] readFully(InputStream in, int count) throws Exception {
        byte[] data = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(data, read, count - read);
            if (n < 0) {
                return null;
            }
            read += n;
        }
        return data;
    }

    /** Reads and hex-dumps, for the setup exchange a reader may want to see raw. */
    static byte[] readLogged(InputStream in, int count, String tag) throws Exception {
        byte[] data = readFully(in, count);
        if (data == null) {
            return null;
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : data) {
            hex.append(String.format("%02x", b));
        }
        synchronized (XProxy.class) {
            log.write(String.format("%6d %s %d bytes %s%n", System.currentTimeMillis() - started.get(),
                    tag, count, hex).getBytes());
            log.flush();
        }
        return data;
    }
}
