# Design notes

Why this server is built the way it is. The README describes the shape of the code; this file
records the decisions behind it, the platform behaviour that forced some of them, and what is
deliberately left undone - so that a change does not have to rediscover it.

Everything here was measured on macOS with JavaFX 25 loaded from the classpath, connecting real
clients (`xclock`, `xeyes`, `xcalc`, and a JavaFX application) from a Linux VM over TCP. Where a
statement is a platform fact rather than a preference, that is said.

## One screen per client connection, one window per screen

A connection is given a `Screen` of its own when it connects (`XServer.openClientScreen`, called from
the handshake before the setup reply), so a client's windows live on a screen no other client can
draw into, and the setup reply names that screen's root as the only one the client sees.

This is what makes each forwarded application appear as its own window, and it is deliberately not
the alternative - one native window per X top-level window, as XQuartz does. That model needs every
window's pixels kept separately, because two windows of one client (a dialog over its parent) overlap
and the covered one's content has to survive; this server has one framebuffer per screen, draws
straight into it and keeps no obscured content, so per-window native windows would either lose
dialogs or cost a second rendering model. One screen per client gets the same user-visible result
for the case that matters (one forwarded application = one window) and keeps a client's own dialogs,
menus and popups overlapping correctly inside its window.

The host window shows a **sub-rectangle** of the screen (`DisplayBackend.setViewport`): the union of
the client's mapped top-level windows, so a small application arrives in a small window rather than
in a screen-sized one with black around it. The screen itself is never resized: growing the
framebuffer would make `Screen`/`Framebuffer` sizes mutable and would tell the client a root size it
can never see again (there is no RandR here). A window larger than `--geometry` is clipped, as it was
before.

`--single-window` keeps the older shape - one shared screen, every client on it - which is also the
mode the in-process tests build on.

## The display tick owns the windows

`XServer.eventLoop` runs on its own non-daemon thread and is the only thread that opens or presents
to a window. A request thread never calls into a backend:

- `DisplayBackend.start`, `setViewport`, `stop` and `releaseToolkit` reach the toolkit's thread and
  wait for it, and holding `serverLock` across that wait deadlocks - the JavaFX input callbacks take
  the same lock.
- So the tick reads everything it needs under the lock, releases it, and only then calls the backend.

Two consequences worth keeping:

- **A window opens when the client first maps something**, not when it connects, so a client that
  only asks questions (`xdpyinfo`) never opens one.
- **`Platform.exit()` is called once, at the very end** (`releaseToolkit`), never by the first window
  that closes: it is one-way, and would take every other client's window with it and stop the toolkit
  starting again.

## The pointer sprite

The sprite comes from the client's cursor and is drawn by the backend **over** the presented frame,
never into the framebuffer - a client's pixels are then never covered by it, and `GetImage` never
reads it back. Two rules make it behave:

- **Its bookkeeping happens before the damage for the frame is taken**, and outside the "is there any
  damage" test. Sitting behind that test, a pointer moving over a window whose client was not
  repainting produced no damage and so no frame, and the cursor only caught up whenever the client
  happened to draw. That reads as a stuttering cursor while dragging, and it was the hardest bug in
  this codebase to see, because every load that keeps the client busy hides it.
- **Nothing is drawn until the server knows where the pointer is** (`InputState.pointerKnown`, set by
  the first `movePointer`). The position starts at the origin, and a sprite drawn there is a second
  pointer in the window's top left corner, next to the platform's own arrow, until the first mouse
  event arrives.

## The host pointer on macOS

The window hides the platform's pointer so the sprite is the only one over it. Three platform facts
shape that (`JavaFxBackend.hostCursor`, `MacCursor`):

- **`Cursor.NONE` is not an empty cursor.** JavaFX implements it as a process-wide
  `[NSCursor hide]`, undone only when a window's cursor changes away from NONE, so it has to be a
  loan: NONE while the pointer is over the window, `Cursor.DEFAULT` everywhere else, re-applied on
  enter/exit, on window close and in `stop()`.
- **An image cursor is ignored by the platform.** A fully transparent one is drawn as an opaque white
  square, and an *opaque* one was measured still showing the standard arrow. No choice of pixels
  makes an `ImageCursor` work here, so `Cursor.NONE` plus handing it back is the only mechanism.
- **Whether the window is active is not part of the test.** A freshly opened window is usually not
  active, and requiring focus left the platform arrow on top of the client's cursor - two pointers a
  few pixels apart - until the user clicked. `pointerOverWindow` is set by *any* mouse event inside,
  not by `MOUSE_ENTERED` alone, because a window that opens under a stationary pointer never gets an
  enter event.

Two things cannot be verified from a test and need an eye on the screen: **`screencapture -C`
composites the cursor itself and ignores whether the application has hidden it** (a capture with the
pointer deliberately visible and one with it hidden came out byte-identical), and the pointer is only
painted where the application is active.

Known gap: an application that is not the active one is ignored by macOS for cursor changes. A
host window that opens behind the IDE never becomes active (its title bar stays grey), so the desktop
arrow stays until the user clicks it. Raising and focusing the window once when its client first
draws would fix it; it is deliberately not done, because a window that jumps to the front on every
client is worse than the arrow.

## Default colours

`Screen.DEFAULT_BACKGROUND_ARGB` (#E8E8E8) is what a screen shows where nobody has drawn;
`DEFAULT_BORDER_ARGB` (#A0A0A0) is what a window's border starts with, a shade darker so a window
has an edge. `XWindow`'s fields carry both, so every constructor path agrees.

The light default is not cosmetic. A real X server paints its root window with a light grey weave,
so a client that never sets a background - `xeyes` is one: its request stream contains no
`CWBackPixel` at all - comes up looking light. With a black default the same client arrives as a
black rectangle, which is what "xeyes looks wrong here" turned out to mean.

Borders are painted after `repaintUnder`, clipped to `outerVisibleRegion()`. Both parts matter: the
border lives outside the window's content, so clipping it to the content region leaves all four
bands zero wide and draws nothing (which is what this server did - the one entry point that got it
right had no callers), and painting it before the windows underneath are repainted lets the root
cover it again.

## Ending a session

Both directions used to leave the other side hanging, and both are now explicit:

- **Closing a window** asks that client to close, the way a window manager does: a
  `ClientMessage` with `WM_PROTOCOLS` as its type and `WM_DELETE_WINDOW` as its first item, sent only
  to root children whose `WM_PROTOCOLS` property lists it (read as big-endian 32 bit items, which is
  how properties are stored). Once `--close-grace-ms` is up the connection is cut, which is what
  actually ends it - toolkits often ignore the message.
- **A client going away** closes its window and frees its screen, and leaves the other clients alone.
  In `--single-window` mode the last client going quiet stops frames rather than leaving a frozen
  empty window live.

## Deliberately not done

- **`GetKeyboardMapping` follows what the display backends translate**, and letters are looked up by
  character (`Keymap.keycodeForLetter`), because US keycodes are not in alphabet order.
- **SHAPE's bounding shape does not clip drawing** - only the input shape is honoured - so a shaped
  window's corners are still painted.
- **`XCursor.fromGlyph` approximates a font cursor as a filled cell**, so a client that uses
  `XCreateFontCursor` gets a solid rectangle under the pointer instead of an arrow. Fixing it means
  embedding the cursor font's glyphs.
- **Screen auto-size is not implemented**: the screen keeps the size `--geometry` gives it.
- The X11 protocol classes come from a fork of `moaxcp/x11-protocol`; its generator ignores the
  `number` on an `eventcopy`, which is why `ClientOutput.EVENT_NUMBERS` rewrites five event type
  bytes. The real fix belongs in the generator.

## Testing notes

`mvn test` runs the suite. It takes no X client and starts no display server of its own; the JavaFX
backend test does open a real window when JavaFX is available. On the author's machine Maven is at
`~/Documents/Tools/apache-maven-3.9.11/bin` and is not on PATH.

End-to-end checks that do need one:

- **The server's own frame dumps are the cheapest way to see what it drew**: run with
  `-Dxserver.dumpFrame=<prefix> -Dxserver.dumpFrames=N` and read `<prefix>-buffer/-image/-scene-N.png`.
  They need no screen-recording permission, and the `-scene` one is the only place the pointer sprite
  appears.
- A **bare-protocol client** drives the server without Xlib being installed: see
  `src/test/java/io/github/oyzh1994/xserver/test/RawXClient.java`, which handshakes, creates windows,
  sends GC-filled rectangles, reads `GetImage`, and moves the pointer through XTEST.
- **A regression test is only worth writing once it has been seen to fail without the fix.** Several
  of the tests here were rewritten after the first version passed against the bug (`pointerKnown`, the
  border clip, and the sprite-damage ordering all needed a sharper test before they meant anything).
