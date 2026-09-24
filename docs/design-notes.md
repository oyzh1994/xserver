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

## The host window is the client's window frame

Three things make a forwarded application look like a window rather than a picture of one, and all
three are work a window manager would do: the frame's title, its icon, and letting the user resize
the window by dragging the frame.

**The title and icon are the client's own.** `WindowIdentity` reads `_NET_WM_NAME` (UTF-8) or
`WM_NAME` off the client's top-level window, and `_NET_WM_ICON` for the icon; `AtomHandlers` reports
every property write to `XServer.windowPropertyChanged`, which rebuilds the identity only for those
three properties - a toolkit writes properties constantly, and rebuilding the identity for each of
those would copy a 128x128 icon every time. Everything else is the display tick's usual pattern: the
session holds the new identity, the tick notices it is not the one it already applied and calls
`DisplayBackend.setWindowTitle`/`setWindowIcon`. The identity is compared **by reference**, so "a new
value" and "the same value" are told apart without comparing pixel arrays.

Which window is the one the frame is named after: the **largest mapped top-level window**, not the
first. A toolkit's first window is often a 1x1 one it keeps for itself, and override-redirect windows
are left out entirely - a menu has no title of its own, and an application that retitles itself does
not retitle its menus. Measured against EasyShell: `_NET_WM_NAME` arrives as UTF-8 and the Chinese
title survives, and GTK publishes several icon sizes of which the largest under 256 pixels is used.

**Dragging the frame resizes the client's window.** The JavaFX window is resizable, and a change of
its content area that the client has not already been told about is a resize the user made
(`JavaFxBackend.isUserResize`). The backend only records it - it runs on the toolkit's thread, where
taking the server lock would deadlock against the tick waiting on the toolkit - and the tick applies
it: the client's top-level window is resized through the ordinary configure path
(`WindowHandlers.resizeWindow` into `applyConfigure`), so the client gets the same `ConfigureNotify`
a window manager sends, and the area the window stops covering is handed back to what is under it.

**A drag is measured in what the frame can show, not in how big the client's window is.** The two are
not the same number, and both differences were reported as bugs before they were fixed:

- A window that hangs off the screen is showing less than its own size. EasyShell's own window, measured,
  is `1300x700+-50+20` on a 1280x800 root: 1250x700 of it is visible. Dragging the frame *taller* used to
  set the window's width to the number the drag reported, which shrank it from 1300 to 1250 and changed
  the width of a window whose height was the only thing dragged - so the frame came out 1200 wide. The
  window size that shows `wanted` pixels is `wanted` plus the part hanging off the start of the screen,
  capped at the screen's far edge (`XServer.windowExtent`), and a dimension whose visible size did not
  change is left alone entirely.
- A drag past what the screen can show must stop at the most there is, and not overshoot and be pulled
  back: a window twenty pixels down the screen can be 780 pixels tall where the screen is 800, and
  asking for more than that lands at 780 rather than at the screen's 800 - which would otherwise be a
  frame that grew past what it could draw and snapped back to a size it had already left.

**The window is told how large it may be dragged** (`DisplayBackend.setMaxClientArea`, the server pushing
the screen less where the content starts on it). Without it the platform happily lets the user drag the
window past what the client's windows can fill, and the two failure modes are both visible: the frame is
left larger than its content, so the part it grew into shows the scene's black, and the next time the
server says how big the content really is the window is pulled back to it - away from the pointer that is
still dragging. This is what `WM_NORMAL_HINTS`'s maximum size does for a window manager, and it is applied
at the backend because the toolkit is what owns the window. Two details are worth keeping:

- The limit applies to the **window**, and the window carries the platform's decoration, so the
  decoration is measured and added back. Measured where the window is known to fit its content - just
  after `sizeToScene`, and once on show - rather than when the limit is applied: a window that has
  already been dragged too large reads as decoration that does not exist. On macOS it comes out as
  `0.0 x 28.0`, which is the title bar and no side borders; a wrong value here is not cosmetic, since a
  limit that is too small cannot be resized out of.
- It is pushed only when it changes, which is when the viewport's **origin** moves - the size the window
  may reach does not depend on how big the client's windows are, only on where they start.

**A menu does not size the frame it is opened in.** A context menu is an override-redirect top-level
window, and the frame is the union of the client's top-level windows, so counting menus as content
made the frame grow the moment one opened and shrink back the moment it closed: measured, the frame
went 700 to 740 and back for a menu that reached the bottom of the screen. A window manager sizes the
frame to the client's *window* and lets a popup be clipped by the screen; this server is the same rule
with the frame standing in for the screen (`XServer.contentBounds`). The cost is real and worth
knowing: a popup that extends past the client's window **is** clipped at the frame's edge now, where
before the frame grew to show it. Stretching the frame to fill the screen removes it - a popup inside
the screen then fits inside the frame - which is also the gesture that made the popups reach the edge
in the first place. A client that has mapped *nothing but* popups is still sized to them, or it would
have no size at all.

Two platform details were forced by measurement rather than design:

- **The scene root is a `Pane` and the canvas is unmanaged.** A `StackPane` centres what it holds,
  which is invisible while the canvas is exactly the size of the scene - and wrong the moment the
  user drags the window, because centring shifts the canvas by half the difference and every
  coordinate this backend reports is measured from the canvas's top-left. A managed canvas also
  imposes its size as the window's minimum: a 1200 pixel canvas stops the window ever being made
  narrower, so shrinking would simply not work.
- **A resize the backend makes itself is not reported back.** The backend sizes the window to the
  client's window whenever the viewport changes, and that also changes the content area; without
  `adjustingWindow` and the "already told" test, each of those would come back as a request to resize
  the client to the size it already has, for ever.

The client is free to refuse - a window with a fixed size keeps its size - and the frame is then
simply larger than what it frames.

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

## A screen bigger than the window looks

`--scale N` (default 1) tells clients about a screen N times the pixels across, for the same physical
size, so that a client which scales itself by the reported resolution draws at the resolution of a
HiDPI display instead of being enlarged to it. `--geometry` stays the size the window looks; the
screen, the framebuffer and the root are `N` times that in pixels, and `Screen` reports the physical
size from the *logical* size so the resolution comes out at `96N` - measured with `xdpyinfo`:
`--geometry 760x480 --scale 2` gives `1520x960 pixels (201x127 millimeters)` and `192x192 dots per
inch`.

The host window then does the one thing that makes it worth anything: it lays its canvas out at the
*logical* size and blits the framebuffer into it scaled by `1/N`, so one framebuffer pixel lands on
exactly one device pixel. Everything that talks in framebuffer coordinates stays in them - the
viewport, the damage rectangles, the sprite - and only the two edges convert: `logical()` on the way
out to the window, and `screenCoordinate`'s multiplication by `N` on the way in from it. A drag is
reported in framebuffer pixels, because that is what the client's window is resized in, and the
window's own maximum size is converted back the other way.

Why it is off by default: a client that ignores the resolution gets a screen twice the size and lays
itself out exactly as before, which on a HiDPI display means a window in the corner at half the
intended size. Measured with termora (a Swing application, so AWT draws it): it kept its 1x interface
on a 192 dpi screen until it was started with `-Dsun.java2d.uiScale=2`, and then its icons came out as
sharp as they do running natively - the same application whose icons were blank blocks before the A8
mask below was fixed, so the two together are what makes it look right.

## A picture follows its window

A RENDER picture over a window carries the translation from its own coordinates to the framebuffer,
and for a window that translation *is* the window's position. Reading the position once, when
`CreatePicture` builds the picture, is wrong for every window that is placed after it is created -
which is how AWT opens a dialog: it creates the window at the origin of the screen and only then
configures it where it belongs. The stale position is not only used to address the pixels; it is also
the point the window's clip is tested against, and once the two disagree the writes are not misplaced
so much as refused, all of them: the client composites a dialog onto its window and the framebuffer
keeps whatever was there.

Measured with termora, whose keymap settings put this on screen as "some dialogs are grey": the 消息
message box is 305x130, created at (0,0) and configured to (497,335), and the composite of its
contents reported

```
composite onto window 0x200071 at (0,0) 305x130: 0 pixels written, 39650 blocked;
picture origin (0,0), window at (497,335), visible [Rectangle(497,335,305,130)]
```

- every pixel refused, which is why the dialog showed its background - painted by `ClearArea`, which
  works in absolute coordinates - and none of its text, icon or button. `Picture.refreshClip()` now
  re-reads the position along with the clip at the start of each request, the same place and for the
  same reason the clip is renewed, and `PictureOriginTest` pins the two halves of it (a write and a
  read after the window moved).

The same log line stays in `Compositor` as the diagnostic for this class of problem: it is the only
place that says a client's drawing was *seen* and yet went nowhere, and printing the picture's origin
next to the window's position is what named the cause here. A composite over a window that is refused
entirely is otherwise indistinguishable from a client that drew nothing.

The core protocol never had the bug, because `DrawHandlers.Target` asks the window for its position
per request; only the picture path cached it, which is why everything except a moved window's
*contents* - its background, its border, its event handling - behaved.

## What a window leaves behind when it goes

A window's own visible region is its rectangle minus its children - a parent does not draw over its
children - and for the shape every toolkit gives a toplevel or a dialog that comes out **empty**: the
frame's content window covers the frame completely. That is the right answer for drawing and for
`Expose`, and the wrong one for "what does this window leave behind", which is what an unmap, a
destroy, a move or a restack has to hand back to the windows underneath.

Measured with termora: closing the settings dialog sent `UnmapWindow` and then `DestroyWindow`, the
window really was gone from the tree - and its picture stayed on the screen, because the region handed
back for repainting was the frame's own, which is nothing at all. The symptom the user reported was
that the close button "needs two clicks": the first click closed the dialog, and the picture it left
behind was what they went on aiming at, so the second click - the one whose hover made the client
repaint that area - looked like the one that took effect. The same shape explains the two others they
saw: a dialog's confirm button closing a dialog, and a popup dismissed by clicking outside it.

`XWindow.coveredRegion()` is the other region: the rectangle including its border and its children,
minus what is stacked above it - the area the window is *seen* in. `WindowHandlers.coveredRegionOf` is
what the code asks wherever the question is what a window leaves behind: `unmapWindow`,
`applyConfigure` (a moved or resized window hands back where it was, so a dragged dialog no longer
leaves a trail) and `circulateSubwindows`. `visibleRegion()` stays what drawing and exposure are
clipped to, which is why the two are not one method.
`WindowObscuringTest.unmappingAFrameGivesBackTheAreaItsContentWindowCovered` pins it and fails without
the fix.

## A depth 8 drawable holds bytes, not colours

A depth 8 pixel is stored as the byte the client sent, unconverted, whether or not the drawable has a
visual. Mostly it is not a colour: a toolkit rasterises its antialiased shapes and glyphs into a depth
8 pixmap whose bytes *are* coverage, and composites a colour through it. Reading that byte as a colour
through the visual destroys six of its eight bits. This server offers a depth 8 visual of the usual
3-3-2 layout, so each byte came back as the blue field of its own expansion - coverage modulo four,
which reaches the picture as 0x00, 0x55, 0xaa and 0xff and nothing in between.

Measured against Xvfb on the same application (termora again): its toolbar icons drew with exactly
three colours between the icon colour and the background, where Xvfb's - the same client, the same
requests - drew twenty. On screen that is the difference between a flat, bold icon and the one the
application is supposed to show, and the same loss hits every antialiased glyph beside it. It is
invisible to `xdpyinfo`-level inspection: the mask *picture* is alpha-only on both servers, so the
format is right and only what the pixmap holds is wrong.

Why Xvfb is right by luck rather than by effort: its screen is 24 bit, so it has no depth 8 visual at
all. A client that asks for a depth 8 pixmap there gets one with no visual attached, and the byte is
simply kept. Matching that exactly would mean dropping the depth 8 visual from the screen, which also
takes away depth 8 *windows*; keeping the byte and decoding it through the format on the way out
(`Picture.stored`, whose alpha-only and depth 8 pictures go through their format's masks) leaves both
working. `ImageCodecTest.depthEightPixelsKeepAllEightBitsOfTheirValue` pins the storage and the
round trip.

The core protocol's `PutImage` was the only path that converted, and `packed` on the read side was the
only one that converted back; everything else already treated a depth 8 store as raw. The log line
that found it is `DrawHandlers`' `PutImage landed: ... values=`, which prints the distinct bytes a
small image put down: three values where a mask should have had a gradient is what named the cause.

## How close the picture is to a real server

Measured against Xvfb on the same application, screenshotted from both and compared pixel for pixel
(this is the only test of "does it look right" that does not depend on somebody's eye):

- **99.5% of the window is identical** - 907497 of 912000 pixels. Of the rest, 2745 differ by one
  unit in a channel, 343 by two to sixteen, and 1415 by more.
- The **one-unit class is left alone on purpose**. It is the blend arithmetic: this server rounds,
  and Xorg's fixed-point composition truncates, so a value that should be 0x74 can come out 0x74 here
  and 0x73 there. Nothing is more correct about being off by one in Xorg's direction, and it is
  invisible - but it is the reason a byte-for-byte comparison against a reference server will never
  be clean, and why the interesting question is where the *larger* differences are.
- The **larger differences that used to be confined to the top fifty rows** of that application's
  window - the title bar and its icons - are **gone**: they were the depth 8 pixels being read as
  3-3-2 colours, which is written up above. The toolbar strip now comes out with a worst channel delta
  of **2**, 936 of its 9280 pixels differing by one unit and none by more, where before a fifth of them
  differed by up to 232. What still differs inside the window's own rectangle is the one-unit class
  plus, along its edge, the root colour this server paints and the border it draws - both deliberate.
  The measurement only means anything with both applications in the same state: the first version of
  this comparison had the pointer parked over the toolbar in one capture and not the other, which
  lights up hover states and reads as a large, entirely imaginary difference.
- **Scan for a shift before believing a rendering difference.** A whole-window scan over offsets of
  +/-4 pixels is cheap and says whether the two renders disagree in place or are the same picture moved,
  which are very different bugs.

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

**Was a first-level menu that is left and re-entered dismissed** by EasyShell, where it stayed up
against Xvfb. A scripted `right-click -> move out of the menu -> move back in` closed the menu every
time on this server and never on Xvfb, and the crossings involved matched the reference byte for byte
(window, detail, mode, state, coordinates and focus bit, in the same order), so the client was
reacting to something that was not in the crossing itself. Narrowed down by trial: suppressing the
enter-into-the-menu on re-entry kept the menu up, but the same crossing is what makes a submenu
active in the first place, so suppressing it left the submenu drawn but dead.

**It was the focus, and it is the one thing the crossings could not show.** The client was never told
it had the keyboard (see the next section), and every measurement in the paragraph above was taken on
a server that changed the focus and said nothing. With `FocusIn`/`FocusOut` delivered, the same
scripted gesture leaves the menu up **6 runs out of 6**, with the hovered item highlighting, where it
was closed in 3 out of 3 before. Two things had to be true for that, and the first alone was not
enough:

- The client has to be told the focus at all, which is what makes its window "focused" and lets its
  popup machinery run.
- **The window the pointer is already inside must still be told**, which is the ordinary case: the
  menu is opened under the pointer, and the window that takes the focus is the one the pointer is in.
  An early version treated that as "nothing changed" and sent nothing, which put the menu straight
  back to being dismissed; a reference server was measured sending the whole pair there.

The gesture is a scripted one (`/tmp/menu-outin2.sh`); a hand on a real mouse moves differently, so
the interactive case is worth re-checking rather than assuming.

Two deliberate deviations, both measured rather than reasoned:

- **Starting and ending a grab does not report the crossing pair Xorg sends** (LeaveNotify on the
  window the pointer is in and EnterNotify on the grab window with `mode` NotifyGrab, and the
  reverse with NotifyUngrab). It should: it is how a submenu becomes the window the pointer
  "belongs to" the moment it opens. With the pairs in place EasyShell's context menu was torn down
  within microseconds of opening in three runs out of three, against one in three for the same
  scripted gesture on this server without them; with them left out the menu behaves as it does
  against Xorg and the submenu works. Since the pointer's next move reports the same information,
  the pairs are the part that can be given up.
  **Measured again on 2026-09-23**, by accident: an uncommitted change had re-added the pairs, and
  the menu was then torn down 3 runs out of 3 where the committed server kept it up in 3 out of 3
  (the signal is the client's own `UnmapWindow` and `DestroyWindow` after the menu's `MapWindow`).
  The message that produced it is still in `InputHandlers.ungrabPointer`'s history: a pair was sent
  on **every** ungrab, and a menu grabs and ungrabs repeatedly while it is open, so the client was
  told "you left your main window and entered the popup" several times while the pointer sat still.
  A grab that ends without changing which window the pointer is in has nothing to report.
- **A popup is closed by an `EnterNotify` on it if the pointer has not moved.** This is the same
  measurement from the client's side, and it is what the leave-and-re-enter dismissal is made of:
  the server log shows the client sending `UngrabPointer` and unmapping the popup **one millisecond**
  after the `EnterNotify` that reports the pointer back inside the menu, with no request in between -
  it is reacting to the crossing itself. So the two deviations above are not timidity: every crossing
  that reports a window "entered" without the pointer having moved was measured to cost the menu,
  whether it is sent at the map, at the release of the button, or at the end of a grab.
- **A window that appears under a stationary pointer is reported as entered by the move that
  follows, not by the map that put it there.** Xorg sends the crossing at the map; doing that here
  tore the menu down as it was mapped in three runs out of three. A window that *goes away* under
  the pointer is reported at once, because the alternative there is a pointer pinned to a window
  that no longer exists and a next click that is reported against nothing.

## The focus is the client's to be told about

`FocusIn` and `FocusOut` are the only way a client learns it has the keyboard, and a toolkit is built
on being told: it moves the focus to a dialog and enables the dialog's fields when the dialog says it
arrived. This server changed the focus on `SetInputFocus` and sent nothing at all, which was recorded
here as a known gap for two sessions without a symptom attached to it. The symptom, when it finally
came, was from JavaFX: a `ContextMenu` opened from a `Canvas` "could not get the focus and could not
be clicked", where the same menu in the connection list worked.

Measured on that client, and it is worth re-measuring rather than re-reasoning:

- EasyShell **selects `FocusChangeMask`** (`0x6ba77f` on its main window - bit 21) and calls
  `SetInputFocus` **once**, for its main window, as it is mapped at startup.
- The server sent **zero** `FocusIn`/`FocusOut` for the whole session. The client asked to be told and
  was never told.
- The client worked around it app-side, and the workaround is the framework's own path: showing the
  menu from `ContextMenuEvent.CONTEXT_MENU_REQUESTED` is exactly what `Control` does internally
  (`Control.contextMenuHandler`: `c.getContextMenu().show(c, event.getScreenX(), event.getScreenY())`
  then `event.consume()`), so a `Canvas` - which is not a `Control` - has to do it by hand to get the
  same treatment. (Their remaining first-time-only failure was unrelated to any of this: the menu
  object had not been initialised yet when the first event arrived.)

What is implemented now, in `EventDispatcher.deliverFocusChange`, called from the only two places the
focus moves - `InputState.setInputFocus` and the revert when the focused window is destroyed:

- **The window losing the focus gets `FocusOut` and the window gaining it gets `FocusIn`**, each to
  whoever selected `FocusChangeMask` on it. One mask bit covers both events, not one each.
- **The detail is the crossing lattice**, the same helper (`crossingDetail`) rather than a second
  copy: how the window on the other side of the change relates to this one, which is how a toolkit
  tells a focus that moved inside its own tree from one that left it entirely. Two details exist only
  for focus: `NotifyPointerRoot` (6) when the other side is the root, and `NotifyDetailNone` (7) when
  it is nowhere at all.
- **A change that is not a change is not reported**, and neither is a window reported as losing a
  focus it did not have - which is what keeps a client from being told the same thing repeatedly.
- **The mode is always `NotifyNormal`**, and `FOCUS_CHANGE` is not in `EventMasks.PROPAGATE`, so these
  do not propagate. Both are deliberate and both are recorded below as the remaining gap.

The rules were taken from a reference server, not from the specification's prose: `/tmp/FocusProbe.java`
drives the same window tree and the same requests against Xvfb and against this server, prints every
focus event with its detail and mode, and the two outputs are diffed. That is what settled the things
prose does not: which windows are told, in what order, and with which detail.

- **Every window on the way in and on the way out is told**, not only the two at the ends. The window
  losing the focus hears first, then the windows holding it, innermost outwards; going the other way
  the holding windows hear first, outermost inwards, and the window itself last - so a toolkit can
  walk down into the window that gained the focus. The windows in between carry `NotifyVirtual` when
  the two ends are related and `NotifyNonlinearVirtual` when they are not.
- **A focus of the root is PointerRoot, and it is reported against the window the pointer is in**,
  with `NotifyPointer` - that window and every window holding it, on both the gaining and the losing
  side. It is the same window on both sides when the pointer is already in the window asking for the
  focus, and that is not a no-op: a reference server sends the whole pair, and treating it as one
  leaves the client that asked never told. That was the difference between a menu that is dismissed
  and a menu that is not.
- **`None` is a state of its own**, kept as null rather than folded into PointerRoot: nothing is told
  it gained the keyboard, `GetInputFocus` answers None, and `focusWindow` returns null so the keys
  have nowhere to go. Folding it into PointerRoot reports a focus the client did not ask for.
- **A window that stops being viewable gives the focus up, and reverts it the way the last
  `SetInputFocus` asked for** - to the nearest ancestor that is still viewable, to PointerRoot, or to
  nothing - whether it is the focused window itself or one holding it. A toolkit that unmaps a dialog
  instead of putting the focus back itself is relying on exactly this, and keyboard events aimed at a
  window nobody can see are keyboard events nobody handles. Destroying a window does the same thing.
- **`FocusOutEvent` is one of the five event classes the protocol generator numbers wrongly**, and
  `ClientOutput` already rewrites it to 10. Focus events therefore have to go out through the ordinary
  send path, not around it, or the client reads a `FocusOut` as something else entirely.

Two traps the probe fell into, both worth an hour of somebody else's time:

- **A request this server accepts may be one a real server rejects.** The first version of
  `RawXClient.setInputFocus` sent sixteen bytes; `xSetInputFocusReq` is twelve, and Xvfb answered
  every one of them with `BadLength` while this server read the length, handed the payload on and
  accepted it. A probe that only ever runs against this server would have gone on passing. `BadLength`
  is also what a probe should print rather than skip: the first version dropped errors silently and
  looked exactly like a server that was sending no focus events at all.
- **Known deviations from the reference, measured rather than assumed**: in the two transitions where
  a revert lands on PointerRoot, this server relates the chain to the root where a reference server
  relates it to the window the pointer is in (details `0/1` where it sends `3/4`, and the reverse in
  one other place). Sixteen of the eighteen probe steps match exactly; those two are in the corner
  where a `RevertToParent` fails and the focus falls through to PointerRoot, and no client has been
  seen to care. Written down so the next person does not have to re-derive them.

## The server generates the keyboard's repeats

A held key speaks once, and the run of characters that follows is the server's own work: a client is
told the key went down and is not expected to press it again. Worth writing down because it is easy to
assume the client does it - and the symptom of getting it wrong is not an error but a terminal that
deletes one character however long Backspace is held.

- The repeats come from the **display tick** (`XServer.pollAutoRepeat`), under the lock the tick already
  holds, once per frame: a key's first repeat `repeatDelayMillis` after the press, the rest every
  `repeatIntervalMillis` - X's own 660 ms and 40 ms (`xset q`'s "delay 660, rate 25").
- **One repeat per key per tick, never a catch-up burst.** A tick that was late - a stalled screen, a
  machine that slept - would otherwise deliver a run of presses at once, which a client prints as
  characters nobody typed.
- **A modifier does not repeat.** On a stock server that is XKB's decision, and a repeating Shift is a
  run of presses no toolkit expects.
- **A release stops it and a second press starts a fresh delay**, or a tapped key repeats immediately
  on the next tap.
- **`ChangeKeyboardControl`'s `AutoRepeatMode` is honoured** (one keycode, or every key when it names
  none) and `GetKeyboardControl` reports what was actually set rather than a blanket "everything
  repeats".
- The **delay and the rate themselves are XKB's** (`XkbSetAutoRepeatRate`), not the core protocol's, so
  a client that wants different ones cannot have them until item B1 of the backlog exists.

## The pointer's history

`GetMotionEvents` answers from a bounded ring per screen - 256 entries, Xorg's own
`MOTION_HISTORY_SIZE` - and it holds what the pointer *did* rather than what was delivered: a motion
swallowed by a grab, or made over another client's window, is part of it, filed under the window the
pointer was in at the time. The timestamps are the ones the events carried, so a client can compare
them with what it has already seen; the caveat is that the server stamps a motion when it *reads* the
request, so a range beginning a millisecond after the client's own clock reading can still contain one
(which is a test that fails one run in four).

## What a subscriber is told

XFixes lets a client ask to be told that a selection changed hands, or that the pointer moved onto a
different cursor, instead of polling. What the notification *says* is not in the specification in the
form the clients rely on, so all of it was measured - `tools/SelectionProbe` and `tools/CursorProbe`
drive the same requests against Xvfb and against this server, and their output is diffed. The two
runs agree event for event once the things a server assigns for itself are normalised away (opcodes,
atom ids, cursor serials), which is what makes the rules below worth writing down rather than
re-deriving:

- **A selection notification names the window the subscription was made on**, not the owner window
  and not the window under the pointer. That is the only thing that window is for: it says which
  screen, and it carries the event.
- **Three ways for a selection to change hands, three subtypes**, and the timestamps tell them
  apart. `SetSelectionOwner` reports the new owner and sets *both* times to now. An owner window
  being destroyed (`SelectionWindowDestroy`) or its client disconnecting (`SelectionClientClose`)
  reports **no owner** and keeps the time the selection was *taken* as the selection's own time -
  nothing changed hands, the selection lost its holder. A client that compares the two times to
  decide whether a notification is news is relying on exactly that.
- **Every `SetSelectionOwner` is reported**, including one that names the owner already there and one
  that gives the selection up to `None`. Both look like a no-op and neither is.
- **The subscription is a resource of the window it was made on**, and dies with it: destroying that
  window stops the notifications. A client that wants to go on hearing about the clipboard keeps the
  window alive, and a client that destroys it hears nothing more.
- **A cursor's `cursorSerial` names the cursor, not the change.** Moving the pointer off a window and
  back onto it reports the serial the cursor had the first time, because it is the same cursor - a
  reference server's serials go back down on the way back. What is counted is cursor *creation*, so
  the counter is assigned where a cursor is built, and the root's own cursor has one too.
- **The cursor event goes with the display, not with a client's drawing**: it is raised from the
  display tick, which is where the server knows what the pointer is over, and the first cursor the
  server can name after it learns where the pointer is is the baseline rather than a change.

A real server refuses every XFixes request from a client that never called `XFixesQueryVersion`
(`BadRequest`), which this server does not - measured, and deliberately left alone: it gains a client
nothing to be told off for it, and every real client asks first anyway. It shows up in a hand-written
probe, which is how this note exists.

## The host clipboard, on the X selection

Copying inside a forwarded application and pasting into a Mac application is the interaction a user
notices most, and it is not a copy between two places that hold data: the X side is a *lazy* selection
- the owner keeps the bytes and hands them over when a requestor asks - and the host side is a
pasteboard that holds whatever was last put there. `ClipboardBridge` is the thing in the middle, and
three of its four decisions are forced rather than chosen.

**Reading the platform pasteboard never happens on a request path.** Every X request runs while the
server lock is held, and a pasteboard read is a round trip to the platform's clipboard service that
can block; a bridge that read it while serving a `ConvertSelection` would stall every other client
whenever that service was slow. A poll thread (200 ms) keeps the text, and the request path reads the
copy. The bridge's own state is guarded by the *server lock* and nothing else, which is what keeps the
two threads from ever holding two locks in the opposite order - and the pasteboard calls happen
outside it, deliberately.

**The X-to-host direction has to be eager.** It is tempting to fetch from the client only when the
pasteboard is read, as the backlog's plan first said; macOS does not tell anybody that a pasteboard
was *read*, so there is no such moment to fetch on. When a client takes the selection the bridge asks
it for the data straight away and puts it on the host. The consequence is visible and should be
expected: copying inside a forwarded application replaces the Mac's clipboard, which is what every
bridge on a platform without a read notification does.

**The bridge owns the selection only while the host has something to serve.** With a picture on the
Mac's clipboard there is nothing an X client could be given that it asked for, so the bridge gives the
selection up rather than answer the paste with an empty string, and a client finds no owner - which
leaves what the user had alone instead of wiping it. And a change to the pasteboard that the bridge
did not make *does* take the selection over from a client that holds it, because otherwise the client
would answer the next paste with what it copied before the user copied something else on the Mac.

**What is not bridged, and why**: `PRIMARY`, because a pasteboard is not a three-button pointer and a
second invisible clipboard nobody can reach is worse than none; images and files, because there is no
X11 target for them that a forwarded application would have asked for; and `INCR` when *serving*,
because the bridge is not a client and is not bound by a request size, so one write serves a property
of any size. `INCR` is honoured when *receiving*, since whether to chunk is the source client's
decision and not one this server gets to make for it.

Two things this cost an hour each, both worth knowing. **A toolkit asks for `TARGETS` first and gives
up if it is refused** - so an owner that serves `UTF8_STRING` and not `TARGETS` looks exactly like a
bridge that does not work, and finding that means looking at which target the client asked for, which
is why that is a debug line in `serve`. And **the shell's `pbcopy`/`pbpaste` were blocked in this
environment's sandbox while AWT was not**, so the host end of a live check goes through Java; the
verification that mattered was a real `java.awt` clipboard on the VM, which *is* the X selection and
the same path a Swing application's clipboard takes.

## Where a window was left

A user who arranges their forwarded windows once expects them to come back there, and nothing in the X
protocol carries that: the position belongs to the host window, which is the server's own. So it is
remembered between runs, in `~/.xserver/host-windows.properties` - the first state this server keeps
after it exits, and a plain properties file so that a user can read it and deleting it costs nothing
but the remembered positions.

**Keyed by `WM_CLASS`**, read off the same window the host window's title comes from. Not the title,
which is what the user is looking at and changes with the document: the class is the application's own
name for itself, and a client that publishes neither has nothing to be remembered by, so it is not
remembered at all.

**Written from the display tick, once the position has held still** (400 ms - the same settle the
viewport uses). The move is reported by the backend, which runs on the toolkit's thread where writing
a file is work done between the user's drag and the next frame, and a drag reports a position per
frame.

Two things this got wrong first, both worth knowing because both look like the feature not working:

- **The position a window *opens* at arrives through the same report a drag does.** Writing it out
  overwrites what was remembered with the very place the restore was about to change, so a client
  would never come back to where it was - measured happening on the first live run, which is why the
  first position of a session is adopted as a baseline rather than recorded.
- **The clamp belongs to the backend, not to the store.** A position remembered on a display that is
  no longer attached is a window the user cannot reach, so `setWindowPosition` keeps the window inside
  the usable area - measured: a remembered `-194,195` came up at `0,195`, with the store still holding
  what the user had left it at, so the intent survives the clamp.

The **size** is deliberately not remembered, and the reason is that the server already works that way:
the host window is sized to the client's windows and dragging its edge resizes the client's window, so
a client that cares how big it is remembers that itself and asks for it next time. A window opened
larger than its content is the failure mode the frame's maximum size exists to prevent.

## Deliberately not done

This is the list of things left out *on purpose*, with the measurement that settled them. The
wider list of what is not implemented yet - including everything a stock server has and this one
does not - is `backlog.md`, which is written to be worked through item by item.

- **`GetKeyboardMapping` follows what the display backends translate**, and letters are looked up by
  character (`Keymap.keycodeForLetter`), because US keycodes are not in alphabet order.
- **SHAPE's bounding shape does not clip drawing** - only the input shape is honoured - so a shaped
  window's corners are still painted.
- **`CreateGlyphCursor` against a font other than the cursor font has no outlines to draw** and falls
  back to a filled cell. The cursor font's own 74 shapes are embedded (`resource/CursorFont.java`,
  generated from `cursor.pcf` by `tools/gen-cursor-font.py`), which is what `XCreateFontCursor`
  actually asks for - so `xterm`'s I-beam and every client's arrow are the real ones now; a client that
  invents a glyph set still gets the approximation.
- **Screen auto-size is not implemented**: the screen keeps the size `--geometry` gives it. The host
  window is resizable, but a resize is therefore capped at the screen rather than growing it, and a
  client's window larger than `--geometry` is still clipped.
- **The focus does not follow the pointer.** With the focus on `PointerRoot` a server is meant to move
  it from window to window as the pointer moves and report each move; here the *keys* follow the
  pointer (`InputState.focusWindow`) but no events are generated for it, so a client tracking focus by
  event never sees it move. `SetInputFocus` is the only thing that produces `FocusIn`/`FocusOut`, and
  it covers what a toolkit actually does at startup and for dialogs.
- **A focus change made by a keyboard grab is reported as `NotifyNormal`.** The modes belong together
  with grabs, and this server does not let a keyboard grab move the focus.
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
