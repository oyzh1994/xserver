package io.github.oyzh1994.xserver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line options for the server. Parsed in X-server style so that the usual
 * {@code --display :1 -screen 0 1280x800x24} invocation works.
 */
public final class ServerOptions {

    /** Which rendering surface to open. */
    public enum Backend {
        /** JavaFX window with a software framebuffer blitted into a {@code WritableImage}. */
        JAVAFX,
        /** Swing window with a {@code BufferedImage}. Used when JavaFX cannot start. */
        SWING,
        /** No window at all: the framebuffer still exists and clients can connect. */
        HEADLESS;

        public static Backend parse(String value) {
            return switch (value.toLowerCase()) {
                case "javafx", "fx" -> JAVAFX;
                case "swing", "awt" -> SWING;
                case "headless", "none", "xvfb" -> HEADLESS;
                default -> throw new IllegalArgumentException(
                        "unknown backend '" + value + "', expected one of javafx|swing|headless");
            };
        }
    }

    private int displayNumber = 1;
    private int width = 1280;
    private int height = 800;
    /** True once the client asked for a size, so the default can be fitted to the display. */
    private boolean geometrySpecified;
    private int depth = 24;
    private Backend backend = Backend.JAVAFX;
    private boolean tcp = true;
    private boolean unixSocket = true;
    private boolean listenAll = true;
    private int maxClients = 256;
    private boolean authRequired;
    private File authFile;
    private String title = "xserver";
    private boolean logRequests;
    private boolean noResetOnLastClose = true;
    private int tickRate = 60;
    /** How long clients get to close themselves after the host window is closed. */
    private int closeGraceMillis = 2000;

    public static ServerOptions parse(String[] args) {
        ServerOptions options = new ServerOptions();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-help", "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                case "--display", "-display" -> options.displayNumber = parseDisplay(next(args, ++i));
                case "--geometry", "-geometry" -> options.parseGeometry(next(args, ++i));
                case "--backend" -> options.backend = Backend.parse(next(args, ++i));
                case "--title" -> options.title = next(args, ++i);
                case "--max-clients" -> options.maxClients = Integer.parseInt(next(args, ++i));
                case "--auth" -> {
                    options.authRequired = true;
                    String value = next(args, ++i);
                    if (!"true".equalsIgnoreCase(value) && !"yes".equalsIgnoreCase(value)) {
                        options.authFile = new File(value);
                    }
                }
                case "--no-auth" -> options.authRequired = false;
                case "--no-tcp" -> options.tcp = false;
                case "--no-unix" -> options.unixSocket = false;
                case "--local" -> options.listenAll = false;
                case "--log-requests" -> options.logRequests = true;
                case "--tick-rate" -> options.tickRate = Integer.parseInt(next(args, ++i));
                case "--close-grace-ms" -> options.closeGraceMillis =
                        Integer.parseInt(next(args, ++i));
                case "-screen" -> {
                    // consumes "-screen 0 WxHxD" but we only ever expose a single screen
                    i += 2;
                    if (i + 1 < args.length) {
                        options.parseGeometry(args[i + 1]);
                        i++;
                    }
                }
                default -> positional.add(arg);
            }
        }
        // bare form: "xserver :2"
        for (String value : positional) {
            if (value.startsWith(":")) {
                options.displayNumber = parseDisplay(value);
            }
        }
        return options;
    }

    /** Accepts ":1", "1", ":1.0", "host:1". */
    static int parseDisplay(String value) {
        int colon = value.indexOf(':');
        String rest = colon >= 0 ? value.substring(colon + 1) : value;
        int dot = rest.indexOf('.');
        if (dot >= 0) {
            rest = rest.substring(0, dot);
        }
        try {
            return Integer.parseInt(rest.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid display '" + value + "'", e);
        }
    }

    /** Accepts "1280x800" or "1280x800x24". */
    void parseGeometry(String value) {
        String[] parts = value.toLowerCase().split("x");
        if (parts.length < 2) {
            throw new IllegalArgumentException("invalid geometry '" + value + "', expected WxH or WxHxD");
        }
        width = Integer.parseInt(parts[0].trim());
        height = Integer.parseInt(parts[1].trim());
        if (parts.length >= 3) {
            depth = Integer.parseInt(parts[2].trim());
        }
        geometrySpecified = true;
    }

    private static String next(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[index - 1]);
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                xserver - a modern X11 display server

                Usage: xserver [display] [options]

                  --display :N          display number (default :1)
                  --geometry WxH[xD]    root window size (default 1280x800x24)
                  --backend NAME        javafx | swing | headless (default javafx)
                  --title TITLE         window title
                  --max-clients N       maximum simultaneous clients (default 256)
                  --auth [FILE]         require MIT-MAGIC-COOKIE-1 using ~/.Xauthority
                  --no-auth             disable access control (default)
                  --no-tcp              do not listen on TCP port 6000+N
                  --no-unix             do not listen on /tmp/.X11-unix/XN
                  --local               only listen on loopback
                  --log-requests        log every request
                  --tick-rate N         screen refresh rate (default 60)
                  --close-grace-ms N    time clients get to close themselves once the window is
                                        closed, before the server disconnects them (default 2000)

                The display is also reported through DISPLAY, so clients can be started with
                  DISPLAY=:1 xeyes
                """);
    }

    /**
     * Shrinks the default screen to fit the display it will be shown on.
     *
     * <p>A 1280x800 screen is wider than a laptop panel once the dock and menu bar are taken out,
     * and a window larger than the display simply runs off the edge with no way to scroll to it.
     * An explicitly requested size is left alone: the caller may know better, and a screen that
     * does not fit on the host is a legitimate thing to ask for.
     */
    public void fitToDisplay() {
        if (geometrySpecified) {
            return;
        }
        try {
            var bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            if (bounds.width > 0 && bounds.height > 0
                    && (bounds.width < width || bounds.height < height)) {
                width = Math.min(width, bounds.width);
                height = Math.min(height, bounds.height);
            }
        } catch (Throwable e) {
            // Headless, or no display information: keep the requested size.
        }
    }

    /** True when the caller asked for an explicit size. */
    public boolean isGeometrySpecified() {
        return geometrySpecified;
    }

    public int displayNumber() {
        return displayNumber;
    }

    public int tcpPort() {
        return 6000 + displayNumber;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public Backend backend() {
        return backend;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
    }

    public boolean tcp() {
        return tcp;
    }

    public boolean unixSocket() {
        return unixSocket;
    }

    public boolean listenAll() {
        return listenAll;
    }

    public int maxClients() {
        return maxClients;
    }

    public boolean authRequired() {
        return authRequired;
    }

    public File authFile() {
        return authFile;
    }

    public String title() {
        return title;
    }

    public boolean logRequests() {
        return logRequests;
    }

    public boolean resetOnLastClose() {
        return !noResetOnLastClose;
    }

    public int tickRate() {
        return tickRate;
    }

    /** How long a client has to close its windows itself once the host window has closed. */
    public int closeGraceMillis() {
        return closeGraceMillis;
    }
}
