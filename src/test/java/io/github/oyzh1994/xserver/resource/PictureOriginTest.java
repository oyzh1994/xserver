package io.github.oyzh1994.xserver.resource;

import io.github.oyzh1994.xserver.display.Screen;
import io.github.oyzh1994.xserver.display.VisualInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a picture on a window puts its pixels once the window has moved.
 *
 * <p>A picture carries the translation from its own coordinates to the pixel store, and for a window
 * that translation is the window's position in the framebuffer. Reading it once, when the picture is
 * created, is wrong for every window that is placed after it is created - which is how AWT opens a
 * dialog: it creates the window at the origin of the screen and only then configures it where it
 * belongs. The stale position is used for the clip as well as the address, and since the two then
 * disagree with the window's real rectangle the writes are not merely misplaced, they are refused:
 * the picture is asked to draw a dialog and the dialog keeps the background it was cleared to, with
 * not one pixel of its contents.
 *
 * <p>Measured against termora: the 消息 box of its keymap settings is 305x130, created at (0,0) and
 * configured to (497,335); the composite of its contents reported 0 of 39650 pixels written until the
 * origin was re-read per request.
 */
class PictureOriginTest {

    private static final int ROOT = 900;

    private static Screen screen() {
        VisualInfo visual = VisualInfo.trueColor24(0x200);
        return new Screen(0, ROOT, ROOT, List.of(visual), 24, visual.id());
    }

    private static XWindow window(Screen screen, XWindow parent, int id, int x, int y, int w,
                                  int h) {
        XWindow window = new XWindow(id, null, screen, parent, 24, XWindow.INPUT_OUTPUT,
                screen.rootVisual(), x, y, w, h, 0);
        window.setMapped(true);
        return window;
    }

    /** A picture over a window, as {@code CreatePicture} builds one. */
    private static Picture picture(Screen screen, XWindow window) {
        return new Picture(9, null, window,
                PictFormat.direct(1, screen.rootVisual(), 0), screen, screen.framebuffer(),
                window.absoluteX(), window.absoluteY(), window);
    }

    @Test
    void aPictureFollowsAWindowThatMovesAfterItWasCreated() {
        Screen screen = screen();
        XWindow root = window(screen, null, 1, 0, 0, ROOT, ROOT);
        XWindow dialog = window(screen, root, 2, 0, 0, 305, 130);
        Picture contents = picture(screen, dialog);
        int whereItWas = screen.framebuffer().pixel(0, 0);

        // The window is placed where the client actually wants it, after its picture exists.
        dialog.setPosition(497, 335);
        contents.refreshClip();

        contents.writePixel(0, 0, 0xFF112233);
        contents.writePixel(304, 129, 0xFF445566);

        assertEquals(0xFF112233, screen.framebuffer().pixel(497, 335),
                "the picture draws where the window is now, not where it was created");
        assertEquals(0xFF445566, screen.framebuffer().pixel(497 + 304, 335 + 129));
        assertEquals(whereItWas, screen.framebuffer().pixel(0, 0),
                "nothing is left behind at the old place");
    }

    @Test
    void aPictureReadsTheWindowWhereItIsNow() {
        Screen screen = screen();
        XWindow root = window(screen, null, 1, 0, 0, ROOT, ROOT);
        XWindow dialog = window(screen, root, 2, 0, 0, 305, 130);
        Picture contents = picture(screen, dialog);
        dialog.setPosition(497, 335);
        contents.refreshClip();
        screen.framebuffer().setPixel(497 + 3, 335 + 4, 0xFF010203);

        // A source picture on a window samples the same translation the writes use; leaving it at
        // the creation-time position reads whatever happens to be there instead.
        assertEquals(0xFF010203, contents.pixelAt(3, 4));
    }
}
