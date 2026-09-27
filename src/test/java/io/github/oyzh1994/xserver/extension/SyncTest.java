package io.github.oyzh1994.xserver.extension;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNC: counters a client sets and reads back, and fences it triggers and waits on.
 *
 * <p>Two things here were measured against a reference rather than read out of the specification
 * ({@code tools/SyncProbe}): {@code Initialize} answers **the server's** version - 3.1 to a client
 * asking for 3.1 and to one asking for 3.0, where echoing the client's own version is the obvious
 * implementation and tells a 3.0 client that a 3.1 request is available to it - and a fence's
 * untriggered-to-triggered-to-reset walk behaves as the client sees it, which is the whole of what a
 * fence is for.
 */
class SyncTest {

    /** A display number the rest of the suite does not use. */
    private static final int DISPLAY = 85;

    private static final int INITIALIZE = 0;
    private static final int CREATE_COUNTER = 2;
    private static final int SET_COUNTER = 3;
    private static final int CHANGE_COUNTER = 4;
    private static final int QUERY_COUNTER = 5;
    private static final int CREATE_FENCE = 14;
    private static final int TRIGGER_FENCE = 15;
    private static final int RESET_FENCE = 16;
    private static final int QUERY_FENCE = 18;

    private static XServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--single-window", "--no-unix"}));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * The version is the server's, not the client's. A client that asks for 3.0 is told 3.1, and a
     * client that asks for 3.1 is told 3.1 - measured on a reference, and the reply is what tells a
     * client which requests it may use.
     */
    @Test
    void initializeAnswersTheServersVersion() throws Exception {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            assertEquals(3, initialize(client, 3, 0)[0], "major");
            assertEquals(1, initialize(client, 3, 0)[1], "a 3.0 client is told 3.1");
            assertEquals(1, initialize(client, 3, 1)[1], "and so is a 3.1 client");
        }
    }

    /**
     * A counter holds what it was set to, and {@code ChangeCounter} adds a delta to it - including
     * above 32 bits, which is the part the wire's two-word {@code INT64} gets wrong if the words are
     * taken in the other order (a value of 2^32 + 7 reads back as 7).
     */
    @Test
    void countersHoldTheirValueAcrossTheFullSixtyFourBits() throws Exception {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = opcode(client);
            int counter = client.allocateId();
            int64Request(client, opcode, CREATE_COUNTER, counter, 5L);
            assertEquals(5L, queryCounter(client, opcode, counter), "the initial value");

            int64Request(client, opcode, SET_COUNTER, counter, 42L);
            assertEquals(42L, queryCounter(client, opcode, counter), "and what it is set to");

            int64Request(client, opcode, CHANGE_COUNTER, counter, 8L);
            assertEquals(50L, queryCounter(client, opcode, counter), "and the delta added to it");

            long big = (1L << 32) + 7;
            int64Request(client, opcode, SET_COUNTER, counter, big);
            assertEquals(big, queryCounter(client, opcode, counter),
                    "a value above 32 bits, which the two words of an INT64 have to carry in order");
        }
    }

    /**
     * A fence's four states, as a client sees them. It is created untriggered, triggered, reset, and
     * read back after each - and the reads are the test, because a fence is exactly the bit a client
     * cannot observe any other way.
     */
    @Test
    void aFenceTriggersAndResets() throws Exception {
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int opcode = opcode(client);
            int fence = client.allocateId();

            byte[] create = new byte[16];
            create[0] = (byte) opcode;
            create[1] = (byte) CREATE_FENCE;
            RawXClient.writeShortAt(create, 2, 4);
            RawXClient.writeIntAt(create, 4, client.root());    // drawable first, fence second
            RawXClient.writeIntAt(create, 8, fence);
            create[12] = 0;
            client.sendRequest(create);
            assertTrue(!queryFence(client, opcode, fence), "created untriggered");

            trigger(client, opcode, TRIGGER_FENCE, fence);
            assertTrue(queryFence(client, opcode, fence), "triggered");

            trigger(client, opcode, RESET_FENCE, fence);
            assertTrue(!queryFence(client, opcode, fence), "and reset again");
        }
    }

    // ------------------------------------------------------------------

    private static int[] initialize(RawXClient client, int major, int minor) throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode(client);
        request[1] = (byte) INITIALIZE;
        RawXClient.writeShortAt(request, 2, 2);
        request[4] = (byte) major;
        request[5] = (byte) minor;
        client.sendRequest(request);
        byte[] reply = client.readReply();
        return new int[]{reply[8] & 0xFF, reply[9] & 0xFF};
    }

    /** CreateCounter, SetCounter and ChangeCounter differ only in which request carries the value. */
    private static void int64Request(RawXClient client, int opcode, int minor, int counter, long value)
            throws IOException {
        byte[] request = new byte[16];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 4);
        RawXClient.writeIntAt(request, 4, counter);
        RawXClient.writeIntAt(request, 8, (int) value);
        RawXClient.writeIntAt(request, 12, (int) (value >> 32));
        client.sendRequest(request);
    }

    private static long queryCounter(RawXClient client, int opcode, int counter) throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) QUERY_COUNTER;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, counter);
        client.sendRequest(request);
        byte[] reply = client.readReply();
        return (RawXClient.readInt(reply, 8) & 0xFFFFFFFFL)
                | ((long) RawXClient.readInt(reply, 12) << 32);
    }

    private static void trigger(RawXClient client, int opcode, int minor, int fence)
            throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) minor;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, fence);
        client.sendRequest(request);
    }

    private static boolean queryFence(RawXClient client, int opcode, int fence) throws IOException {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) QUERY_FENCE;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, fence);
        client.sendRequest(request);
        return client.readReply()[8] != 0;
    }

    private static int opcode(RawXClient client) throws IOException {
        int[] info = client.queryExtensionInfo("SYNC");
        assertTrue(info[0] != 0, "the extension has to be advertised");
        return info[1];
    }
}
