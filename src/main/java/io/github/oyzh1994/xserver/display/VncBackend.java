package io.github.oyzh1994.xserver.display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Rectangle;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the screen to another machine, as a VNC server (backlog D2).
 *
 * <p>The fourth backend, and the odd one out: the other three present the framebuffer to a window on
 * <em>this</em> machine, and this one presents it to whoever asks over the network. It is a backend
 * rather than a feature bolted onto one because the contract already fits - the display tick calls
 * {@link #present} with the rectangle that changed, which is exactly what an RFB update is - and
 * because everything else the server does (the screen, the windows, the input state, the damage
 * rectangles) is then shared with it for nothing.
 *
 * <p><b>There is no window, so nothing here can be closed.</b> {@link #isClosed()} answers no for
 * ever and {@link #isHeadless()} yes: the server runs until it is stopped, which is also what makes
 * the VNC backend compose with {@code --detach-on-close} (D1) without meaning anything different -
 * a server with no window at all is what detaching produces.
 *
 * <p><b>A slow client is that client's own problem.</b> {@link #present} is called from the display
 * tick and does nothing but add a rectangle to each session's pending damage; the socket is written
 * from the session's own thread, and only when that client asks for an update. That is the RFB
 * protocol's own backpressure - and this server's rule (E5) that no other client waits for a slow
 * one - arrived at from two directions at once.
 */
public final class VncBackend implements DisplayBackend {

    private static final Logger log = LoggerFactory.getLogger(VncBackend.class);

    private final int port;
    private final String title;
    private final boolean loopbackOnly;
    /** The password a client must type, or null when the display is served to anyone. */
    private final String password;

    private final List<RfbSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private ServerSocket socket;
    private Screen screen;
    private InputSink input;
    private volatile int boundPort;
    /** The X display this serves, for the name a viewer shows in its title bar. */
    private volatile int displayNumber;

    /**
     * @param port         the TCP port to listen on, or 0 for any free one (which a test wants)
     * @param title        what the client's window will say it is connected to
     * @param loopbackOnly true when the server was told to listen on loopback only ({@code --local})
     * @param password     the password a client must type, or null for none
     */
    public VncBackend(int port, String title, boolean loopbackOnly, String password) {
        this.port = port;
        this.title = title;
        this.loopbackOnly = loopbackOnly;
        this.password = password;
    }

    @Override
    public String name() {
        return "vnc";
    }

    @Override
    public boolean isHeadless() {
        // No window is opened on this machine; the screen goes over the network instead.
        return true;
    }

    @Override
    public void start(Screen screen, int displayNumber, InputSink input) {
        this.screen = screen;
        this.input = input;
        this.displayNumber = displayNumber;
        try {
            ServerSocket bound = new ServerSocket();
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(loopbackOnly ? InetAddress.getLoopbackAddress() : null,
                    port));
            socket = bound;
            boundPort = bound.getLocalPort();
            running.set(true);
            Thread accept = new Thread(this::accept, "xserver-vnc");
            accept.setDaemon(true);
            accept.start();
            log.info("VNC on port {}: connect with vnc://<this machine>:{} - {}",
                    boundPort, boundPort, password == null
                            ? "the display is served to anyone who connects, so this is for a"
                                    + " network you trust (--vnc-password-file asks for a password)"
                            : "clients are asked for the password in --vnc-password-file");
        } catch (IOException e) {
            running.set(false);
            throw new IllegalStateException("cannot listen for VNC on port " + port + ": "
                    + e.getMessage(), e);
        }
    }

    /** The port actually bound, which is what a caller that asked for 0 needs to know. */
    public int boundPort() {
        return boundPort;
    }

    @Override
    public void present(Screen screen, Rectangle damage, CursorSprite cursor) {
        if (damage == null || sessions.isEmpty()) {
            return;
        }
        for (RfbSession session : sessions) {
            session.addDamage(damage);
        }
    }

    @Override
    public void screenResized(Screen screen) {
        for (RfbSession session : sessions) {
            session.screenResized();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        ServerSocket bound = socket;
        if (bound != null) {
            try {
                bound.close();
            } catch (IOException e) {
                log.debug("closing the VNC listener", e);
            }
        }
        for (RfbSession session : sessions) {
            session.close();
        }
        sessions.clear();
    }

    @Override
    public boolean isClosed() {
        // Nothing a user can close: the server keeps running until it is stopped.
        return false;
    }

    private void accept() {
        while (running.get()) {
            try {
                Socket client = socket.accept();
                // The name a viewer puts in its window: the X display it is watching, not the
                // screen's index - "xserver :96" is what a user recognises, and the index is 0 for
                // the only screen this backend serves.
                RfbSession session = new RfbSession(client, screen, input,
                        title + " :" + displayNumber, password);
                sessions.add(session);
                Thread thread = new Thread(() -> {
                    try {
                        session.run();
                    } finally {
                        sessions.remove(session);
                    }
                }, "xserver-vnc-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (running.get()) {
                    log.debug("accepting a VNC client failed", e);
                }
            }
        }
    }
}
