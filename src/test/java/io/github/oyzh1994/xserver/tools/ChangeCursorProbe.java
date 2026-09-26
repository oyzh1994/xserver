package io.github.oyzh1994.xserver.tools;

import io.github.oyzh1994.xserver.test.RawXClient;

/**
 * What {@code XFixesChangeCursor} does to the two cursors it names - A8's last refusal.
 *
 * <p>The request takes two cursors, {@code source} and {@code destination}, and what it means for
 * one of them to change is measured rather than read off the specification: the two questions are
 * **which** of the two ends up with the other's image, and whether a window already showing it sees
 * the change (a cursor is a shared resource in X, so a rewrite should reach every window at once).
 *
 * <p>The probe makes two cursors with different glyphs from the font {@code fixed}, puts them on two
 * windows, and reads each window's cursor back through XFIXES' {@code GetCursorImage} - before and
 * after the change. It also watches the cursor *notifications*, because a rewrite is a change to a
 * cursor a client may be watching, and the event's serial says which cursor it thinks changed.
 *
 * <p>Usage: {@code ChangeCursorProbe <host> <port>} - step 3 asks for the {@code fixed} font, so a
 * server that does not have it will say so rather than draw nothing.
 */
public final class ChangeCursorProbe {

    private static final int GET_CURSOR_IMAGE = 4;
    private static final int CHANGE_CURSOR = 26;
    private static final int SELECT_CURSOR_INPUT = 3;

    public static void main(String[] args) throws Exception {
        try (RawXClient client = RawXClient.connect(args[0], Integer.parseInt(args[1]))) {
            int[] xfixes = client.queryExtensionInfo("XFIXES");
            if (xfixes[0] == 0) {
                System.out.println("no XFIXES");
                return;
            }
            int opcode = xfixes[1];
            int firstEvent = xfixes[2];
            int[] version = client.queryXfixesVersion(opcode, 4, 0);
            System.out.println("XFIXES version " + version[0] + "." + version[1]);

            int watcher = client.allocateId();
            client.createWindow(watcher, client.root(), 0, 0, 30, 30);
            client.mapWindow(watcher);
            client.selectCursorInput(opcode, watcher, 1);
            client.flush();

            int font = client.allocateId();
            client.openFont(font, "fixed");
            int first = client.allocateId();
            int second = client.allocateId();
            // 'A' and 'B' rather than two cursor-font shapes: their glyphs are visibly different, and
            // the reading is the shape rather than a size both would share.
            client.createGlyphCursor(first, font, font, 'A', 'A', 0xFFFFFF, 0x000000);
            client.createGlyphCursor(second, font, font, 'B', 'B', 0xFFFFFF, 0x000000);

            int windowOne = client.allocateId();
            int windowTwo = client.allocateId();
            client.createWindow(windowOne, client.root(), 100, 100, 60, 60, 0x303030);
            client.createWindow(windowTwo, client.root(), 300, 100, 60, 60, 0x303030);
            client.setWindowCursor(windowOne, first);
            client.setWindowCursor(windowTwo, second);
            client.mapWindow(windowOne);
            client.mapWindow(windowTwo);
            client.flush();

            System.out.println("-- before");
            show(client, opcode, windowOne, "window with source");
            // The pointer is left over the *destination* window, so the change is to the cursor being
            // displayed - which is the case that says whether subscribers hear about a rewrite.
            show(client, opcode, windowTwo, "window with destination");

            // XFixesChangeCursor: source, then destination.
            byte[] request = new byte[12];
            request[0] = (byte) opcode;
            request[1] = (byte) CHANGE_CURSOR;
            RawXClient.writeShortAt(request, 2, 3);
            RawXClient.writeIntAt(request, 4, first);
            RawXClient.writeIntAt(request, 8, second);
            client.sendRequest(request);
            client.flush();
            // A round trip after it, so the events it raises have arrived before they are counted.
            client.queryTree(watcher);
            System.out.println("-- after ChangeCursor(source, destination)");
            show(client, opcode, windowOne, "window with source");
            show(client, opcode, windowTwo, "window with destination");

            // And the notifications, which say whether a rewrite is a cursor change a client hears
            // about, and which serial it carries.
            int events = 0;
            client.readTimeoutMillis(600);
            for (byte[] event = client.readEvent(); event != null; event = client.readEvent()) {
                if ((event[0] & 0x7F) == firstEvent) {
                    events++;
                    System.out.printf("  CursorNotify window=0x%x serial=%d%n",
                            RawXClient.readInt(event, 4), RawXClient.readInt(event, 8));
                }
            }
            client.readTimeoutMillis(5000);
            System.out.println("  cursor notifications during the change: " + events);
        }
    }

    /** XFixesGetCursorImage for the cursor the given window's pointer shows. */
    private static void show(RawXClient client, int opcode, int window, String what)
            throws Exception {
        client.warpPointer(window, 30, 30);
        client.queryTree(window);                       // the pointer has moved
        byte[] request = new byte[4];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_CURSOR_IMAGE;
        RawXClient.writeShortAt(request, 2, 1);
        client.sendRequest(request);
        byte[] reply = client.readReply();
        int width = RawXClient.readShort(reply, 12);
        int height = RawXClient.readShort(reply, 14);
        StringBuilder shape = new StringBuilder();
        for (int i = 0; i < Math.min(width * height, 78); i++) {
            int argb = RawXClient.readInt(reply, 32 + i * 4);
            shape.append((argb >>> 24) == 0 ? '.' : '#');
        }
        System.out.printf("  %-24s %dx%d serial=%d %s%n", what, width, height,
                RawXClient.readInt(reply, 20), shape);
    }

    private ChangeCursorProbe() {
    }
}
