package io.github.oyzh1994.xserver.extension;

import com.github.moaxcp.x11.protocol.XProtocolPlugin;
import com.github.moaxcp.x11.protocol.XRequest;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.client.ClientConnection;

/**
 * An X extension served by this server.
 *
 * <p>Request decoding is delegated to the generated {@link XProtocolPlugin} for the extension,
 * which knows every opcode and field layout. Extensions only implement behaviour, so adding a new
 * one means wiring the plugin and handling the decoded requests.
 */
public interface Extension {

    /** Protocol name as reported by {@code QueryExtension}, for example {@code SHAPE}. */
    String name();

    /** Major opcode assigned when the extension was registered. */
    byte majorOpcode();

    /** First event code reserved for this extension. */
    byte firstEvent();

    /** First error code reserved for this extension. */
    byte firstError();

    /** The generated protocol plugin used to decode requests. */
    XProtocolPlugin plugin();

    /** Called once after registration, so the extension can allocate its resources. */
    default void init(XServer server) {
    }

    /**
     * Decodes a request the generated decoder cannot, or returns null to let the plugin decode it.
     *
     * <p>Some generated readers size their last list from the request length with the wrong
     * arithmetic and throw on perfectly valid requests. An extension that has such a request says so
     * here; see {@code VariableLengthDecoders} for the full explanation.
     */
    default XRequest decodeRequest(int minorOpcode, com.github.moaxcp.x11.protocol.X11Input in)
            throws java.io.IOException {
        return null;
    }

    /** Handles one decoded request. */
    void handleRequest(XServer server, ClientConnection client, XRequest request);

    /** Lets an extension drop state belonging to a client that has gone away. */
    default void clientDisconnected(XServer server, ClientConnection client) {
    }

    /**
     * Whether the extension should appear before the client has used it. Extensions that must be
     * present in {@code ListExtensions} regardless of usage return true.
     */
    default boolean alwaysPresent() {
        return true;
    }
}
