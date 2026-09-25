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

/**
 * Xinerama, which answers "no, I am one screen" - and has to answer.
 *
 * <p>It is not here because a client uses it: this server has one screen and always will. It is here
 * because a client *asks*, and Xlib's own {@code XineramaIsActive} does not survive being told the
 * extension is absent - the question is what a toolkit asks at startup, so the answer has to exist.
 */
class XineramaTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 66;

    /** The extension's requests, in the order the specification numbers them. */
    private static final int QUERY_VERSION = 0;
    private static final int IS_ACTIVE = 4;
    private static final int QUERY_SCREENS = 5;

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
    void itIsOfferedAndReportshisVersion() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XINERAMA");
            assertNotEquals(0, opcode, "a client that asks has to be told the extension is there");

            byte[] reply = client.extensionRequest(opcode, QUERY_VERSION, 2);
            assertEquals(1, RawXClient.readShort(reply, 8), "the major version the client asked about");
        }
    }

    @Test
    void itSaysItIsNotSeveralScreens() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XINERAMA");
            byte[] reply = client.extensionRequest(opcode, IS_ACTIVE);
            assertEquals(0, RawXClient.readInt(reply, 8),
                    "one screen is not a Xinerama display, and saying so is the answer that matters");
        }
    }

    @Test
    void andItCanSayWhichScreenThatIs() throws IOException {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = client.queryExtension("XINERAMA");
            byte[] reply = client.extensionRequest(opcode, QUERY_SCREENS);
            assertEquals(1, RawXClient.readInt(reply, 8), "the list of screens has one screen in it");
            // ScreenInfo: x, y, width, height, each sixteen bit, from offset 32.
            assertEquals(0, RawXClient.readShort(reply, 32));
            assertEquals(0, RawXClient.readShort(reply, 34));
            assertEquals(600, RawXClient.readShort(reply, 36), "the screen the server has");
            assertEquals(400, RawXClient.readShort(reply, 38));
        }
    }
}
