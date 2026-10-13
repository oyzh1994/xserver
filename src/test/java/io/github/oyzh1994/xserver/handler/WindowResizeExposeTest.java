package io.github.oyzh1994.xserver.handler;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.test.RawXClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Exposes a resize owes are one series, and each one says how many are still to come.
 *
 * <p>A window grown by its client is told to draw the strip it gained, and a real server sends the
 * rectangles of that strip as a *series*: the {@code count} field of every Expose but the last says
 * how many more are on the way. Toolkits read it - it is the whole point of the field - and Xt's
 * exposure compression waits for the rest of a series and hands the widget one region, whose bounding
 * box is then the whole window.
 *
 * <p>That is what {@code xclock} needs and what this server did not give it. Grown from 300x300 to a
 * maximised window, xclock frees its backing pixmap, makes a new one at the new size, and lays out the
 * clock again - but it draws only the area the Exposes name. Sent two Exposes that each claim to be
 * the last, it draws both strips separately and copies its new, mostly untouched pixmap over the
 * window, and the untouched part of it - whatever the server's fresh memory holds, black here - lands
 * on screen: the black box in the top left corner of the maximised window.
 *
 * <p>Every number below was measured against a reference server (Xvfb) with a raw client that
 * creates a 600x500 window, grows it to 800x600, and prints each Expose it is sent; the reference's
 * answer is exactly the two rectangles and the two counts asserted here.
 */
class WindowResizeExposeTest {

    /** A display number nothing else in the suite uses; the server listens on TCP 6000 + it. */
    private static final int DISPLAY = 98;

    private static final int EXPOSE = 12;

    /** ExposureMask, the event a toolkit draws itself from. */
    private static final int EXPOSURE_MASK = 1 << 15;

    /** SHAPE's bounding kind and its {@code Set} operation. */
    private static final int KIND_BOUNDING = 0;
    private static final int SET = 0;

    /** Where an event's {@code window} field sits, in bytes. */
    private static final int WINDOW = 4;

    /** Where an Expose event's fields sit, in bytes: x, y, width, height, and the count. */
    private static final int X = 8;
    private static final int Y = 10;
    private static final int WIDTH = 12;
    private static final int HEIGHT = 14;
    private static final int COUNT = 16;

    @Test
    void aResizeSendsTheGainedAreaAsASeriesThatCountsItselfDown() throws Exception {
        XServer server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix"}));
        server.start();
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 600, 500);
            client.selectInput(window, EXPOSURE_MASK);
            client.mapWindow(window);
            client.flush();

            byte[] alone = client.readEvent(EXPOSE);
            assertNotNull(alone, "the map owes the window an Expose for the whole of itself");
            assertEquals(0, RawXClient.readShort(alone, COUNT),
                    "an Expose with nothing behind it is the last of its series");

            client.configureWindow(window, 0, 0, 800, 600);
            client.flush();

            List<byte[]> series = new ArrayList<>();
            byte[] expose;
            do {
                expose = client.readEvent(EXPOSE);
                assertNotNull(expose, "the series ended while its last Expose still promised more");
                series.add(expose);
            } while (RawXClient.readShort(expose, COUNT) != 0);

            assertEquals(2, series.size(), "the grown strip is the two rectangles the reference sends");
            assertExpose(series.get(0), 600, 0, 200, 500, 1);
            assertExpose(series.get(1), 0, 500, 800, 100, 0);
        } finally {
            server.stop();
        }
    }

    /**
     * A shape gain exposes the window's children, not just the window.
     *
     * <p>A bounding shape clips a window's inferiors as much as the window itself: a child's visible
     * region is its own region intersected with every ancestor's shape, so when the shape gains area
     * the child gains it too. The window cannot stand in for the child - a child that covers the
     * window leaves the window's own visible region empty, so the Expose the window is sent names
     * nothing - and a toolkit that is never told (xeyes: the shell carries the eyes' mask, the eyes
     * widget covers the shell) never redraws. Maximised, its eyes stayed the size they had before
     * (2026-10-13).
     */
    @Test
    void aShapeThatGainsAreaExposesTheChildThatCoversTheWindow() throws Exception {
        XServer server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix"}));
        server.start();
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            client.socketTimeout(1000);
            int shell = client.allocateId();
            int widget = client.allocateId();
            client.createWindow(shell, client.root(), 0, 0, 200, 200);
            client.createWindow(widget, shell, 0, 0, 200, 200);
            client.selectInput(shell, EXPOSURE_MASK);
            client.selectInput(widget, EXPOSURE_MASK);
            client.mapWindow(shell);
            client.mapWindow(widget);
            client.flush();
            drainExposes(client);

            int shape = client.queryExtension("SHAPE");
            assertTrue(shape != 0, "the extension this test is about");

            // A shape smaller than the window, so that growing it is a gain the child has to hear
            // about. Taking area away is not: a child draws nothing where it is not shown.
            client.shapeRectangles(shape, shell, KIND_BOUNDING, SET, new int[][] {{40, 40, 20, 20}});
            client.flush();
            drainExposes(client);

            client.shapeRectangles(shape, shell, KIND_BOUNDING, SET, new int[][] {{0, 0, 200, 200}});
            client.flush();

            List<byte[]> series = readExposeSeries(client);
            for (byte[] expose : series) {
                assertEquals(widget, RawXClient.readInt(expose, WINDOW),
                        "the Expose is the covering child's: the shell's own region is empty");
            }
            assertTrue(covers(series, 0, 0) && covers(series, 199, 199),
                    "the series names the area the shape gave back, corners included");
        } finally {
            server.stop();
        }
    }

    /**
     * A gain divided into more pieces than a client can use is sent as one Expose, not a flood.
     *
     * <p>A shape mask leaves a rectangle per scanned row, and nothing merges them: xeyes' eyes at a
     * maximised size are about two thousand rows. A series that long is useless to the client - a
     * toolkit combines a series into one region anyway, whose bounding box is what it draws - and it
     * overflows the connection's own queue of a thousand messages, which drops the client.
     */
    @Test
    void aGainDividedIntoTooManyPiecesIsSentAsItsBoundingRectangle() throws Exception {
        XServer server = new XServer(ServerOptions.parse(new String[]{"--display", ":" + DISPLAY,
                "--backend", "headless", "--no-unix"}));
        server.start();
        try (RawXClient client = RawXClient.connect(DISPLAY)) {
            client.socketTimeout(1000);
            int window = client.allocateId();
            client.createWindow(window, client.root(), 0, 0, 200, 200);
            client.selectInput(window, EXPOSURE_MASK);
            client.mapWindow(window);
            client.flush();
            drainExposes(client);

            int shape = client.queryExtension("SHAPE");
            client.shapeRectangles(shape, window, KIND_BOUNDING, SET,
                    new int[][] {{0, 0, 10, 10}});
            client.flush();
            drainExposes(client);

            // A hundred and one one-pixel-tall stripes: a region whose pieces number far more than
            // any client reads one at a time, and which only a bounding rectangle can describe.
            int[][] stripes = new int[101][4];
            for (int i = 0; i < 100; i++) {
                stripes[i] = new int[] {0, i * 2, 200, 1};
            }
            stripes[100] = new int[] {0, 199, 200, 1};
            client.shapeRectangles(shape, window, KIND_BOUNDING, SET, stripes);
            client.flush();

            List<byte[]> series = readExposeSeries(client);
            assertEquals(1, series.size(),
                    "the whole region goes out as one Expose, not a hundred");
            assertExpose(series.get(0), 0, 0, 200, 200, 0);
        } finally {
            server.stop();
        }
    }

    /** Reads a whole Expose series: the one whose count is zero is the last. */
    private static List<byte[]> readExposeSeries(RawXClient client) throws Exception {
        List<byte[]> series = new ArrayList<>();
        byte[] expose;
        do {
            expose = client.readEvent(EXPOSE);
            assertNotNull(expose, "the series ended while its last Expose still promised more");
            series.add(expose);
        } while (RawXClient.readShort(expose, COUNT) != 0);
        return series;
    }

    /** Reads and throws away the Exposes an earlier step owes, so the next read starts clean. */
    private static void drainExposes(RawXClient client) throws Exception {
        while (client.readEvent(EXPOSE) != null) {
            // Each of these is a series of its own, and the series this test cares about is the one
            // that follows, so they are only carried off the stream.
        }
    }

    /** True when some rectangle in the series holds the point. */
    private static boolean covers(List<byte[]> series, int x, int y) {
        for (byte[] expose : series) {
            int left = RawXClient.readShort(expose, X);
            int top = RawXClient.readShort(expose, Y);
            if (x >= left && x < left + RawXClient.readShort(expose, WIDTH)
                    && y >= top && y < top + RawXClient.readShort(expose, HEIGHT)) {
                return true;
            }
        }
        return false;
    }

    private static void assertExpose(byte[] expose, int x, int y, int width, int height, int count) {
        assertEquals(x, RawXClient.readShort(expose, X), "expose x");
        assertEquals(y, RawXClient.readShort(expose, Y), "expose y");
        assertEquals(width, RawXClient.readShort(expose, WIDTH), "expose width");
        assertEquals(height, RawXClient.readShort(expose, HEIGHT), "expose height");
        assertEquals(count, RawXClient.readShort(expose, COUNT),
                "the count field is how many Exposes are still to come");
    }
}
