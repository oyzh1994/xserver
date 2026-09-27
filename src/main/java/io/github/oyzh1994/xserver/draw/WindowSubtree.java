package io.github.oyzh1994.xserver.draw;

import io.github.oyzh1994.xserver.resource.ShapeState;
import io.github.oyzh1994.xserver.resource.XWindow;

import java.awt.Rectangle;
import java.util.List;

/**
 * A top level window's own picture: its subtree composited from the windows' own stores.
 *
 * <p>This is what a host window per top level (C8) has to show, and it is the piece the first
 * attempt at that item did not have. A frame cannot show a rectangle of the <b>screen</b>, because
 * the screen holds every window the client has mapped: a dialog that overlaps its parent would be
 * seen inside the parent's frame as well, which is exactly what the prototype measured and was
 * reverted for. A frame cannot show the top level's own store either, because a toolkit's top level
 * is a frame whose content window covers it completely - its own pixels are not what the user sees.
 *
 * <p>So the picture is built from the stores, bottom to top: each mapped window in the subtree
 * paints what it owns ({@link XWindow#ownRegion()}, which is already clipped by its ancestors'
 * rectangles and shapes) at its offset inside the top level, and a child paints over its parent
 * because it comes after it. A window that is not mapped paints nothing and its parent's pixels show
 * through, which is what the screen does with it too.
 *
 * <p>What this does <em>not</em> include is a window's border: borders are drawn onto the screen
 * rather than kept in a store, so a frame shows the client's rectangle without the ring this server
 * paints around it. A top level in the clients this is for has no border - the host window is the
 * frame - so nothing is missing in practice, and it is written down here so that it is a decision
 * rather than an oversight.
 */
public final class WindowSubtree {

    private WindowSubtree() {
    }

    /**
     * Paints the subtree of {@code toplevel} onto {@code target}, in the top level's own coordinates.
     *
     * @param area the part of the top level to draw, in the top level's coordinates - the caller's
     *     damage, so that a frame that shows one small change does not recomposite the window
     */
    public static void render(XWindow toplevel, Framebuffer target, List<Rectangle> area) {
        if (area.isEmpty()) {
            return;
        }
        renderInto(toplevel, toplevel, target, area);
    }

    private static void renderInto(XWindow window, XWindow toplevel, Framebuffer target,
                                   List<Rectangle> area) {
        if (window.isMapped() && !window.isInputOnly() && window.keepsOwnPixels()) {
            paint(window, toplevel, target, area);
        }
        // Bottom to top, and after the window itself: a child is above its parent, and a later
        // sibling is above an earlier one, which is the order the stores have to be laid down in.
        for (XWindow child : window.children()) {
            renderInto(child, toplevel, target, area);
        }
    }

    private static void paint(XWindow window, XWindow toplevel, Framebuffer target,
                              List<Rectangle> area) {
        int offsetX = window.absoluteX() - toplevel.absoluteX();
        int offsetY = window.absoluteY() - toplevel.absoluteY();
        Framebuffer source = window.ownPixels();
        for (Rectangle own : window.ownRegion()) {
            Rectangle part = new Rectangle(own.x + offsetX, own.y + offsetY, own.width, own.height);
            for (Rectangle piece : ShapeState.intersect(List.of(part), area)) {
                target.copyFrom(source, piece.x - offsetX, piece.y - offsetY, piece.x, piece.y,
                        piece.width, piece.height);
            }
        }
    }
}
