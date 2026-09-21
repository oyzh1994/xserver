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
            case SWING -> new SwingBackend(DEFAULT_TITLE);
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
            log.warn("JavaFX is not usable ({}), falling back to the swing backend", describe(t));
            log.debug("JavaFX startup failed", t);
            return new SwingBackend(DEFAULT_TITLE);
        }
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
