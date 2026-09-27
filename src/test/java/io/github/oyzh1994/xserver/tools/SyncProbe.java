package io.github.oyzh1994.xserver.tools;

import io.github.oyzh1994.xserver.test.RawXClient;

/**
 * The SYNC extension as a client uses it - the harness for the second of B5's three, and the one a
 * toolkit reaches for when it wants to know that a frame has been *seen* rather than merely sent.
 *
 * <p>What it does, in the order a client does it: asks for the version, lists the system counters,
 * makes a counter of its own and reads it back after setting and changing it, reads and sets a
 * client priority, then makes a fence and walks it through its four states - created untriggered,
 * triggered, reset, destroyed - reading {@code QueryFence} after each. Every field is printed whether
 * or not the extension answered, because "the extension is missing" and "the fence never triggers"
 * are different findings.
 *
 * <p>Usage: {@code SyncProbe &lt;host&gt; &lt;port&gt;}.
 */
public final class SyncProbe {

    private static final int INITIALIZE = 0;
    private static final int LIST_SYSTEM_COUNTERS = 1;
    private static final int CREATE_COUNTER = 2;
    private static final int SET_COUNTER = 3;
    private static final int CHANGE_COUNTER = 4;
    private static final int QUERY_COUNTER = 5;
    private static final int DESTROY_COUNTER = 6;
    private static final int SET_PRIORITY = 12;
    private static final int GET_PRIORITY = 13;
    private static final int CREATE_FENCE = 14;
    private static final int TRIGGER_FENCE = 15;
    private static final int RESET_FENCE = 16;
    private static final int DESTROY_FENCE = 17;
    private static final int QUERY_FENCE = 18;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: SyncProbe <host> <port>");
            return;
        }
        try (RawXClient client = RawXClient.connect(args[0], Integer.parseInt(args[1]))) {
            int[] info = client.queryExtensionInfo("SYNC");
            System.out.println("SYNC present=" + info[0] + " major=" + info[1]
                    + " firstEvent=" + info[2] + " firstError=" + info[3]);
            if (info[0] == 0) {
                return;
            }
            int opcode = info[1];

            for (int[] asked : new int[][]{{3, 1}, {3, 0}}) {
                byte[] init = new byte[8];
                init[0] = (byte) opcode;
                init[1] = (byte) INITIALIZE;
                RawXClient.writeShortAt(init, 2, 2);
                init[4] = (byte) asked[0];
                init[5] = (byte) asked[1];
                client.sendRequest(init);
                byte[] initReply = client.readReply();
                System.out.println("  Initialize(" + asked[0] + "." + asked[1] + ") -> "
                        + (initReply[8] & 0xFF) + "." + (initReply[9] & 0xFF));
            }

            // The counters a server maintains itself. A reference names one (SERVERTIME); a server
            // with no system counter may answer none, which is a measurement and not a failure - so
            // the count and the names are printed rather than required.
            byte[] list = new byte[4];
            list[0] = (byte) opcode;
            list[1] = (byte) LIST_SYSTEM_COUNTERS;
            RawXClient.writeShortAt(list, 2, 1);
            client.sendRequest(list);
            byte[] listReply = client.readReply();
            int count = RawXClient.readInt(listReply, 8);
            System.out.println("  ListSystemCounters -> " + count + " system counter(s), reply "
                    + listReply.length + " bytes");
            // The entries' own bytes, for the record: the name is a length-prefixed padded list and
            // its stride is the one field a probe cannot guess without reading the reply.
            StringBuilder hex = new StringBuilder();
            for (int i = 32; i < Math.min(listReply.length, 96); i++) {
                hex.append(String.format("%02x", listReply[i]));
            }
            System.out.println("    payload[32..]: " + hex);
            // The entries themselves are **not** decoded, and that is deliberate. The name is a
            // length-prefixed padded list and the stride a first version assumed read one counter's
            // name out of the next one's fields ("counter=0x45434956 name= 7" is really two halves of
            // one entry). Nothing here needs them - this server keeps no system counters at all - and
            // a decoded field that is wrong is worse than an undecoded one, which is the whole of
            // B6's lesson about layouts. The count and the raw bytes are what the comparison uses.
            if (count > 0) {
                System.out.println("    (the entries are left packed; see tools/README.md)");
            }

            int counter = client.allocateId();
            byte[] create = new byte[16];
            create[0] = (byte) opcode;
            create[1] = (byte) CREATE_COUNTER;
            RawXClient.writeShortAt(create, 2, 4);
            RawXClient.writeIntAt(create, 4, counter);
            RawXClient.writeIntAt(create, 8, 0);            // initial value, low word
            RawXClient.writeIntAt(create, 12, 0);           // high word
            client.sendRequest(create);
            client.flush();
            System.out.println("  CreateCounter 0 -> " + queryCounter(client, opcode, counter));

            byte[] set = new byte[16];
            set[0] = (byte) opcode;
            set[1] = (byte) SET_COUNTER;
            RawXClient.writeShortAt(set, 2, 4);
            RawXClient.writeIntAt(set, 4, counter);
            RawXClient.writeIntAt(set, 8, 42);
            RawXClient.writeIntAt(set, 12, 0);
            client.sendRequest(set);
            System.out.println("  SetCounter 42 -> " + queryCounter(client, opcode, counter));

            byte[] change = new byte[16];
            change[0] = (byte) opcode;
            change[1] = (byte) CHANGE_COUNTER;
            RawXClient.writeShortAt(change, 2, 4);
            RawXClient.writeIntAt(change, 4, counter);
            RawXClient.writeIntAt(change, 8, 8);
            RawXClient.writeIntAt(change, 12, 0);
            client.sendRequest(change);
            System.out.println("  ChangeCounter +8 -> " + queryCounter(client, opcode, counter));

            byte[] destroyCounter = new byte[8];
            destroyCounter[0] = (byte) opcode;
            destroyCounter[1] = (byte) DESTROY_COUNTER;
            RawXClient.writeShortAt(destroyCounter, 2, 2);
            RawXClient.writeIntAt(destroyCounter, 4, counter);
            client.sendRequest(destroyCounter);

            System.out.println("  GetPriority(0) -> " + getPriority(client, opcode));
            byte[] setPriority = new byte[12];
            setPriority[0] = (byte) opcode;
            setPriority[1] = (byte) SET_PRIORITY;
            RawXClient.writeShortAt(setPriority, 2, 3);
            RawXClient.writeIntAt(setPriority, 4, 0);
            RawXClient.writeIntAt(setPriority, 8, -7);
            client.sendRequest(setPriority);
            System.out.println("  SetPriority(-7) then GetPriority(0) -> "
                    + getPriority(client, opcode));

            // The fence: created untriggered, triggered, reset, and read back after each. This is the
            // sequence the extension exists for, and the one a client cannot fake.
            int fence = client.allocateId();
            byte[] createFence = new byte[16];
            createFence[0] = (byte) opcode;
            createFence[1] = (byte) CREATE_FENCE;
            RawXClient.writeShortAt(createFence, 2, 4);
            // Drawable first, fence second - which is the opposite of the order the generated
            // class's getters list them in, and the reference says so with BadDrawable on the fence
            // id when a probe believes the getters.
            RawXClient.writeIntAt(createFence, 4, client.root());
            RawXClient.writeIntAt(createFence, 8, fence);
            createFence[12] = 0;                            // initially triggered: no
            client.sendRequest(createFence);
            System.out.println("  CreateFence(untriggered) -> " + queryFence(client, opcode, fence));

            byte[] trigger = new byte[8];
            trigger[0] = (byte) opcode;
            trigger[1] = (byte) TRIGGER_FENCE;
            RawXClient.writeShortAt(trigger, 2, 2);
            RawXClient.writeIntAt(trigger, 4, fence);
            client.sendRequest(trigger);
            System.out.println("  TriggerFence -> " + queryFence(client, opcode, fence));

            byte[] reset = new byte[8];
            reset[0] = (byte) opcode;
            reset[1] = (byte) RESET_FENCE;
            RawXClient.writeShortAt(reset, 2, 2);
            RawXClient.writeIntAt(reset, 4, fence);
            client.sendRequest(reset);
            System.out.println("  ResetFence -> " + queryFence(client, opcode, fence));

            byte[] destroyFence = new byte[8];
            destroyFence[0] = (byte) opcode;
            destroyFence[1] = (byte) DESTROY_FENCE;
            RawXClient.writeShortAt(destroyFence, 2, 2);
            RawXClient.writeIntAt(destroyFence, 4, fence);
            client.sendRequest(destroyFence);
            client.flush();
            System.out.println("  DestroyFence sent");
        }
    }

    private static String getPriority(RawXClient client, int opcode) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) GET_PRIORITY;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, 0);
        client.sendRequest(request);
        return String.valueOf(RawXClient.readInt(client.readReply(), 8));
    }

    private static String queryFence(RawXClient client, int opcode, int fence) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) QUERY_FENCE;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, fence);
        client.sendRequest(request);
        return client.readReply()[8] == 0 ? "triggered=no" : "triggered=yes";
    }

    private static String queryCounter(RawXClient client, int opcode, int counter) throws Exception {
        byte[] request = new byte[8];
        request[0] = (byte) opcode;
        request[1] = (byte) QUERY_COUNTER;
        RawXClient.writeShortAt(request, 2, 2);
        RawXClient.writeIntAt(request, 4, counter);
        client.sendRequest(request);
        byte[] reply = client.readReply();
        long low = RawXClient.readInt(reply, 8) & 0xFFFFFFFFL;
        long high = RawXClient.readInt(reply, 12);
        return String.valueOf(low | (high << 32));
    }

    private SyncProbe() {
    }
}
