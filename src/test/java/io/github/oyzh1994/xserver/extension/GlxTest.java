package io.github.oyzh1994.xserver.extension;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GLX: the handshake a software OpenGL client needs, pinned at the wire.
 *
 * <p>This is what makes B6 stay done. The live check - {@code glxinfo} and {@code glxgears} on the VM
 * - is the real proof, but it needs a client machine and a GL library, and the two bugs this
 * extension had first were both about *bytes*: the request decoder read the body three bytes early
 * (invisible in a request whose body is ignored, wrong in every one where it is not) and the string
 * replies were one byte short because the length does not count the NUL. Both would come back the
 * same way, so both are pinned here.
 *
 * <p>What a test can check that a screenshot cannot: the config list's **40 ints per visual**, which
 * is a layout a client reads into a fixed struct - eighteen fields and then eleven
 * {@code (token, value)} pairs, the numbers measured against Xvfb's own answer.
 */
class GlxTest {

    /** A display number nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 67;

    // GLX's opcodes, as in the extension (Mesa's numbering).
    private static final int CREATE_CONTEXT = 3;
    private static final int IS_DIRECT = 6;
    private static final int QUERY_VERSION = 7;
    private static final int GET_VISUAL_CONFIGS = 14;
    private static final int QUERY_SERVER_STRING = 19;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--geometry", "600x400", "--backend", "headless", "--no-unix"}));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void itAnswersTheVersionAndTheVendorStrings() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("GLX");
            assertNotEquals(0, opcode, "a client that asks has to find it");

            byte[] version = request(client, opcode, QUERY_VERSION, 1, 4);
            assertEquals(1, RawXClient.readInt(version, 8), "major");
            assertEquals(4, RawXClient.readInt(version, 12), "minor: what this server implements");

            // **The length counts the NUL.** A client reads exactly that many bytes into a string it
            // has already allocated, and one short is a string with whatever came next appended to it
            // - measured as `glxinfo` printing `1.4` followed by two characters of memory.
            byte[] vendor = request(client, opcode, QUERY_SERVER_STRING, 0, 1);
            assertEquals("xserver".length() + 1, RawXClient.readInt(vendor, 12),
                    "the string's length includes its terminator");
            assertEquals("xserver", new String(vendor, 32, "xserver".length(),
                    java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void theVisualConfigsAreTheFortyIntLayout() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("GLX");
            byte[] reply = request(client, opcode, GET_VISUAL_CONFIGS, 0);
            int visuals = RawXClient.readInt(reply, 8);
            int props = RawXClient.readInt(reply, 12);
            assertTrue(visuals > 0, "a GL client needs at least one config to choose");
            assertEquals(40, props, "eighteen fields and eleven (token, value) pairs, as measured");

            // The first config, field by field: a visual this server also offers, RGBA, eight bits a
            // channel, double buffered, with a depth and a stencil buffer - which is what a client
            // asks for when it asks for anything.
            assertEquals(visuals, (reply.length - 32) / (props * 4), "one entry per visual");
            int visualId = RawXClient.readInt(reply, 32);
            assertNotEquals(0, visualId, "a config names a visual");
            assertEquals(4, RawXClient.readInt(reply, 32 + 4), "class: TrueColor");
            assertEquals(1, RawXClient.readInt(reply, 32 + 8), "rgba");
            assertEquals(8, RawXClient.readInt(reply, 32 + 12), "red size");
            assertEquals(1, RawXClient.readInt(reply, 32 + 44), "double buffered");
            assertEquals(24, RawXClient.readInt(reply, 32 + 56), "depth size");
            assertEquals(8, RawXClient.readInt(reply, 32 + 60), "stencil size");
        }
    }

    @Test
    void aDirectContextIsGrantedAndAnIndirectOneIsRefused() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("GLX");
            byte[] configs = request(client, opcode, GET_VISUAL_CONFIGS, 0);
            int visual = RawXClient.readInt(configs, 32);

            int direct = client.allocateId();
            createContext(client, opcode, direct, visual, 1);
            client.flush();
            // A request with no reply is only known to have run by the round trip after it, and an
            // error arrives first - so the reply that comes back is the answer.
            byte[] isDirect = request(client, opcode, IS_DIRECT, direct);
            assertEquals(1, isDirect[8] & 0xFF,
                    "a direct context is what makes the client render itself and blit - the whole of"
                            + " why this server needs no GL implementation");

            int indirect = client.allocateId();
            createContext(client, opcode, indirect, visual, 0);
            client.flush();
            // Read as an *event* rather than a reply: `readReply` throws on an error, and this error
            // is the answer the test came for. A refused request is answered with one message.
            byte[] answer = client.readEvent();
            assertNotNull(answer, "an indirect context has to be answered, one way or the other");
            assertEquals(0, answer[0] & 0x7F,
                    "an indirect context has to be refused: it would mean GL commands over the wire,"
                            + " and there is no renderer here to execute them");
            assertEquals(17, answer[1] & 0xFF, "refused as BadImplementation, which a client can act on");
        }
    }

    // ------------------------------------------------------------------

    private static void createContext(RawXClient client, int opcode, int context, int visual,
                                      int direct) throws IOException {
        byte[] request = new byte[24];
        request[0] = (byte) opcode;
        request[1] = CREATE_CONTEXT;
        RawXClient.writeShortAt(request, 2, 6);
        RawXClient.writeIntAt(request, 4, context);
        RawXClient.writeIntAt(request, 8, visual);
        RawXClient.writeIntAt(request, 12, 0);          // screen
        RawXClient.writeIntAt(request, 16, 0);          // share with nothing
        RawXClient.writeIntAt(request, 20, direct);
        client.sendRequest(request);
    }

    /** Sends a GLX request whose body is whole words, and reads the reply. */
    private static byte[] request(RawXClient client, int opcode, int minor, int... body)
            throws IOException {
        byte[] request = new byte[4 + body.length * 4];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 1 + body.length);
        for (int i = 0; i < body.length; i++) {
            RawXClient.writeIntAt(request, 4 + i * 4, body[i]);
        }
        client.sendRequest(request);
        client.flush();
        return client.readReply();
    }
}
