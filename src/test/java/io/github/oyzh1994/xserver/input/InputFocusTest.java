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
        server.input().setInputFocus(null, InputState.REVERT_TO_POINTER_ROOT);
        // Inside the outer window but outside the child that covers its top left corner.
        server.input().pointerMoved(60, 60);

        assertEquals(outer, server.input().focusWindow(), "keys belong to the window under the pointer");
    }
}
