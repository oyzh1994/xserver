---
name: xserver-open-bugs
description: Rendering bugs still open in the xserver repo after the Expose-coordinate fix
metadata: 
  node_type: memory
  type: project
  originSessionId: 20e98c73-c9b5-4cc8-9d2e-466cca178651
  modified: 2026-09-23T18:59:59.528Z
---

Fixed and verified in this repo (uncommitted as of 2026-09-22): `WindowHandlers.deliverExpose()` sent Expose events with **screen** coordinates instead of window-relative ones (a window at screen (102,602) was told to repaint from `(102,602)` inside a 200x198 window), which makes any client that clips its repaint to the exposed rectangle draw nothing — a black window that never paints. Proof: `xev -geometry 200x200+100+600` before `(102,602)`, after `(0,0)`. Also added a `-scene-N.png` dump next to the existing `-image`/`-buffer` dumps in `JavaFxBackend`.

Fixed later the same day (committed by the user as `5a4d856` / `4a7617c` / the obscuring change):

- **Cursor save/restore was stale**: `InputState` painted the sprite into the framebuffer and wrote the saved pixels back unconditionally, so a client that drew inside the sprite's rectangle had its work reverted by the next pointer move (a trail of patches). The sprite is now handed to the display backend as a `CursorSprite` and drawn over the presented frame, never into the framebuffer; the ARGB32 alpha that a JavaFX cursor needs is preserved in `VisualInfo.toArgb`/`ImageCodec`; and the host window hides the platform pointer so there is only one pointer.

  That last part first used `scene.setCursor(Cursor.NONE)`, which **was itself a bug**: on macOS NONE is a process-wide `[NSCursor hide]`, so the desktop lost its pointer once the pointer left the server window. Replaced on 2026-09-22 with a transparent `ImageCursor` plus an enter/exit swap - see [[javafx-cursor-none-hides-platform-pointer]].
- **Obscuring is now tracked**: `XWindow.visibleRegion()` (own rectangle minus the siblings above it at every level *and* its own children), a region-aware `Rasterizer`, `Picture` regions, and `visibilityChanged`/`repaintUnder` in `WindowHandlers` which repaint and `Expose` the area a window stops covering. `XWindow.clipRect()` still only intersects ancestors - the region is the new API.

Fixed on 2026-09-22, found with real clients off the VM (`xclock` black, `xeyes`/`xcalc` blank until the
pointer was dragged across them, and `xcalc`'s button labels missing even then):

- **A window mapped before its parent was never sent an Expose.** `WindowHandlers.mapWindow` snapshots
  the window's visible region, maps it, then re-maps its mapped children - but it read each *child's*
  region at that point, by which time the parent was already mapped and the child's region therefore
  looked unchanged. Nothing gained means no Expose, and a toolkit maps its children first and draws
  itself from the Expose the parent's map owes it, so the window stayed black. `mapWindow` now carries a
  `newlyVisible` flag down the recursion (the entry always passes true) so every descendant of a window
  that just became viewable is told its whole area is new. Verified against a real Xlib client: the
  server's request log had **zero** Expose events before and `xcalc`/`xclock`/`xeyes` render fully after;
  `ExposeOnMapTest` drives a real connection (child mapped, then parent) and fails without the fix.

Fixed on 2026-09-23 (**uncommitted**), the reason a right-click menu's submenu was drawn but dead:
**no crossing events were delivered while a pointer grab was held** (`InputState.movePointer` skipped
`deliverCrossing` whenever `pointerGrab != null`). EnterNotify is the only way a toolkit learns
which of its own windows the pointer is in, so the submenu never highlighted and a press in it was
read as a click outside the parent menu, which dismissed the menu. What is now implemented, each
rule measured against Xvfb with a raw-protocol probe (`/tmp/Probe.java`, `/tmp/MenuProbe.java`):
crossings are delivered while grabbed but confined to the grab window's subtree when the grab has
`owner_events` false (`EventDispatcher.deliverCrossing(..., inside)`), an `owner_events` grab
confines nothing, and an explicit grab supersedes the implicit one for event routing
(`InputState.grabbedWindow`), which is what makes the release of a menu that appeared under a held
button land on the popup. Two behaviours of a real server are deliberately **not** implemented
because they were measured to tear EasyShell's menus down within microseconds of opening (3 runs
out of 3 against 1 in 3 for the same scripted gesture without them): the crossing **pair** a grab
reports at its start and end (mode NotifyGrab/NotifyUngrab), and reporting a window that appears
under a stationary pointer at the moment it is mapped. Both are written up in
`docs/design-notes.md`; `GrabCrossingTest` pins the two rules that are implemented.

Still open, deliberately not changed:

**A popup that appears under the pointer was never entered (fixed 2026-09-23, end of the third
session).** The deviation the design notes record as deliberate - "a window that appears under a
stationary pointer is reported as entered by the move that follows, not by the map" - left termora's
override-redirect popups (measured: `Override Redirect State: yes`) **never entered at all**, because
the press that opens one holds the implicit grab, which withholds the enter, and nothing delivered it
afterwards. The app therefore never knew the pointer was inside its menu: nothing highlighted and a
click in it was read as a click outside. A reference server withholds the enter only *until the button
comes up*, which is now what happens here: `WindowHandlers.mapWindow` reports the crossing at the map,
`InputState` remembers what the client was *told* (`announcedWindow`, separate from `pointerWindow`,
which button events update silently) and `settlePointerWindow` delivers the owed enter when the
implicit grab ends. **The reason it was left out originally no longer holds**: with the focus work in
place, EasyShell survives the out-and-in gesture 3/3 and opens as before, where the same change tore
its menus down 3/3 before. Not verified against Xvfb for the popup gesture - worth doing before
trusting it further.

**SOLVED 2026-09-24 (uncommitted): "the close button needs two clicks" was a ghost, not a click.**
The click worked: termora's settings dialog sent `UnmapWindow` on the *first* click and its window
really went away. What stayed was its **picture** - the area it covered was never repainted, so the
user went on aiming at the dialog they could still see, and the second click (whose hover made the
client repaint) is what made it disappear. Root cause: the region handed back when a window goes is
`window.visibleRegion()`, which subtracts the window's **own children** - and an AWT toplevel is a
frame whose content window covers it completely, so that region is *empty* and nothing was restored.
`XWindow.coveredRegion()` (own rect + border + children, minus what is above) is now what
`WindowHandlers` uses for `unmapWindow`, `applyConfigure` and `circulateSubwindows`;
`WindowObscuringTest.unmappingAFrameGivesBackTheAreaItsContentWindowCovered` pins it. Also explains the
same two-click report for a dialog's 确定 and for a popup dismissed by clicking outside it.

**How it was found - the log told us, and the lesson is about *what* to log**: with the user's own
clicks (their server, their hand) the trace showed `button 1 down at (957,141) -> 0x200032` for the
first click and `-> 0x200021` for the second - i.e. the dialog was already gone by the second click,
which meant the click was never the problem. What settled it was dumping the **framebuffer** after a
scripted single click (`-Dxserver.dumpFrame`, or `xwd -root` on a `--single-window` server) and seeing
that the closed dialog's pixels were still there, byte for byte. Two useful diagnostics came out of
this hunt and are kept: `JavaFxBackend.hostEvent` logs every press/release the host window itself
receives (before anything is done with it), which separates "the platform swallowed the click" from
"the client ignored it"; and `DrawHandlers`' `PutImage landed: ... values=` prints the distinct bytes a
small image put down.

**SOLVED 2026-09-23 (uncommitted): termora's "一团灰色" dialogs are a picture drawing at a stale
position.** `Picture` cached the window's position when the picture was created; AWT opens a dialog at
the origin of the screen and *then* configures it where it belongs, so the 消息 box stood at (497,335)
with a picture that still thought it was at (0,0). The same stale origin is what the window's clip is
tested against, so it is not a misplaced draw but a refused one - all 39650 pixels of it, which
`Compositor`'s diagnostic now prints (`0 pixels written, 39650 blocked; picture origin (0,0), window at
(497,335)`). The background came from `ClearArea`, which works in absolute coordinates, so the box
appeared with a hole exactly where its contents belonged. `Picture.refreshClip()` re-reads the position
along with the clip; `PictureOriginTest` pins write and read. The earlier hypothesis in this file
(override-redirect dialog clipped by `contentBounds`) was wrong. **Note the same diagnostic is how it
was found: a composite onto a window that is refused entirely is otherwise invisible in the log.** The
client-side facts that made the reproduction possible - termora is Swing, it runs on the VM, and XTEST
is scoped to the caller's own screen - are in [[xserver-testbed]].

**How close the output is to a real X server (measured 2026-09-23).** Shooting the same application
on both servers and diffing: **99.5% of the window identical** (907497 of 912000 pixels); 2745 differ
by one unit in a channel, 343 by 2..16, 1415 by more. The one-unit class is **blend rounding - ours
rounds, Xorg's fixed-point composition truncates** - and is deliberately kept, since neither is more
correct and it is invisible. The 1415 larger ones sit **only in the top ~50 rows** (title bar and its
icons), are not a shift (a +/-2 pixel offset scan finds nothing better), and are *not* explained: the
composites that draw them are 1:1 masked `Over` with the nearest filter, and the mask's coverage was
confirmed in the low byte by the server's own `PutImage landed` diagnostic. Next step if it matters:
diff the mask pixmap itself against a reference render.

**The user's "非常糊" was the Retina display, not the rendering.** The Mac is a 3024x1964 Retina panel
and JavaFX reports `scale 2.0x2.0`: a 1x framebuffer drawn through a canvas is enlarged 2x by macOS,
so the window is soft however correct the pixels are. Fixed by the `--scale` option below.

**Fixed 2026-09-23 (third session): an A8 mask was read as an opaque colour.** termora's toolbar
icons and filled paths arrived as solid grey blocks; the same AppImage on Xvfb drew them correctly,
so it was ours. RENDER masks are **depth 8 alpha-only** pictures (the log now prints it:
`mask=pixmap(...)(depth 8 alpha-only)`, which is how it was identified) and a drawable's pixels are
held as `0xAARRGGBB`, where such a mask's *value* is its coverage - reading it as a colour channel
with an opaque alpha made every mask pixel fully covered, so everything composited through a mask was
painted as a solid rectangle of the source colour. `Picture.stored` now decodes through the format
when it `isAlphaOnly()` (the helper `PictFormat.toArgb` already existed and the glyph path already
used it, which is why text was never affected). `AlphaMaskTest` pins it, verified failing without the
fix. **SOLVED 2026-09-23 (fourth session, uncommitted): the "bolder/jagged icons" were depth 8 pixels read
as 3-3-2 colours.** `ImageCodec` converted a depth 8 wire pixel through the drawable's *visual* on the
way into the store, and this server offers a depth 8 visual of the usual 3-3-2 layout, so a coverage
byte came back as the blue field of its own expansion - coverage modulo four, i.e. 0x00/0x55/0xaa/0xff
and nothing between. The client had been sending smooth coverage all along (the masks are PutImage'd);
we threw six of the eight bits away. Measured: termora's toolbar icons had exactly three colours
between the icon colour and the background where Xvfb's - the same client, the same requests - had
twenty. Fixed by storing the byte (`ImageCodec.argb`/`packed`, and `Picture.stored` decodes a depth 8
drawable through its format's masks, so a colour format still shows colours);
`ImageCodecTest.depthEightPixelsKeepAllEightBitsOfTheirValue` pins it and fails without the fix. **The
toolbar strip now differs from Xvfb's by a worst channel delta of 2** (936 of 9280 pixels, all one
unit). The old hypothesis here - "the app chooses a different rasterisation path because our RENDER
advertisement differs" - was wrong: the client's requests were identical.

**Measurement trap, cost an hour**: two captures of the same application in *different UI states* look
like a large rendering difference. The pointer being parked over the window in one run and not the
other lights up hover states (the sidebar row drew blue in one capture and not the other), and a
*different root colour* - ours is #E8E8E8, Xvfb's is black - shows up as hundreds of large-delta
pixels along the window edge. Park the pointer in the same place and check for a shift before
believing any of it.

**The focus gap has client-side evidence now (2026-09-23, second session).** EasyShell **selects
`FocusChangeMask`** (mask `0x6ba77f` on its main window, bit 21 set) and calls **`SetInputFocus` once**,
at startup when its main window is mapped - and the server sent **zero** `FocusIn`/`FocusOut` in the
whole session. So a client that asks for the focus and explicitly subscribes to being told is never
told; JavaFX's rule is that a popup is focused only while it is visible *and* its owner window is
focused, and a window's focused state comes from glass receiving `FocusIn`/`FocusOut`. What the user
saw from this: a `ContextMenu` opened from a `Canvas` "couldn't get focus and couldn't be clicked".
They resolved it **app-side** by showing the menu from `setOnContextMenuRequested` - which is exactly
what `Control` does internally (`Control.contextMenuHandler` does
`c.getContextMenu().show(c, event.getScreenX(), event.getScreenY()); event.consume();`), so they gave
the non-Control `Canvas` the framework's own path. Their remaining "only the first time" failure was
**their own initialisation order** - the menu object was not built yet when the first event arrived;
initialising it up front fixed it. Nothing to do with the server.

**IMPLEMENTED 2026-09-23** (third session, uncommitted): `EventDispatcher.deliverFocusChange`, called
from `InputState.setInputFocus` and from the revert when the focused window is destroyed. FocusOut to
the window losing the focus and FocusIn to the window gaining it, to whoever selected
`FocusChangeMask` (one bit covers both events), with the **crossing detail lattice reused**
(`crossingDetail`) plus `NotifyPointerRoot` (6) and `NotifyDetailNone` (7). Verified against the real
client: its startup `SetInputFocus` now produces a `FocusInEvent` where the log previously showed
none, and the app still renders and behaves the same. `FocusEventTest` (5 tests) pins the details,
the no-change case, and the deliberate fold of `None` into `PointerRoot`.

**Still missing** (recorded in the design notes): the focus does not follow the pointer while it is on
`PointerRoot` (keys follow it, no events are generated), and a focus change made by a keyboard grab is
reported as `NotifyNormal` rather than Grab/Ungrab.

Also measured and worth not re-deriving: the **first and later openings of the same context menu are
protocol-identical** (same CreateWindow x2 / MapWindow / ConfigureWindow / MapWindow / GrabPointer
sequence, all grabs `granted`, the popup's X windows recreated every time rather than reused, and the
button release delivered to the popup both times). So any "the first time behaves differently"
symptom is not in this server's event stream.

0. **Passive grabs are recorded and never matched**: `GrabButton`/`GrabKey` land in
   `XServer.passiveGrabs` and nothing reads that list for matching, so a window manager or a
   popup-shortcut arming one never hears about the button or key. **`SetInputFocus` generates no
   `FocusIn`/`FocusOut`** either, so a client that waits to be told it has the focus (a dialog, a
   keyboard-opened menu) is never told, while keys are delivered to the focused window. Both were
   noticed while chasing the menu bug and left alone.

1. **SHAPE's bounding shape does not clip drawing** (only the input shape is honoured), and the visible region does not consult it, so a shaped window's corners are still painted.
2. **`XCursor.fromGlyph` approximates font cursors as a filled cell**, so a client that uses `XCreateFontCursor` (xterm, xclock) draws a solid rectangle under the pointer instead of the standard arrow. Fixing it means embedding the cursor font's glyph bitmaps.
3. **`xdotool` segfaults against this server** (`getmouselocation` aborts); a hand-written raw-protocol client does the same QueryPointer/WarpPointer calls fine, so something in a reply is malformed enough to make Xlib misread it.

Not a bug, seen while testing and worth remembering: a one-frame 100%-black screen can be a client's own `ClearArea` with an unset `background_pixel` (xclock does it on startup), and `xserver --display N` on a display whose unix socket/TCP port is taken exits loudly with `BindException: Address already in use` ("failed to start") rather than half-listening.

Fixed 2026-09-23 (**uncommitted**), the "context menu closes as soon as the pointer is moved"
report: `JavaFxBackend` mapped *scene* coordinates to X screen coordinates one for one, ignoring that
the window shows the viewport sub-rectangle of the screen - `SwingBackend` had always added the
origin, so the JavaFX path (the one that is actually used) was the odd one out. Measured for
EasyShell in per-client mode on a 1200x760 screen: its toplevel lands at (-50,20), the viewport comes
out as `x=0,y=20`, so every click and motion was reported 20 pixels above where it happened; with the
crossing events now delivered (see above), the client is told the pointer left the menu 20px before
it does, and the menu closes. `JavaFxBackend.screenCoordinate` is now the single conversion, with a
unit test in `JavaFxBackendTest`.

Round two on the same area (2026-09-23 evening, still uncommitted). The viewport input mapping is now
**verified end-to-end**, not just reasoned: with a real JavaFX window in per-client mode the server
logged `showing java.awt.Rectangle[x=0,y=20,...]`, a physical click at window point (100,100) was
reported as `(100,120)` after the fix and as `(100,100)` before - and the user confirmed the cursor no
longer sits 20px above the pointer. Three more rules were added, each measured the same way (raw
protocol probes against Xvfb, byte-for-byte): an event for a window that is not the grabbing client's
goes to the grab window (a motion off a menu was previously dropped); the focus bit of a crossing is
set when the focus window is the event window or inside it, with PointerRoot meaning "the window the
pointer is in"; and a crossing between the grab window and a window outside it is reported as
`NotifyAncestor`. The implicit grab a press starts confines crossings while a button is down, which
is why a popup mapped under the pointer mid-press is not entered until the button comes up.

**2026-09-23, second session - the dismissal is the client reacting to the crossing itself.** A
reproducible harness (`/tmp/menu-cycle.sh` starts the headless server on :92 plus EasyShell on the
VM, `/tmp/menu-check.sh` right-clicks and reports whether the client unmaps the popup,
`/tmp/menu-outin.sh` adds the leave-and-re-enter gesture) shows the client sending `UngrabPointer`
and unmapping the popup **1 ms** after the `EnterNotify` that reports the pointer back inside the
menu, with no request in between. So *every* crossing that reports a popup "entered" while the
pointer has not moved costs the menu: measured 3/3 torn down. The uncommitted batch in the working
tree at the start of that session (`EventDispatcher.deliverGrabCrossing` + `InputState.announceGrab`
/`announceUngrab`, saved as `/tmp/regression-batch-announceGrabUngrab.patch`) re-added the grab
crossing pair the notes above say is deliberately left out, on **every** ungrab - and a menu grabs
and ungrabs repeatedly while open - so the menu was torn down 3/3 at open where the committed server
kept it up 3/3. That batch was reverted; the tree went back to 92/92 green.

**SOLVED 2026-09-23 evening - and it was the focus.** The dismissal above is gone in the scripted
harness (6/6 survive, hovered item highlighting; it was 3/3 dismissed before), and the missing
`FocusIn`/`FocusOut` was the cause. Two things had to be true, and the second is what took the time:
the client has to be told the focus at all, **and the window the pointer is already inside must still
be told** - an early version treated "the window taking the focus is the one the pointer is in" as no
change and sent nothing, which put the menu straight back to being dismissed. `FocusIn`/`FocusOut` is
now modelled on a reference server (`/tmp/FocusProbe.java` diffed against Xvfb: Xvfb run with
`-listen tcp -ac` on the VM, display :98): the whole chain of windows on each side, innermost-first
out and outermost-first in, `NotifyVirtual`/`NotifyNonlinearVirtual` for the windows in between,
`NotifyPointer` for the chain under PointerRoot, `None` as a real third state, and the focus reverting
out of a window (or a window holding it) that stops being viewable, per `revert_to`. 16 of 18 probe
steps match byte for byte; the two that do not are a corner where a failed `RevertToParent` falls
through to PointerRoot, recorded in the design notes.

**Historical, for the trail**: a first-level menu that is left and re-entered was dismissed by EasyShell
(`right-click -> move out -> move back in` closes it every time here, never against Xvfb). The
crossings involved match the reference byte for byte - window, detail, mode, state, coordinates,
focus bit, order - so the client is reacting to something that is not in the crossing, and the hunt
stopped there. Ruled out by trial: suppressing the crossing that leaves the grab window keeps the
menu up but makes a submenu inert again (that crossing is what makes a menu "the window the pointer
is in"); suppressing only its Leave half keeps the submenu working and does not help; suppressing the
pair at the map is what kills the menu at open time. The next thing worth doing is getting this
client's *own* event stream off Xvfb - `/tmp/XProxy.java` is a byte-level X11 proxy built for exactly
that and it works for Xlib clients (`xwininfo` through it is fine) but EasyShell stops after its
first `QueryExtension` through it, for a reason never found.

Harness warning for whoever picks this up: scripted clicks have real effects on EasyShell (one of
them opened an SSH session in the test instance), so the app's state drifts during a measurement run
and menu coordinates from an earlier run go stale - restart the app (`/tmp/cycle92.sh`) before
trusting a coordinate-based check.
