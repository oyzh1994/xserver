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

## Input is mapped through the viewport

The host window shows a sub-rectangle of the screen, so a point in the window is not a point on the
screen: the window's top left is the rectangle's top left. `JavaFxBackend` converted a scene point to
a screen point one for one and ignored that corner, while `SwingBackend` has always added it - so on
the JavaFX path every pointer position, click and scroll was reported as far off as the rectangle's
origin is from the screen's. Measured for a JavaFX client in per-client mode on a 1200x760 screen:
its 1300x700 toplevel lands at (-50,20), the viewport comes out as `x=0,y=20`, and every click was
reported twenty pixels above where it happened. The visible symptom is a menu that closes as soon as
the pointer is moved: the position the client is told about leaves the menu's rectangle twenty pixels
before the pointer does, and the crossing events that say so - see above - then dismiss it.

Both backends now add the viewport origin and clamp to the screen
(`JavaFxBackend.screenCoordinate`); the server logs the viewport it opened for a session
(`opened a window for display :N showing java.awt.Rectangle[...]`) at INFO, which is how to tell
whether a given setup is affected.

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

## Grabs and crossing events

EnterNotify and LeaveNotify are the only way a client learns which of its own windows the pointer is
in, and a menu is built entirely on them: its submenu is a separate window that appears beside the
menu the pointer is hovering, and the EnterNotify that says the pointer arrived inside it is what
makes the submenu highlight its items and accept clicks. Clicks and motion alone are not enough -
a toolkit routes them by the window it last saw the pointer enter.

This server used to deliver no crossing events at all while a pointer grab was held, and a menu
holds a grab whenever it is open. The submenu was drawn and could not be used: nothing in it
highlighted (measured), and a press inside it was read as a click outside the parent menu, which
dismissed the whole menu instead of activating the item.

What a real server does, measured against Xorg for the same gestures request for request, and what
is implemented here:

- **A grab that reports events to itself confines crossings to the grab window's tree.**
  `GrabPointer` with `owner_events` false takes the pointer over: the window the pointer leaves
  still hears its LeaveNotify, and a window outside the grab window's subtree hears nothing. The
  implicit grab a press starts confines the same way, which is why a window that merely appears
  under a dragged pointer is not "entered" by it.
- **A grab with `owner_events` confines nothing.** The grabbing client goes on receiving the
  crossings of its own windows. A menu that grabs the pointer on its main window depends on this:
  confining those would leave it never learning that the pointer reached one of its own popups.
- **A grab confines crossings while a button is down.** The implicit grab a press starts pins the
  pointer to the window it landed on, and crossings are confined to that window's subtree until the
  button comes up - which is why a popup a menu maps under the pointer mid-press is not "entered"
  until the button is released. The reference server was measured doing exactly that.
- **An event for a window that is not the grabbing client's goes to the grab window.** A motion or a
  click that lands on the root, or on another client's window, while a grab with `owner_events` is
  held is reported against the window that holds the grab. Without it the motion that takes the
  pointer off a menu goes nowhere at all, and a click outside it - how a menu learns to close - is
  never delivered.
- **A crossing between the grab window and a window outside it is reported as a move inside the
  grabbing client's tree** (`NotifyAncestor` rather than `NotifyNonlinear`), and the focus bit of a
  crossing is set when the focus window is the event window or inside it, with `PointerRoot` counting
  as the window the pointer is in. Both are what the reference server sends.
- **An explicit grab supersedes the implicit one.** A menu that pops up under a held button is the
  case: the press pins the pointer to the window underneath, the client then grabs the pointer for
  the popup it just created, and the release has to be reported against the popup. Reporting it
  against the window the press landed on is a release the menu reads as a click outside itself.

Open issue, not explained: **a first-level menu that is left and re-entered is dismissed** by
EasyShell, where it stays up against Xvfb. A scripted `right-click -> move out of the menu -> move
back in` closes the menu every time on this server and never on Xvfb. The crossings involved match
the reference byte for byte (window, detail, mode, state, coordinates and focus bit, in the same
order), so the client is reacting to something that is not in the crossing itself. Narrowed down by
trial: suppressing the enter-into-the-menu on re-entry keeps the menu up, but the same crossing is
what makes a submenu active in the first place, so suppressing it puts the submenu back to being
drawn but dead. What is left is that the client is told the pointer "entered" the window that holds
its grab (the toplevel) as the pointer moves off the menu, and a later enter into the menu is then
fatal - the reference server sends that enter too, which is why this is unexplained rather than
fixed.

Two deliberate deviations, both measured rather than reasoned:

- **Starting and ending a grab does not report the crossing pair Xorg sends** (LeaveNotify on the
  window the pointer is in and EnterNotify on the grab window with `mode` NotifyGrab, and the
  reverse with NotifyUngrab). It should: it is how a submenu becomes the window the pointer
  "belongs to" the moment it opens. With the pairs in place EasyShell's context menu was torn down
  within microseconds of opening in three runs out of three, against one in three for the same
  scripted gesture on this server without them; with them left out the menu behaves as it does
  against Xorg and the submenu works. Since the pointer's next move reports the same information,
  the pairs are the part that can be given up.
- **A window that appears under a stationary pointer is reported as entered by the move that
  follows, not by the map that put it there.** Xorg sends the crossing at the map; doing that here
  tore the menu down as it was mapped in three runs out of three. A window that *goes away* under
  the pointer is reported at once, because the alternative there is a pointer pinned to a window
  that no longer exists and a next click that is reported against nothing.

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
