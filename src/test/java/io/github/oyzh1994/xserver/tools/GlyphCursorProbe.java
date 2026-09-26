package io.github.oyzh1994.xserver.tools;

import io.github.oyzh1994.xserver.test.RawXClient;

/**
 * A cursor built from a font's own glyphs, printed pixel by pixel - A3's other half.
 *
 * <p>{@code CreateGlyphCursor} is how a client asks for a cursor drawn out of a character. The
 * cursor font's shapes are the common case (every toolkit's arrow, and A3 is about those); a client
 * with a font of its own is the other, and this server used to approximate it with **a filled cell**
 * - "this cell is the shape" - because it had no glyph outlines. The font tables have the real
 * bitmaps now, so this probe is what says whether the two servers compose the same picture: the
 * size, the hotspot, and every pixel's colour.
 *
 * <p>It reads the cursor back the way a client can: XFIXES' {@code GetCursorImage} answers with the
 * image of the cursor **the pointer is showing**, so the probe defines the cursor on a window, warps
 * the pointer into it, and asks. That is the only route that sees the composed result rather than
 * the arguments.
 *
 * <p>Usage: {@code GlyphCursorProbe <host> <port> [font] [chars...]} - the font defaults to
 * {@code fixed} and the characters to {@code A}, {@code g} and {@code !}, which between them have
 * holes, a descender and a narrow glyph: a filled cell shows up in all three.
 */
public final class GlyphCursorProbe {

    /** The XFIXES request that answers with the image of the cursor the pointer shows. */
    private static final int GET_CURSOR_IMAGE = 4;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: GlyphCursorProbe <host> <port> [font] [chars...]");
            return;
        }
        String fontName = args.length > 2 ? args[2] : "fixed";
        String chars = args.length > 3 ? args[3] : "Ag!";
        // The mask character per source character, or empty for "the one after it" - the convention
        // the cursor font follows. Which glyph masks which changes the picture completely, so it is
        // an argument rather than a constant.
        String masks = args.length > 4 ? args[4] : "";
        try (RawXClient client = RawXClient.connect(args[0], Integer.parseInt(args[1]))) {
            int xfixes = client.queryExtension("XFIXES");
            if (xfixes == 0) {
                System.out.println("no XFIXES: nothing can read a cursor back");
                return;
            }
            // The version first: a reference server refuses every XFIXES request from a client that
            // never asked for one, which reads as BadRequest on the *first* cursor request and looks
            // like a malformed request rather than a missing handshake (measured 2026-09-26).
            client.queryXfixesVersion(xfixes, 4, 0);
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 60, 60, 0x202020);
            client.mapWindow(window);
            int font = client.allocateId();
            client.openFont(font, fontName);
            System.out.println("font " + fontName + ", window 0x" + Integer.toHexString(window));

            for (int i = 0; i < chars.length(); i++) {
                int character = chars.charAt(i);
                int mask = masks.length() > i ? masks.charAt(i) : character + 1;
                int cursor = client.allocateId();
                client.createGlyphCursor(cursor, font, font, character, mask, foreground(), background());
                client.setWindowCursor(window, cursor);
                client.warpPointer(window, 30, 30);
                client.queryTree(window);                   // a round trip: the pointer is there
                print(client, xfixes, (char) character, (char) mask);
            }
        }
    }

    /** The two colours, overridable so a measurement can tell which argument paints what. */
    private static int foreground() {
        return Integer.decode(System.getenv().getOrDefault("GLYPH_FG", "0xFFFFFF"));
    }

    private static int background() {
        return Integer.decode(System.getenv().getOrDefault("GLYPH_BG", "0x000000"));
    }

    /** XFixesGetCursorImage: the size, the hotspot and the image, as text. */
    private static void print(RawXClient client, int xfixes, char character, char mask)
            throws Exception {
        // One unit: XFixesGetCursorImage takes no arguments at all, and a request that claims two
        // is refused by a reference server with BadRequest.
        byte[] request = new byte[4];
        request[0] = (byte) xfixes;
        request[1] = (byte) GET_CURSOR_IMAGE;
        RawXClient.writeShortAt(request, 2, 1);
        client.sendRequest(request);
        byte[] reply = client.readReply();
        if ((reply[0] & 0x7F) != 1) {
            System.out.printf("  %c/%c: no reply (type %d)%n", character, mask, reply[0] & 0xFF);
            return;
        }
        // The reply's own order: x, y, width, height, xhot, yhot, then the serial and the image.
        int width = RawXClient.readShort(reply, 12);
        int height = RawXClient.readShort(reply, 14);
        int hotX = RawXClient.readShort(reply, 16);
        int hotY = RawXClient.readShort(reply, 18);
        System.out.printf("  source %c, mask %c: %dx%d hotspot %d,%d%n",
                character, mask, width, height, hotX, hotY);
        for (int y = 0; y < height; y++) {
            StringBuilder row = new StringBuilder("      ");
            for (int x = 0; x < width; x++) {
                int argb = RawXClient.readInt(reply, 32 + (y * width + x) * 4);
                // Three states, which is what a two-colour cursor has: the foreground, the
                // background, and nothing at all where the mask does not reach.
                int alpha = argb >>> 24;
                row.append(alpha == 0 ? '.' : (argb & 0xFFFFFF) == 0 ? 'o' : '#');
            }
            System.out.println(row);
            if (System.getenv("GLYPH_RAW") != null) {
                for (int x = 0; x < width; x++) {
                    int argb = RawXClient.readInt(reply, 32 + (y * width + x) * 4);
                    if ((argb & 0x00FFFFFF) != 0 || (argb >>> 24) != 0) {
                        System.out.printf("      (%d,%d)=%08x%n", x, y, argb);
                    }
                }
            }
        }
    }

    private GlyphCursorProbe() {
    }
}
