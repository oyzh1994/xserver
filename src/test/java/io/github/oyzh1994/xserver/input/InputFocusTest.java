package io.github.oyzh1994.xserver.input;

import io.github.oyzh1994.xserver.ServerOptions;
import io.github.oyzh1994.xserver.XServer;
import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.resource.XWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The input focus follows its window: it reverts when that window is destroyed, and a focus of the
 * root means the keys belong to whatever the pointer is in.
 *
 * <p>Without both, a toolkit that moves the focus to a dialog and lets the dialog be destroyed
 * leaves the focus on a window that no longer exists, and every keystroke aimed at the window
 * underneath is swallowed - clicks keep working, so the window looks alive while its text fields
 * cannot be typed into at all.
 */
public class InputFocusTest {

    private static XWindow window(Screen screen, XWindow parent, int id, int x, int y, int w,
                                  int h) {
        return new XWindow(id, null, screen, parent, 24, XWindow.INPUT_OUTPUT,
                screen.rootVisual(), x, y, w, h, 0);
    }

    private static XServer serverWithTwoWindows() {
        XServer server = new XServer(ServerOptions.parse(
                new String[]{"--display", "93", "--backend", "headless"}));
        XWindow outer = window(server.screen(), server.rootWindow(), 0x400001, 10, 10, 60, 60);
        server.resources().add(outer);
        outer.setMapped(true);
        XWindow inner = window(server.screen(), outer, 0x400002, 5, 5, 20, 20);
        server.resources().add(inner);
        inner.setMapped(true);
        return server;
    }

    @Test
    void focusRevertsToTheParentWhenTheFocusedWindowIsDestroyed() {
        XServer server = serverWithTwoWindows();
        XWindow outer = (XWindow) server.resources().get(0x400001);
        XWindow inner = (XWindow) server.resources().get(0x400002);
        server.input().setInputFocus(inner, InputState.REVERT_TO_PARENT);

        server.windows().destroyWindowTree(null, inner);

        assertEquals(outer, server.input().inputFocus(), "the focus went to the parent, as asked");
    }

    @Test
    void focusGoesToTheRootWhenTheWindowItWasOnIsDestroyed() {
        XServer server = serverWithTwoWindows();
        XWindow inner = (XWindow) server.resources().get(0x400002);
        server.input().setInputFocus(inner, InputState.REVERT_TO_POINTER_ROOT);

        server.windows().destroyWindowTree(null, inner);

        assertEquals(server.rootWindow(), server.input().inputFocus());
    }

    @Test
    void aRootFocusSendsKeysToTheWindowUnderThePointer() {
        XServer server = serverWithTwoWindows();
        XWindow outer = (XWindow) server.resources().get(0x400001);
        // The focus is the root, which means PointerRoot: the focus follows the pointer.
        server.input().setInputFocus(server.rootWindow(), InputState.REVERT_TO_POINTER_ROOT);
        // Inside the outer window but outside the child that covers its top left corner.
        server.input().pointerMoved(60, 60);

        assertEquals(outer, server.input().focusWindow(), "keys belong to the window under the pointer");
    }

    /**
     * The root window asked for by id is a state of its own, and the keys follow the pointer in it
     * just as they do for {@code PointerRoot}.
     *
     * <p>Measured against a reference server with {@code tools/KeyTargetProbe}: with the focus on the
     * root - whether asked for directly or reached by a revert - the {@code KeyPress} is reported
     * against the window the pointer is in, and only {@code None} leaves it unreported. A server that
     * hands the key to the root window instead sends it to a client that never asked for it, which is
     * a text field that looks focused, still reacts to the mouse, and accepts nothing.
     */
    @Test
    void theRootWindowFocusedByIdAlsoSendsKeysToTheWindowUnderThePointer() {
        XServer server = serverWithTwoWindows();
        XWindow outer = (XWindow) server.resources().get(0x400001);
        // The three-argument form, because the two-argument one is the shorthand for "the root means
        // PointerRoot": the request handler passes the flag the request carried, and a client that
        // names the root's id asks for the root window, which GetInputFocus then answers.
        server.input().setInputFocus(server.rootWindow(), InputState.REVERT_TO_PARENT, false);
        server.input().pointerMoved(60, 60);

        assertEquals(server.rootWindow(), server.input().inputFocus(), "the root window, not a window"
                + " of its own");
        assertEquals(false, server.input().focusIsPointerRoot(), "and not PointerRoot either");
        assertEquals(outer, server.input().focusWindow(), "keys still belong to the pointer's window");
    }

    /**
     * The gesture a toolkit makes: it moves the focus to the page it just opened and closes the page
     * by unmapping it, so the revert - not the client - is what puts the focus back.
     *
     * <p>{@code RevertToParent} out of a child of the root lands on the root window, and from there
     * the keys have to reach the window under the pointer or the application's own window never sees
     * another keystroke: EasyShell's 片段 page closed this way on 2026-09-29 left its home page's
     * search box unable to accept input until an unrelated dialog was opened and closed.
     */
    @Test
    void afterTheFocusedWindowIsUnmappedTheKeysReachTheWindowUnderThePointer() {
        XServer server = serverWithTwoWindows();
        XWindow outer = (XWindow) server.resources().get(0x400001);
        // The page: a toplevel of its own, so the revert has nowhere to go but the root.
        XWindow page = window(server.screen(), server.rootWindow(), 0x400003, 200, 200, 20, 20);
        server.resources().add(page);
        page.setMapped(true);
        server.input().setInputFocus(page, InputState.REVERT_TO_PARENT);
        server.input().pointerMoved(60, 60);

        server.windows().unmapWindow(page, false);

        assertEquals(server.rootWindow(), server.input().inputFocus(), "the revert landed on the root");
        assertEquals(outer, server.input().focusWindow(), "and the keys reach the pointer's window");
    }

    @Test
    void aFocusOfNoneSendsKeysNowhere() {
        XServer server = serverWithTwoWindows();
        // A focus of null is None, which is a state of its own: no window has the keyboard, so the
        // keys belong to none of them rather than to whichever one the pointer is in.
        server.input().setInputFocus(null, InputState.REVERT_TO_POINTER_ROOT);
        server.input().pointerMoved(60, 60);

        assertEquals(null, server.input().focusWindow(), "a focus of None names no window");
        assertEquals(null, server.input().inputFocus(), "and it is not PointerRoot either");
    }
}
