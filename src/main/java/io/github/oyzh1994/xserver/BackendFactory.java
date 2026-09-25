package io.github.oyzh1994.xserver;

import io.github.oyzh1994.xserver.display.DisplayBackend;
import io.github.oyzh1994.xserver.display.HeadlessBackend;
import io.github.oyzh1994.xserver.display.JavaFxBackend;
import io.github.oyzh1994.xserver.display.SwingBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Chooses the display backend the server runs on.
 *
 * <p>JavaFX is the primary backend, but it is also the one that can be missing at runtime: the
 * jars may not be on the classpath, the natives may not load, or there may be no display for the
 * toolkit. The server has to run on such machines anyway, so the JavaFX path is started for real
 * before it is chosen and any failure - {@link Throwable}, including the {@link LinkageError}
 * raised when the jars are absent - falls back to Swing instead of aborting the server.
 */
public final class BackendFactory {

    private static final Logger log = LoggerFactory.getLogger(BackendFactory.class);

    /** Title for the window when the caller has none; matches the default of {@link ServerOptions}. */
    public static final String DEFAULT_TITLE = "xserver";

    private BackendFactory() {
    }

    /** Never returns null: an unusable JavaFX installation yields a Swing backend. */
    public static DisplayBackend create(ServerOptions.Backend backend) {
        return switch (backend) {
            case JAVAFX -> createJavaFx();
            case SWING -> createSwing();
            case HEADLESS -> new HeadlessBackend();
        };
    }

    private static DisplayBackend createJavaFx() {
        try {
            // Starting the toolkit here is the only honest test of whether JavaFX works: a missing
            // class, a missing native library and a missing display all show up at this point.
            JavaFxBackend.verifyAvailable();
            return new JavaFxBackend(DEFAULT_TITLE);
        } catch (Throwable t) {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return headlessInstead("JavaFX is not usable (" + describe(t) + ")");
            }
            log.warn("JavaFX is not usable ({}), falling back to the swing backend", describe(t));
            log.debug("JavaFX startup failed", t);
            return new SwingBackend(DEFAULT_TITLE);
        }
    }

    /** A Swing window, or the headless backend where there is no display to open one on. */
    private static DisplayBackend createSwing() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return headlessInstead("this machine has no display");
        }
        return new SwingBackend(DEFAULT_TITLE);
    }

    /**
     * The end of the fallback chain, said out loud.
     *
     * <p>A Swing window is opened on the event dispatch thread, so a machine that cannot show one
     * fails there rather than in {@code start} - out of the caller's sight. The server then served
     * every client, showed nothing, and logged nothing about it (C11). Where the platform itself says
     * there is no display there is no point trying the next toolkit: the honest answer is the headless
     * backend, and a line saying that is what happened.
     */
    private static DisplayBackend headlessInstead(String why) {
        log.warn("{}: nothing can be shown on this machine, so the server is running headless -"
                + " clients are served and no window is opened", why);
        return new HeadlessBackend();
    }

    /** Names the real cause when the failure arrived wrapped in an initializer or invocation. */
    private static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause
                && (cause instanceof ExceptionInInitializerError
                || cause instanceof InvocationTargetException)) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
