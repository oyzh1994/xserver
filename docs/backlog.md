# Backlog: what this server does not do yet

This is the working list of everything this X server is still missing, written so that each item can
be picked up on its own: what the code does today, why it matters, how to build it, what has already
bitten somebody, and how to prove the result. It complements rather than repeats:

- `README.md` - what is implemented, and the limitations a user should know about;
- `docs/design-notes.md` - why the server is built the way it is, including the deviations that are
  deliberate and the platform traps.

**How to use it.** Every item has a stable id (`A1`, `C4`, ...) so a branch, a commit or a test can
name it. Tick the box in the item's heading when it is *done and verified* - verification means the
check under **Verify** was actually run, not that the code looks right. Items in
[§7 Deliberate deviations](#7-deliberate-deviations-do-not-fix-without-re-measuring) are **not** bugs;
do not "fix" them without re-measuring, because each one was measured to break a real client.

Sizes: **S** ≤ half a day, **M** 1-2 days, **L** a week or more, **XL** a project of its own.

## How this is meant to be worked

This file is a **hand-off to a new session, on a new branch**. Nothing here is in progress; each item
is waiting for somebody with a clean context to pick it up. The loop, per item:

1. Read the item's **Current** and **Traps** before opening the code - more than one of them records a
   change that was made, measured against a real client, and reverted.
2. Make a branch (or a commit) named after the id, e.g. `a7-auth-requires-credentials`.
3. Change the code, then **run the item's own Verify step** - `mvn test` alone is not a verification.
4. Add a regression test that fails without the fix. The design notes' testing section says why this
   matters here: several tests in this repo were first written so loosely that they passed against the
   bug they were meant to catch.
5. Tick the box in the heading and record what the verification measured - two lines now beat a
   re-derivation in three months.
6. Update `README.md`'s "Known limitations" and `docs/design-notes.md`'s "Deliberately not done" when
   they list the item: a fixed item still listed there is worse than no documentation.

A prompt that starts a fresh session on this, verbatim:

> Read `docs/backlog.md` and implement the next unchecked item, in the order of the §0 table unless I
> say otherwise. Work on a branch named after its id. Before changing anything read the item's
> *Current* and *Traps*; after changing anything run its *Verify* step and add a regression test that
> fails without the fix. Then tick the item and tell me what the verification measured.

---

## 0. All items at a glance

| id | item | area | size |
|----|------|------|------|
| E0 | finish the verification kit | engineering | S |
| ~~A7~~ | ~~`--auth` can be bypassed by offering no credentials~~ **done 2026-09-24** | security | S |
| ~~A3~~ | ~~font cursors are drawn as a filled cell~~ **done 2026-09-24** | correctness | S |
| A4 | `xdotool` segfaults against this server **- crash is at startup** | correctness | S-M |
| ~~A2~~ | ~~a bounding shape does not clip drawing~~ **done 2026-09-24** | correctness | S-M |
| ~~A11~~ | ~~no keyboard autorepeat~~ **done 2026-09-24** | correctness | S-M |
| ~~A10~~ | ~~`GetMotionEvents` returns nothing~~ **done 2026-09-24** | correctness | S |
| A12 | window gravity is stored but not applied | correctness | S-M |
| A8 | XFixes selection/cursor notifications are never delivered | correctness | S-M |
| A6 | a keyboard-grab focus change is reported `NotifyNormal` | correctness | S |
| A5 | the focus does not follow the pointer under `PointerRoot` | correctness | M |
| A1 | passive grabs are recorded but never matched | correctness | M |
| A9 | `IncludeInferiors`, backing store, save-unders are recorded only | correctness | M-L |
| A13 | `AllowEvents` freeze/thaw semantics | correctness | S |
| C1 | screen auto-size (grow the screen with the window) | host window | M |
| C2 | raise/activate the host window when a client window appears | host window | S-M |
| C4 | bridge the host clipboard to the X selections | host window | M |
| C6 | HiDPI automation (`--scale` without hand-editing the client's flags) | host window | S |
| C7 | remember the host window's position and size per client | host window | S |
| C3 | fullscreen / maximize / urgency from the client | host window | S-M |
| C5 | host ↔ X drag and drop (XDND) | host window | M |
| C9 | tray icons (`_NET_SYSTEM_TRAY`) in the macOS menu bar | host window | M |
| C10 | input methods: verify XIM, and decide about the host IME | host window | M |
| B2 | RandR | extension | M |
| B3 | Xinerama | extension | S-M |
| B1 | XKEYBOARD | extension | M-L |
| B7 | DPMS and the XScreenSaver extension | extension | S |
| E2 | continuous integration | engineering | S |
| E4 | per-client framebuffer memory | engineering | M |
| E5 | backpressure to a client that has stopped reading | engineering | M |
| E3 | performance: local blending, fewer copies | engineering | M-L |
| D3 | core-font fidelity | large | M-L |
| B4 | XInput2 | extension | L |
| B5 | DAMAGE / Composite / Present / SYNC (and real translucency) | extension | L |
| C8 | one host window per client toplevel | host window | L |
| E1 | the `eventcopy` generator fix in the protocol fork | engineering | S (in the fork) |
| D1 | session persistence and re-attach | large | L-XL |
| D2 | built-in remote transport (VNC / WebSocket / RDP) | large | L-XL |
| B6 | GLX / OpenGL | extension | XL |
| -- | MIT-SHM | **do not do** | see [B8](#b8-mit-shm--deliberately-not-worth-doing) |

---

## 1. Verification kit (do E0 first)

Every item below ends with a **Verify** step, and most of them need the same small tools and the same
two-machine test bed. **The kit is now in this repository**: the tools live in
`src/test/java/io/github/oyzh1994/xserver/tools/` (so `mvn -o test-compile` compiles them), the
two-machine harness is `tools/vm.sh` and `tools/bed.sh`, and `tools/README.md` says what each tool is
for, how to run it, which tools were **lost** when `/tmp` was cleaned and are still to be re-created,
and the four measurement rules that go with them. Read that file before running any comparison: two of
the rules exist because ignoring them produced an afternoon of chasing an imaginary rendering bug.

### [ ] E0. Finish the verification kit. **S**

Restored on 2026-09-24: `Drive`, `WinTree`, `Focus`, `Pixel`, `Xwd`, `Blocks`, `Colors`, `Shift`,
`Rect`, `Crop`, a reconstruction of `XProxy`, and a new `AuthProbe` (written for A7). `RawXClient`
grew a key driver, an auto-repeat setter and - worth more than the rest - a **reply sequence check**:
a reply that is not the one being waited for is now an error instead of a wrong value, which is how a
test that asked for an empty range once came back with the previous query's three events. Still missing and worth having (see
`tools/README.md`): a typed-string driver (`Keys`), a `GetImage` capture tool, and the raw-protocol
probes (`Probe`, `MenuProbe`, `PointProbe`) that the focus and menu measurements in the design notes
were made with. Also worth doing here: a single script that runs a whole comparison - start both beds,
drive the same gesture, capture both sides with the same tool, print the `Xwd diff` and `Blocks`
summary - so that "compare against Xvfb" is one command instead of six.

The kit, and what each piece is for:

| tool | what it does |
|------|--------------|
| `RawXClient.java` (already in the repo, under `test/`) | the base for all of them: handshake, create windows, GC-filled rectangles, `GetImage`, XTEST. Everything below is a variation on it. |
| `Drive` | XTEST driver: `M x y` motion, `P n`/`R n` press/release, `K kc` key, `W ms` wait. This is how a GUI is driven without xdotool. |
| `Keys` | types a string through XTEST (needs the keymap from `GetKeyboardMapping`). |
| `WinTree` | prints the window tree with geometry, class, map state, override-redirect, `WM_NAME`, `WM_CLASS`, `WM_TRANSIENT_FOR`. The per-client mode has no other way to see a client's windows from outside. |
| `Xwd` | reads an XWD file and either writes a PNG or diffs two of them (counts, worst channel delta, delta histogram). Written because **Python's PIL refuses the XWD that `xwd` writes on the VM**; the header is big-endian while the *pixels* are in the order named by header field 7 (LSB here), and the pixel data starts at `header_size + ncolors * 12`. |
| `Blocks` | a grid of per-block difference counts between two captures - where to look. |
| `Colors` | every distinct colour in a rectangle, most common first - how "the icon has three colours where Xvfb's has twenty" was read off. |
| `Shift` | difference cost per offset in ±4 pixels - tells a shift from a rendering difference before blaming rendering. |
| `Rect` | an ASCII map of a rectangle (darkness buckets) - how a ghost dialog was spotted. |
| `Focus` | `SetInputFocus` to an arbitrary window from a bare-protocol client, i.e. plays the window manager for a test. |
| `AuthProbe` | sends a connection setup with chosen credentials and reports the answer: the setup's vendor and screen count, or the refusal's reason. No arguments means "offer nothing". |
| `XProxy` | a byte-level X11 proxy: logs a client's request/event stream against a *real* server, which is the only way to compare what a client sends to Xvfb with what it sends here. |
| `Crop`, `Pixel`, `Diff`, `Compare` | small image helpers (Crop magnifies a region around a point). |

The test bed (as of 2026-09-24):

- The **server runs on the Mac**; clients run on the **Linux VM** at `192.168.22.147` and connect over
  TCP to `192.168.22.1:<display>` - or, for the reference, to the VM's own `Xvfb :98`.
- `ssh root@192.168.22.147`, password `user@147`. **`expect` hangs on the password prompt in this
  environment; use `SSH_ASKPASS`**:
  `SSH_ASKPASS=<script printing the password> SSH_ASKPASS_REQUIRE=force ssh -o PreferredAuthentications=password -o PubkeyAuthentication=no …`
- Long-running commands must be detached (`setsid nohup … < /dev/null &`) or `ssh` kills them when the
  session ends.
- The VM has `xdotool` (**which segfaults against this server** - see A4), `xwd`, `xwininfo`,
  `x11vnc`, python3+PIL, and no X server of its own except the `Xvfb :98` that stays running.
- `Xvfb :98` (1300x800x24) plus `x11vnc -display :98 -forever -rfbport 5900 -listen 0.0.0.0 -shared`
  is the **reference**: connect from macOS with `vnc://192.168.22.147` and a human can compare, or
  drive it with `xdotool` and capture it with `xwd -root` (both work there).
- The forwarded applications used so far: **termora** (`/root/termora-2.0.0-beta.16-linux-aarch64.AppImage`,
  a Swing/AWT terminal, allows one instance at a time) and **EasyShell** (JavaFX, from a previous
  project). Launch with `setsid nohup env DISPLAY=… ./AppImage > log 2>&1 < /dev/null &`.

Server-side diagnostics that cost nothing and answer most questions:

```
--log-requests                              every request, at DEBUG, into the logback file
-Dxserver.dumpFrame=/tmp/d -Dxserver.dumpFrames=3000
                                            one frame a second: d-buffer-N.png (framebuffer),
                                            d-image-N.png (what JavaFX was handed), d-scene-N.png
                                            (what the window shows - the only place the sprite is)
```
`JavaFxBackend.hostEvent` logs every press/release the *host window* receives before anything is done
with it, which is how "the platform swallowed the click" is told apart from "the client ignored it";
`InputState` logs `button N down/up at (x,y) -> 0x…`, which says which window the server delivered it
to; `WindowHandlers` logs which events a client selected.

**Measurement traps that have already cost hours** (they belong in the kit's README):

1. **Park the pointer in the same place, in the same state, before comparing two captures.** One
   capture with the pointer over the toolbar and one without lit up hover states and read as a large,
   entirely imaginary rendering difference. Two captures of the same application on two servers are
   only comparable when the app is in the same state.
2. **Check for a shift before believing a rendering difference** (`Shift`). "Same picture moved" and
   "different picture" are completely different bugs.
3. **`xwd` on the VM only sees the screen of the display it connects to** - in per-client mode a new
   connection gets a root of its own and *nothing* is visible; use `--single-window` for scripted
   captures, or the frame dumps in either mode.
4. **A comparison against Xvfb is a comparison against a *different* root colour** (Xvfb's is black,
   this server's is `#E8E8E8`) and against the window border this server draws: the window's own edge
   always differs, and that is by design.

---

## 2. A - Correctness: the code accepts a request and does the wrong thing

### [x] A7. `--auth` could be bypassed by offering no credentials. **S** - done 2026-09-24

**What it was.** `ConnectionSetup.authenticate()` read
`if (!server.options().authRequired() || authName.isEmpty()) return true;` - so a client that left the
credentials out of its handshake was accepted even though `--auth` was requested. Since the server
listens on every interface by default, that was the whole of the access control: the flag looked like
one and was not.

**What changed.** "Access control is on and nothing was offered" is a refusal (with a log line naming
the peer); an `-ac` server - the default - still takes anything, which is what every other test and
the author's own test bed rely on.

**Measured 2026-09-24** with `tools/AuthProbe` (new in the kit) against live servers:

```
no credentials, --auth on:   REFUSED: Authentication rejected
right cookie:                SETUP: vendor=xserver screens=1
wrong cookie (one byte off): REFUSED: Authentication rejected
no credentials, --no-auth:   SETUP: vendor=xserver screens=1
```

`client/AuthenticationTest` (6 tests) pins all four plus "an empty cookie is not the same as offering
none" and "an unknown authorization protocol is refused"; the bypass test fails without the fix
(`expected: <> but was: <setup>`).

**Traps worth keeping.** A client with no `~/.Xauthority` entry for that display offers nothing, and
the failure looks exactly like "the server is down", so the refusal is logged with the peer - check the
log before blaming the network. And `AuthProbe`'s own handshake bit twice while it was written: the
setup request's two length fields are read in the byte order the client *declares* (little-endian for
`'l'`), and a failure reply is an eight byte header with the reason after it, not two bytes - getting
either wrong reads as a *hung* handshake rather than a bad request.

---

### [x] A3. Font cursors were drawn as a filled cell. **S** - done 2026-09-24

**What it was.** `XCursor.fromGlyph` had no glyph outlines to draw, so it approximated the character
with "this cell is the shape": a client that called `XCreateFontCursor` - `xterm`, `xclock`, `xcalc`,
most Xlib applications - got a solid rectangle under the pointer instead of the standard arrow.

**What changed.** The X cursor font's bitmaps are embedded (`resource/CursorFont.java`, 74 shapes:
source bitmap, mask bitmap, hotspot, generated from `cursor.pcf` by `tools/gen-cursor-font.py`, which
is in the repository so it can be regenerated). `fromGlyph` composes them the way
`XCreateGlyphCursor` defines it: the source bitmap in the foreground over the mask character's bitmap
in the background, at the glyph's own hotspot - and the font argument is not consulted at all on that
path, because a client that asks for `XC_left_ptr` means the arrow every X server draws.

**Measured 2026-09-24.** `resource/CursorFontTest` (3 tests): the arrow leaves most of its cell
transparent and draws a shape in the foreground (a filled cell has no transparent pixels at all), the
hotspot is inside the bitmap, and a shape the table does not have still gets the old approximation.
The two arrow tests fail without the fix.

**Traps.** The font's copyright line is what makes embedding legal - "These glyphs are unencumbered" -
and the hotspot is the font's own convention (left side bearing, ascent), not the bitmap's corner.
Clients with *themed* cursors (GTK, Qt, JavaFX) never touch this path: they upload ARGB images through
`CreateCursor` and a pixmap, which already worked. `CreateGlyphCursor` with an arbitrary glyph set is
a different path again and still has no outlines.

**Verify (partly done).** In process, by `CursorFontTest`. Live, `xterm` over a forwarded display is
what would show it - an I-beam over the text area - but **the VM has no `xterm`** (only `xclock`,
whose cursor is the default arrow and does not take this path), so the sprite has not been looked at
over a real display yet. The sprite appears only in the `-scene-` frame dump: diff it against the
`-image-` dump of the same frame and the differing region is the sprite.

---

### [ ] A4. `xdotool` segfaults against this server. **S-M**

**Current.** `xdotool getmouselocation` aborts; a hand-written raw-protocol client issues the same
`QueryPointer`/`WarpPointer`/`GetInputFocus` and is fine. So some reply this server sends is well
enough formed for a lenient client and malformed enough for Xlib to misread - a wrong length, a wrong
`nitems`, or a field Xlib dereferences.

**Why it matters.** `xdotool` is *the* tool for scripting an X desktop; without it every test here
needs a hand-written driver (see E0), and any user who scripts their desktop hits a crash they will
blame on their own script.

**Leads so far (2026-09-24).** The crash is **not** in a reply xdotool parses: *every* subcommand
dies, including `getdisplaygeometry`, which needs nothing but the screen - so it happens while xdotool
starts up, in `xdo_new` or in what it requires of the display before dispatching. Checked on the VM
against a live server: `getdisplaygeometry`, `getactivewindow`, `search --name xterm`,
`getmouselocation`, `getwindowfocus` all come back with SIGSEGV (139).

**One thing was fixed on the way, and it is a real deviation either way**:
`InputHandlers.queryPointer` answered with the *deepest* window under the pointer, where the protocol
says the *child of the queried window* - a client uses that answer to name the window it points at,
and handing it a grandchild makes it query a window it never asked about. It now uses
`window.childAt`, which is the immediate child. Xdotool still crashes, so this was not the cause.

**Next step.** A backtrace (`gdb`/`lldb` on the VM, or a core dump - the VM does write one), or
`strace -f -e trace=network`, to see which call is last before the fault. Then `XProxy` in front of
**Xvfb**, extended to log reply *bodies*, to compare what a working session gets back with what this
server sends - `XProxy` prints requests and events only, and extending it is the first thing to do.

**Traps.** A `BadWindow` error in the middle of Xlib's own request sequence also looks like a
segfault, because the error arrives asynchronously and Xlib's default handler exits; check the server
log for `rejected the request` lines at the moment of the crash before assuming a malformed reply.

**Verify.** `xdotool getmouselocation`, `xdotool mousemove 100 100`, `xdotool getactivewindow`,
`xdotool search --name xterm` against a running server, all clean, and the suite still green.

---

### [x] A2. A bounding shape did not clip drawing. **S-M** - done 2026-09-24

**What it was.** Only the input shape was honoured, for hit testing. The bounding shape affected the
extents a client could query and nothing else: a shaped window's corners were painted with its
background, its children painted outside it, and (the half that is easy to miss) the window kept
*obscuring* its whole rectangle, so the corners it no longer covered were never given back to the
screen underneath.

**What changed.** `XWindow` carries its own bounding region (`boundingShape`, in window coordinates),
handed over by `ShapeExtension` on every shape change - the extension owns the shapes, but the region
code runs once per drawing request and has no server to ask. `visibleRegion()` intersects it for the
window and for each ancestor (so a child outside its parent's shape is clipped by the parent's), and
`subtractWindow` subtracts the *shape* rather than the rectangle where there is one, which is what
lets the corners go back to what is under them. `WindowHandlers.shapeChanged` does the two halves a
shape change owes, the same two a move owes: give back what it stopped covering (`repaintUnder`) and
ask the client to draw what it gained (`Expose`).

**Measured 2026-09-24.** `resource/ShapeClippingTest` (3 tests, in-process server, framebuffer
assertions): a shaped window takes the fill only inside its shape; a shape set *after* the window is
mapped hands the corner back; a child outside its parent's shape does not paint. All three fail
without the fix.

**Traps.** A shape is a subtraction from the window's pixels, not a clip on top of them - and the
border lives outside the content rectangle, so a shaped window's border is clipped by the shape too
(`outerVisibleRegion` gets it from the same walk). `SetWindowClipRegion` (XFixes) is a different
thing, a clip on drawing, and stays where it is.

**Left open here**: the hand-written `ShapeRectangles` request in `RawXClient` is refused by this
server as a bad request, so the tests set shapes in process. The layout matches the generated writer
(minor 1, then operation, destination kind, ordering, pad, window, offsets), so the next step is to
see which request the server actually decodes - `XProxy` in front of the server, or a debug line in
`ShapeExtension.handleRequest`.

---

### [x] A11. There was no keyboard autorepeat. **S-M** - done 2026-09-24

**What it was.** The server delivered the press and the release and nothing in between, while
`GetKeyboardControl` claimed every key repeats - so a held Backspace in a forwarded terminal deleted
one character, and a client that turned a key's repeat off was ignored.

**What changed.** The display tick (`XServer.pollAutoRepeat`, under the server lock, once per frame)
delivers the repeats: a key's first repeat comes `repeatDelayMillis` after it went down and the rest
every `repeatIntervalMillis` - X's own defaults, 660 ms and 40 ms (`xset q`'s "delay 660, rate 25"), as
constants in `InputState`. A release stops it, a second press starts a fresh delay, a **modifier does
not repeat** (XKB decides that on a stock server, and a repeating Shift is a run of presses no toolkit
expects), and a client's `ChangeKeyboardControl` with `AutoRepeatMode` is honoured - for one keycode or,
with no keycode in the value, for every key, and `GetKeyboardControl` now reports what was actually set
rather than a blanket "everything repeats".

**Measured 2026-09-24**, both ways:

- `input/AutoRepeatTest` (5 tests, bare-protocol client, XTEST, the display loop actually running):
  the delay is honoured and the interval is kept, a release stops it, a modifier never repeats, a key
  whose repeat was turned off (and back on) behaves, and - the end-to-end one - a key held for a second
  and a half delivers about twenty presses *on its own, from the tick*. With the tick's call commented
  out, that test fails with `saw 0` while the other four still pass, which is the split the item wants:
  the arithmetic is pinned separately from the delivery.
- Live, against a real client on the VM (termora, `--single-window`), one 1.6 s hold of Backspace
  through XTEST: **27 `KeyPressEvent` deliveries and 1 release**, against 1 and 1 before.

**What is still missing** is the rest of X's repeat vocabulary, and it is XKB's (B1): the *delay* and
the *rate* are only settable through `XkbSetControls`/`XkbSetAutoRepeatRate`, so a client that wants a
200 ms delay cannot have one, and `XkbSetDetectableAutoRepeat` - where the client generates the repeats
itself and only needs to be told when a repeat would have started - is not there either. Until then the
defaults are what every client gets.

---

### [x] A10. `GetMotionEvents` returned nothing. **S** - done 2026-09-24

**What it was.** The handler replied with an empty list, on the argument that a buffer per window is
waste for a request almost nobody makes. What that cost: a client that wants to tell a drag from a
click, or to draw a stroke with the shape it had, was told the pointer never moved.

**What changed.** `InputState` keeps a bounded ring of the last 256 motions per screen (Xorg's own
`MOTION_HISTORY_SIZE`), each filed under the window the pointer was in at the time and stamped with the
event clock, and `InputHandlers.getMotionEvents` answers from it, clipped to the window and to the
half-open time range the client asked for - compared as 32 bit values that wrap, the way X compares
`Time`.

**Measured 2026-09-24.** `input/MotionHistoryTest` (2 tests, bare-protocol client over TCP, XTEST
motion): three motions inside a window come back in order with their coordinates and usable timestamps;
a motion outside the window is another window's history; two overlapping windows each get their own;
an empty range answers none. Without the fix both tests fail (the first with `expected: <3> but was:
<0>`).

**Traps.** The history holds what the pointer *did*, not what was delivered: a motion swallowed by a
grab, or made over another client's window, is still in it. Times are the ones events carry
(`server.events().time()`), so a client can compare them with what it has already seen - but the server
stamps a motion when it *reads* the request, so a range that begins a millisecond after the client's
own clock reading can still contain a real motion, which is a test that fails one run in four (it did).
Pointer acceleration is still accepted and ignored: that stays a deliberate deviation, since the
platform's own acceleration is what the user feels.

---

### [ ] A12. Window gravity is stored but not applied. **S-M**

**Current.** `bitGravity`/`winGravity` are set, reported in `GetWindowAttributes`/`QueryTree`, and
never used: a resize does not shift the window's contents by its bit gravity, and a parent resize does
not move a child by its win gravity.

**Why it matters.** Small in practice - the default is `NorthWest` and almost every toolkit leaves it
there - but it is a documented part of the protocol, and the two gravity modes that *are* used in the
wild (`StaticGravity` for a window whose content must not move, `EastGravity`/`SouthGravity` for
things pinned to an edge) are exactly the ones a caller notices.

**Plan.** `bitGravity` applies to the window's own pixels on a resize: this server has no per-window
backing store (see A9), so the honest implementation is "the region that the gravity keeps in place is
not repainted by the server, the rest is exposed" - which, for `NorthWest`, is what already happens.
`winGravity` applies in `configure` when a *parent* is resized: move each child whose gravity says so
before computing `coveredRegion`, so the repaint logic sees the new positions.

**Traps.** Doing this wrong shows up as windows jumping on a resize - the reference is easy to check
with a two-window raw client against Xvfb (create a parent and a child with `EastGravity`, resize the
parent, print the child's `GetGeometry`).

**Verify.** The two-window raw client above, against Xvfb and against this server, same numbers.

---

### [ ] A8. XFixes selection and cursor notifications are never delivered. **S-M**

**Current.** `XFixesExtension` implements `SelectSelectionInput`/`SelectCursorInput` (it records the
selection) and never sends `SetSelectionOwnerNotify`, `SelectionWindowDestroyNotify`,
`SelectionClientCloseNotify` or `CursorNotify`; the class javadoc says so.

**Why it matters.** Clipboard managers, `xclip`/`wl-paste` bridges and cursor-theme tools subscribe to
these instead of polling the selection owner. Without them, a client that wants to know "the clipboard
changed" sees nothing.

**Plan.** In `AtomHandlers.setSelectionOwner`, deliver `SetSelectionOwnerNotify` to every client that
selected for that selection (also on owner window destroy / client close, the two other variants), and
`CursorNotify` from the cursor-display path in `DisplayBackend`/`XServer.tick`. The event numbers for
XFixes events are *not* in the extension's advertised range by accident - check `XFixesExtension`'s
registered event count (2) against the classes used, and remember the `eventcopy` bug in the fork
(E1) when the numbers look wrong.

**Traps.** Both are *events with a timestamp*; the XFixes notification carries the selection's timestamp
and must match `SetSelectionOwner`'s, or a client that compares them discards the event. Sending the
notification *before* the owner is updated makes the client query the old owner.

**Verify.** A raw client that selects for the CLIPBOARD and prints what it gets, with another client
taking ownership; compare with Xvfb, which implements the same subscription.

---

### [ ] A6. A focus change made by a keyboard grab is reported as `NotifyNormal`. **S**

**Current.** `EventDispatcher.deliverFocusChange` always reports mode `NotifyNormal`, including the
focus changes a keyboard grab causes; a reference server reports `NotifyGrab`/`NotifyUngrab` for those.

**Why it matters.** A toolkit that tracks the mode tells "my window lost the focus because somebody
grabbed the keyboard" from "because the user clicked elsewhere"; the difference is visible in menus
and in dialog activation policies. Narrow, but it is a documented deviation
(`docs/design-notes.md`, "The focus is the client's to be told about").

**Plan.** Thread the mode through `deliverFocusChange` from the two call sites that can be inside a
grab (`InputState.keyboardGrab`/`UngrabKeyboard`), and leave `NotifyNormal` everywhere else.
`/tmp/FocusProbe.java` (described in the design notes) is the harness that diffs details and modes
against Xvfb - re-create it as part of E0 if it is gone.

**Verify.** Same probe against Xvfb and here, byte for byte, with a grab in the middle of the sequence.

---

### [ ] A5. The focus does not follow the pointer under `PointerRoot`. **M**

**Current.** With the focus set to `PointerRoot`, keys are delivered to the window the pointer is in
(`InputState.focusWindow`), but no `FocusIn`/`FocusOut` are generated as the pointer crosses windows.
A client that tracks the focus by event never sees it move.

**Why it matters.** `PointerRoot` is the default state of a server with no window manager, which is
exactly this server's model, and "focus follows the mouse" is what some users want from a forwarded
desktop. It is also the state EasyShell/termora start in before anything calls `SetInputFocus`.

**Plan.** In the existing crossing path (`InputState.deliverCrossing` / `refreshPointerWindow`), when
the focus is `PointerRoot`, report the focus change that the crossing implies: `FocusOut` on the
window the pointer left, `FocusIn` on the one it entered, with `NotifyPointer` detail and the
outermost-first/innermost-first order the delivered-focus code already implements for real focus
changes. The rule to copy is in the design notes' focus section (which was measured against a
reference server).

**Traps.** This is the same class of change as the one that "tore EasyShell's menus down 3 runs out of
3" before the focus work landed - **re-measure the menu gesture** (`docs/design-notes.md` describes
the harness) before and after, and do not ship it if the menu starts being dismissed again. Two
details a reference server is picky about: the crossing pair must not be reported *twice* (once as a
focus change and once as a crossing), and a window that has the focus must not be told again when the
pointer re-enters it.

**Verify.** `/tmp/FocusProbe`-style diff against Xvfb for a sequence of pointer warps with the focus on
`PointerRoot`; plus the EasyShell/termora menu gesture, 6 runs, no dismissal.

---

### [ ] A1. Passive grabs are recorded but never matched. **M**

**Current.** `GrabButton`/`GrabKey` are added to `XServer.passiveGrabs()` and removed by the matching
`Ungrab*`; **nothing reads the list**, so a client that arms a passive grab - a window manager, a
toolkit's popup shortcut, a global-hotkey library - never hears about the button or key it asked for.
Active grabs, including the implicit grab a press starts, work.

**Why it matters.** It is the largest remaining hole in the input model, and it is what makes a
"no window manager" server unable to host clients that put a shortcut on the root window (a very
common way to get a global hotkey). The README lists it; it has never been used by the two
applications tested so far, which is why it has stayed open.

**Plan.** Match in `InputState`: on a button press, before delivering to the window under the pointer,
walk the pointer's *ancestor chain* looking for a passive grab on any of them (grabs are on a window
and fire for its descendants) whose button/modifiers/`event_mask` match - honouring the documented
rules: modifiers are an exact match after the "any modifier" combinations are expanded, the grab is
only taken if no other client has grabbed first (the *last* one armed wins for `GrabButton` with the
same modifiers), and `owner_events` decides whether the event goes to the grab window or to the window
under the pointer. Then take the grab (it is an active grab from then until the button is released)
and unfreeze the pointer if `pointer_mode` says `Async`.

Keyboard grabs are the same shape in `keyChanged`, with `keyboard_mode`, and with the extra rule that
a key press that matches a passive grab is *consumed* (the client that armed it gets the event, the
focused window does not).

**Traps.** `XGrabButton` with `AnyModifier` and `AnyButton` stores a wildcard - the protocol defines
several overlapping candidates and a strict "closest match" order; read the specification table
rather than inventing one. A passive grab on a window that is not mapped must not fire. The grab must
be released on the button release even if the grab window is destroyed in between (otherwise input is
dead until the next click - the same trap the implicit grab had). `XTestFakeInput` has to go through
the same path or a scripted test cannot see the behaviour.

**Verify.** A raw client arms `GrabButton(AnyButton|ButtonPress, owner_events=false)` on a window,
another client maps a window under the pointer; a click must be delivered to the grab window with
`NotifyGrab`/`NotifyUngrab` mode crossings (which is the pair §7 says is deliberately not sent for
*active* grabs - check what the reference does for a *passive* grab before copying that decision).
`GrabKey` on the root with `AnyModifier`, then a key press from XTEST.

---

### [ ] A9. `SubwindowMode.IncludeInferiors`, backing store and save-unders are recorded only. **M-L**

**Current.** `Picture.subwindowMode` is stored and never consulted (`Picture`'s javadoc says so): a
picture over a window behaves as `ClipByChildren` always, which is what toolkits ask for. The
`backing_store` and `save_unders` window attributes are stored and unused, and `XWindow.backingPixel`
with them.

**Why it matters.** `IncludeInferiors` is what a client uses to copy *the screen* rather than a window
- `xwd`, screenshot tools, `xcompmgr`-less composition, and some toolkits' "grab the parent's pixels"
tricks. Today they get the window's own pixels only, which for a frame whose content child covers it
is nothing at all. Backing store is a *performance* hint: honouring it would let a covered window keep
its pixels and answer an expose without the client repainting (which would also make the "restore what
a window covered" path cheaper and less dependent on the lower window's cooperation).

**Plan.** For `IncludeInferiors`, the pixel source of a window picture has to include its children: the
compositor's sampler reads from the framebuffer, which already *has* the children painted into it, so
the change is in the *clip*: `Picture.hasContent`/`isWritable` currently subtract children (via
`visibleRegion`); with `IncludeInferiors` they must not. Backing store is a bigger decision - it is one
buffer per window, and it changes what "obscured" means; the design notes explain why the server went
without one, so treat it as a design change, not a fix.

**Traps.** The two are independent: `IncludeInferiors` is per-picture (a picture attribute), backing
store is per-window. Writing into a window with `IncludeInferiors` must still be clipped by the window
rectangle, or a client can scribble over the whole screen. `GetImage` uses `plane_mask` and its own
rules, not `subwindowMode`.

**Verify.** `xwd -id <frame>` against a window with a child: the image must contain the child's content
(compare with Xvfb). For backing store: `xclock`, cover it, uncover it, and check with the frame dumps
that the client was not asked to repaint (`-Dxserver.dumpFrames` plus the request log).

---

### [ ] A13. `AllowEvents` freeze/thaw semantics. **S**

**Current.** `AllowEvents` is dispatched (`MiscHandlers`), and the modes' effect on a *frozen* device
has not been reviewed: the server never freezes the pointer or the keyboard on a grab, so the request
is close to a no-op (which for `AsyncPointer`/`AsyncKeyboard` is the right answer, and for the
`Sync*`/`Replay*` modes is not).

**Why it matters.** Games and remote-desktop clients use `SyncPointer` + `AllowEvents` to replay the
event that was queued while they were busy. Getting it wrong is invisible until one of them is used.

**Plan.** First *verify* (a raw client that grabs with `pointer_mode=Sync`, presses, and checks that
nothing arrives until `AllowEvents(SyncPointer)`), then implement freezing in `InputState`: a frozen
pointer queues events, and `AllowEvents` flushes them / replays the last one, per the specification's
table.

**Verify.** The raw client above, against Xvfb and here, event for event.

---

## 3. B - Extensions a reference server has

### [ ] B1. XKEYBOARD. **M-L**

**Current.** Not advertised. The keyboard is a fixed table (`Keymap`: letters, digits, modifiers) that
`ChangeKeyboardMapping` can overwrite; `GetModifierMapping` works; there is no layout switching, no
`xkbcomp`, no `XkbSetDetectableAutoRepeat`, and no `xset -kb`-style configuration.

**Why it matters.** A non-US layout cannot be selected (`setxkbmap` is a no-op against this server),
keyboard repeat cannot be delegated to the client (see A11), and toolkits that probe XKB to decide how
to translate keys fall back to the core table. GTK and Qt both fall back cleanly, which is why the
applications tested so far have worked.

**Plan.** A *minimal* XKB is a better first step than a faithful one: advertise the extension, answer
`UseExtension`/`GetState`/`GetMap`/`GetNames` with a single US-compatible map, and implement
`XkbSetDetectableAutoRepeat` (a flag that suppresses the server's autorepeat - which is what A11's
timer needs to consult) and `XkbSelectEvents` (deliver nothing, or the state notifications a client
asks for). That is enough for the two clients that matter and leaves the layout machinery for later.

**Traps.** XKB's wire format is the largest in the protocol; do not hand-roll the whole map - the
`x11-protocol` fork may not even have all the request classes. A client that gets `GetMap` wrong
crashes (Qt reads the keymap arrays by offset). Take the map layout from `xkbcomp`'s output for a US
layout against Xvfb and replay it.

**Verify.** `xdpyinfo -ext XKEYBOARD` lists it; `setxkbmap -layout us -print` does not error;
`xev -event keyboard` shows the same keysyms as against Xvfb; a held key still repeats exactly once per
interval with `XkbSetDetectableAutoRepeat` on (the client repeats it) and not at all when the client
asked for that.

---

### [ ] B2. RandR. **M**

**Current.** Not advertised. There is one screen of a fixed pixel size, reported through the core
protocol; nothing can tell a client the size changed, and a client cannot ask for a mode.

**Why it matters.** Browsers and Electron read RandR to decide the device pixel ratio and monitor
geometry; HiDPI-aware toolkits use it to pick a scale; and "the screen grows with the window" (C1)
needs a way to tell the clients that it did - RandR's `ScreenChangeNotify`/`CrtcChangeNotify` is it.

**Plan.** A minimal implementation: `QueryVersion` (1.6), `GetScreenResources`/`GetScreenResourcesCurrent`,
`GetCrtcInfo`/`GetCrtcGammaSize`, `GetOutputInfo`, `GetScreenInfo`, `GetMonitors` and the
`SelectInput`/notifications path, with one CRTC and one output that mirror the screen. Phases 1-4 of
RandR are mostly bookkeeping over "the screen is N x M".

**Traps.** The reply sizes are version-dependent and clients parse them positionally; take the exact
field sets from a capture against Xvfb (`XProxy`) rather than from prose. `--scale N` already changes
what the client is told - RandR must agree with the setup reply or a client computes a nonsense DPI.

**Verify.** `xrandr` on the VM prints one output at the right size; `xrandr --query` against Xvfb
diffed against this server; a C1 resize delivers `ScreenChangeNotify` to a client that selected it.

---

### [ ] B3. Xinerama. **M**/**S-M**

**Current.** Not advertised: every client sees one monitor, which is also what this server has.

**Why it matters.** AWT/JavaFX (and browsers) enumerate monitors through Xinerama or RandR when they
want to talk about more than one screen; with neither, a client has one screen and that is fine today.
It becomes relevant the moment the server hosts more than one host window (per-client mode already
does) or a multi-monitor host: the honest answer then is one Xinerama head per host window.

**Plan.** Advertise the extension, answer `QueryVersion` and `QueryScreens` with one head per screen
today (and per host window once C8 lands).

**Traps.** Head coordinates are screen coordinates, so a client may create a window at the origin of a
head that is not the root's - which in per-client mode means a window outside the viewport. Decide
whether that is "clipped" (today's behaviour) before advertising.

**Verify.** `xdpyinfo -ext XINERAMA` and a JavaFX/AWT application printing its screen list.

---

### [ ] B4. XInput2. **L**

**Current.** Not advertised; `xeyes` prints a warning about the missing `XInputExtension` and falls
back to core input, and that fallback works for every client tested.

**Why it matters.** Modern toolkits prefer XI2 for precise scrolling, touchpad gestures, tablet and
multi-touch input, and "raw" mouse motion (pointer-acceleration free). A client that gets none of it
loses gestures but still works, which is why this is L rather than the next thing to do.

**Plan.** XI2 is a large protocol (devices, classes, event masks, grabs, hierarchy, properties). A
minimal, honest version advertises one pointer and one keyboard device, implements `XIQueryVersion`,
`XIQueryDevice`, `XISelectEvents`, `XIGetClientPointer`, and delivers the XI2 versions of the core
events a client selects. Anything more (touch, tablets, barriers) is a separate item.

**Traps.** Core and XI2 events must not both be delivered to a client that asked for one of them -
toolkits that select XI2 *and* core input double-handle. The device ids and the `XIDeviceInfo` reply
layout are again positional; capture from Xvfb.

**Verify.** `xinput list` on the VM; `xev -event device` prints XI2 events; the applications still
receive input exactly once.

---

### [ ] B5. DAMAGE, Composite, Present, SYNC - and real translucency. **L**

**Current.** None of the four. The model is "every request draws straight into one framebuffer and a
revealed window is asked to redraw with an `Expose`" (README's "A revealed window is restored by
asking it to draw"). A 32-bit ARGB visual is advertised, but with no compositor a translucent window
simply draws its pixels opaquely.

**Why it matters.** A compositing window manager has nothing to build on; a translucent popup or a
window with a shadow cannot exist; a client that wants damage notifications (instead of `Expose`)
polls. It is also the prerequisite for a *cheap* C1 (resize the screen without asking everyone to
repaint) and for honest backing store (A9).

**Plan.** This is the architecture item: a per-window backing store, a damage region per window, the
`Damage` and `Composite` extensions over it, and a final composite to the framebuffer in the display
tick. Do it *after* C1/C2 if the goal is a usable forwarder - the payoff today is small for the two
applications tested, and the risk to the obscuring/expose machinery (which took three sessions to get
right) is large.

**Traps.** The `Expose` path stays: clients without `Damage` must keep being told to repaint. The
framebuffer is the only surface today, and `Picture`'s clip is computed from `visibleRegion()` per
request - a backing store changes what "visible" means for *reads* (a covered window has pixels again),
which is exactly the `IncludeInferiors` question in A9.

**Verify.** `xcompmgr`/`picom` running against this server; `xeyes` with a translucent background;
`xdpyinfo -ext DAMAGE -ext Composite`.

---

### [ ] B6. GLX / OpenGL. **XL**

**Current.** Not advertised: a client that needs OpenGL gets nothing (browsers with WebGL, Blender,
`glxgears`, games).

**Why it matters.** It is the last big class of applications that cannot run here at all. It is *not*
small: direct rendering needs a driver talking to the same GPU the host window is on; indirect GLX
(the old, slow path) still needs a GL implementation on the server side and the `GLX` extension's
protocol.

**Plan.** If this is ever done, indirect GLX over a software rasteriser (Mesa's `llvmpipe` through
JNI or an offscreen EGL context) is the only realistic route, and it needs a decision about who
composites the GL output into the framebuffer. Treat it as its own project with its own proof of
concept before promising anything; the realistic first step is "advertise GLX with a single
`GLXVisualConfig` and no direct rendering" and see which clients degrade gracefully.

**Traps.** Do not advertise GLX before it works: a client that sees the extension will try it and
crash rather than fall back to software rendering.

**Verify.** `glxinfo` and `glxgears` on the VM.

---

### [ ] B7. DPMS and the XScreenSaver extension. **S**

**Current.** The core screen-saver requests are implemented (`Get/Set/ForceScreenSaver`), the
extensions are not, so `xset s`, `xset dpms` and anything that inhibits the screen saver report
"extension not present".

**Why it matters.** Small: a client that plays video wants to say "do not blank"; a client that wants
the DPMS state gets nothing. A real server's answer is also what a test suite for idle behaviour
reaches for.

**Plan.** Advertise `MIT-SCREEN-SAVER` and `DPMS` with `QueryVersion`/`QueryInfo`/`GetTimeouts`/
`SetTimeouts`/`ForceLevel`/`Info` and the `ScreenSaverNotify` event, backed by the same idle state the
core requests already keep.

**Verify.** `xset q` on the VM shows the extension; `xset s 60 0` does not error.

---

<a name="b8-mit-shm--deliberately-not-worth-doing"></a>
### B8. MIT-SHM - deliberately not worth doing.

The extension exists to let a client and the server share memory instead of copying pixels through
the socket. **A client on another machine cannot use it at all** (the protocol requires the same
host), and every client here is remote - the VM, or a container. Implementing it would speed up
*same-host* clients only, and those already work (they fall back to `PutImage`/`GetImage`, which is
what the log shows). Do not put it in the plan; if a local client ever becomes the main use case,
revisit.

---

## 4. C - The host window (where this project differs from a plain server)

The host window plays the part of a window manager's frame. Everything in this section is about making
that frame behave the way a user expects, and each item is as much a product decision as a technical
one.

### [ ] C1. Screen auto-size: grow the screen with the window. **M**

**Current.** The screen (root, framebuffer, host window) is the size `--geometry` gave it, for the life
of the server. A resize caps the *client's* window at the screen (`DisplayBackend.setMaxClientArea`),
because the framebuffer behind it is fixed; a client window larger than the screen is clipped.

**Why it matters.** A forwarded application that wants to be 2000 pixels wide cannot be; a terminal
maximised on a big display gets a small window. This is the difference between "a viewer" and "a
desktop".

**Plan** (decided in an earlier session, recorded here because the decisions are the hard part):

- `Framebuffer.resize` must reallocate **in place**: `Picture.store` is final and every backend
  captured the array, so the array cannot be swapped for a new one - copy into the new arrays and
  keep the object identity.
- Apply the resize **from the display tick**, never from the request thread: the JavaFX/AWT input
  callbacks take the server lock, so holding it while waiting on the toolkit's thread deadlocks.
- The backends rebuild their `PixelBuffer`/`WritableImage`/`Canvas`/`Scene` (JavaFX) or
  panel/`BufferedImage` (Swing) on the tick.
- Cap it (`--max-screen`, default 8192x8192), because the setup reply's width/height are 16-bit.
- Take the server lock in `ConnectionSetup.sendSetup` so a client cannot be told a size the framebuffer
  does not have.
- Tell clients that are watching: without RandR (B2) nothing can notify a client of a new screen size,
  so either implement the minimal RandR notify or accept that the client learns by polling
  `GetGeometry`.
- The per-client mode already shows a viewport sub-rectangle of the framebuffer; growing the screen is
  the same mechanism with a bigger target size.

**Traps.** `Screen`/`Framebuffer` sizes are currently immutable and read from many places (the damage
rectangles, the cursor clamp, the viewport); a resize has to be visible to all of them at once or a
frame is presented at the wrong stride. The host window's own maximum size must be re-derived after a
resize. Shrinking has the same problem in reverse: a client window that no longer fits must be told.

**Verify.** A raw client that creates a window larger than `--geometry`; the framebuffer dump shows the
whole window, `xdpyinfo` reports the new size, and the client gets a `ConfigureNotify`/
`ScreenChangeNotify`. Then a real application resized by dragging the host window's edge beyond the
old screen.

---

### [ ] C2. Raise and activate the host window when a client window appears. **S-M**

**Current.** The host window is created once and never brought to the front. A client window that
appears while another application is frontmost stays behind it, with a grey title bar and no focus, and
- because macOS ignores cursor changes made by a background application - the platform pointer stays
visible next to the sprite.

**Why it matters.** Reported by the user as "the window opens behind the IDE", and it also causes the
parked "two cursors while the window is not active" item.

**Plan** (decided earlier, recorded here): `DisplayBackend.raise()` (JavaFX `stage.toFront()`, Swing
`frame.toFront()`), a `raisePending` flag set when a client's top level window is mapped and consumed
in the tick **after a frame was actually presented** (raising before the first frame shows an empty
window). Only raise when the mapping is a *new* top level, not on every damage.

**Traps.** Raising on every map turns the window into something that steals the user's focus
constantly (a client that maps a popup per keystroke). Guard it to the client's *first* top-level
window, or to a window that is not a popup (`override_redirect` false and larger than trivial).
`toFront()` on macOS also activates the application, which is a user-visible side effect - decide
whether to request the user's attention instead (`stage.requestFocus`, or the Dock bounce for an
urgency hint, see C3).

**Verify.** Start the IDE frontmost, then launch termora: the host window must come forward on its
own, and the sprite must be the only pointer.

---

### [ ] C4. Bridge the host clipboard to the X selections. **M**

**Current.** Selections work *inside* the X server (`ConvertSelection` delivers `SelectionRequest` to
the owner and `SelectionNotify` back), so copy/paste between two forwarded windows works. Nothing
connects them to the macOS pasteboard: text copied in a forwarded application cannot be pasted into a
Mac application, and the other way round.

**Why it matters.** Copy/paste is the single interaction a user notices most; without it a forwarded
terminal is a read-only curiosity for anything you want to paste.

**Plan.** A bridge that owns `CLIPBOARD` (and optionally `PRIMARY`) whenever no client owns it, and
acts as a requestor when it does:

- On the X side, the bridge is an ordinary client: it takes ownership when the previous owner goes
  away, subscribes to XFixes selection notifications (A8) to learn when a client takes it, and
  answers `SelectionRequest` with `UTF8_STRING`/`STRING`/`TARGETS`.
- On the host side, it reads/writes the macOS pasteboard (Java's `Clipboard` for text is enough to
  start; a native path is needed for images and files later).
- Fetch from a client only when the host pasteboard is read (X selections are lazy): `ConvertSelection`
  with a property, wait for `SelectionNotify`, read the property, push it to the host.
- Push from the host on host-pasteboard changes, or on `TARGETS` requests.

**Traps.** `INCR` (a transfer larger than the window property limit) must be handled or big pastes
truncate silently; the property must be `UTF8_STRING` for anything non-ASCII; the timestamps must be
the ones the client used, or a client that compares them refuses the transfer; and a client that owns
the selection and *disappears* leaves the bridge as owner with nothing to serve. Test with Chinese
text (the user's own use case).

**Verify.** Copy in `xterm` on the VM, paste into TextEdit on the Mac; copy in TextEdit, paste into
`xterm` (Ctrl+Shift+V in termora, or the middle click for `PRIMARY`); both directions with CJK text and
with a >100 KB paste (the `INCR` path).

---

### [ ] C6. HiDPI automation. **S**

**Current.** `--scale N` makes the picture sharp on a Retina display, but the client has to be started
with its own scale flag (`-Dsun.java2d.uiScale=2` for AWT, the equivalent for others), and today the
user does that by hand - the design notes explain why `--scale` is off by default and what a client
that ignores the resolution looks like.

**Why it matters.** "Sharp on a Mac" is the main reason to use this rather than VNC, and it costs the
user a manual step per application today.

**Plan.** Detect the host scale (`com.sun.glass.ui.Screen.getPrimary().getPlatformScaleX()`, or the
JavaFX scene's `outputScaleX`), and when it is 2, either (a) start with `--scale 2` automatically and
tell the client its DPI (the reported millimetres already follow the scale), or (b) leave the server
at 1x and inject the client's scale property through whatever launcher the project ends up with. The
server cannot set a client's JVM flags, so (a) plus documentation is the honest version; a per-client
"scale" preference is the product version.

**Traps.** A scale of 2 quadruples the framebuffer (memory: see E4, and band-with for the software
blend); `xdpyinfo`'s reported DPI doubles, which some clients use to *choose* their own scale - that is
the point, but it also means a client that scales by DPI *and* is given `uiScale` double-scales.

**Verify.** The same window captured at `--scale 1` and `--scale 2` on the Retina panel: the 2x one
must be pixel-sharp (compare an icon's edge count against the Xvfb render), and a client that ignores
the resolution must still lay out identically.

---

### [ ] C7. Remember the host window's position and size per client. **S**

**Current.** Every session opens the host window at the platform's default position and at the size of
the client's content.

**Why it matters.** A user who arranges their forwarded windows once expects them to come back there.
It is also how the window's *size* survives a client that maps nothing at startup.

**Plan.** A small per-client store (keyed by the client's `WM_CLASS` or by the command line reported
through the host window's title), written when the host window is moved/resized and read when it is
created. Keep it next to whatever configuration the server already has (`ServerOptions` is argument
driven today - this is the first piece of *state* the server would persist, so decide where it lives).

**Traps.** A remembered size larger than the screen (or than `--max-screen`) must be clamped. Restoring
a position the display no longer has (an unplugged monitor) puts the window off-screen - clamp to the
visible area.

**Verify.** Move and resize the host window, restart the server and the client, and check both are
restored, including the clamped cases.

---

### [ ] C3. Fullscreen, maximize and urgency from the client. **S-M**

**Current.** The host window takes the client's title and icon and resizes the client's window when
dragged, but nothing else of the ICCCM/EWMH vocabulary: a client that asks to be fullscreen
(`_NET_WM_STATE_FULLSCREEN`), maximised, or urgent (`WM_HINTS.UrgencyHint`) is ignored, and
`WM_TRANSIENT_FOR` is only reported (it is not used to give a dialog a sensible transient window).

**Why it matters.** A terminal or a media player that goes fullscreen on a real desktop stays a
window here; an application that wants the user's attention cannot ask.

**Plan.** Read the properties a client sets (`_NET_WM_STATE`, `WM_HINTS`) in the same place
`WindowIdentity` reads the title (i.e. on property change, applied by the tick), and translate them:
fullscreen/maximised -> the host window's corresponding state; urgency -> a Dock bounce or a
`Stage.toFront` (decide, it is a user-visible behaviour).

**Traps.** `_NET_WM_STATE` is a *list* that a client adds to and removes from, and it expects the
window manager to put the state *back* into the property as it applies it - a client that asks for
fullscreen and is ignored may retry forever. Transient windows: this server shows one client in one
host window (see C8), so `WM_TRANSIENT_FOR` has nothing to do today.

**Verify.** `xterm -fullscreen`-style clients (or a raw client that sets `_NET_WM_STATE_FULLSCREEN`)
fills the display; a client that sets the urgency hint is noticeable.

---

### [ ] C5. Host ↔ X drag and drop. **M**

**Current.** Nothing. Dragging a file from Finder onto a forwarded window does nothing; dragging out
of it does nothing.

**Why it matters.** It is the second most noticed interaction after copy/paste, and it is how files
get into a forwarded application without a shared filesystem.

**Plan.** The X side is client-to-client: the source owns `XdndSelection`, sends `XdndEnter`/
`XdndPosition`/`XdndDrop` as `ClientMessage`s to the window under the pointer that has the `XdndAware`
property, and provides `text/uri-list` on request. A bridge therefore needs a *window of its own* in
the X server (an override-redirect window that owns the selection and speaks the protocol), plus a
macOS drag source/destination (JavaFX `DragEvent` on the host window, or a native implementation),
plus the pasteboard file type (`NSFilenamesPboardType`/`public.file-url`).

**Traps.** The bridge's X window must not be visible (it is a protocol participant, not a UI); the
action list (`XdndActionCopy`/`Move`/`Link`) has to be negotiated or the drop is refused; the position
must be in *root* coordinates, which in per-client mode means the client's own screen - the same
coordinate translation the pointer already goes through (`JavaFxBackend.screenCoordinate`); and
dragging *out* needs a pasteboard file promise that the client's `text/uri-list` request can fill in
lazily.

**Verify.** Drag a file from Finder into a forwarded `xterm` (it should print the path); drag a file
out to the Desktop. Both with the same gestures against Xvfb + a real file manager as the reference.

---

### [ ] C9. Tray icons in the macOS menu bar. **M**

**Current.** Nothing. A client that wants a notification-area icon (Java's `SystemTray`, Qt's
`QSystemTrayIcon`, GTK's `StatusIcon`) looks for a `_NET_SYSTEM_TRAY_Sn` selection owner and gives up
when there is none.

**Why it matters.** Any application with a tray icon (chat clients, sync tools) silently loses that
part of its UI - and it is a visible "this is a real desktop" feature.

**Plan.** The tray protocol is ICCCM-shaped and small: the server (or the host window's Java code)
becomes the owner of `_NET_SYSTEM_TRAY_S0`, publishes `_NET_SYSTEM_TRAY_OPCODE`/`_VISUAL`/
`_ORIENTATION`, hears `SYSTEM_TRAY_REQUEST_DOCK` with a window id, and from then on *is* the tray
host for that window. The docked window is an ordinary X window: reparent it (or show it in a
`Stage`), forward its clicks, and expose the image in the macOS menu bar (JavaFX/AWT has no menu-bar
API; this needs JNI or a native helper, which is what makes it M rather than S).

**Traps.** Reparenting a client's window into a host window changes its coordinates and its
`ConfigureNotify`s; the tray window expects the tray host to resize it to the icon size it asked for.
A client that docks is usually also the one drawing the icon and its own context menu - the menu is a
popup on the X screen, so it must appear where the *host* icon is, not on the X screen.

**Verify.** A Java application using `SystemTray` (`xeyes`-style, or a raw client that sends
`SYSTEM_TRAY_REQUEST_DOCK`) shows up; its context menu opens at the menu-bar icon.

---

### [ ] C10. Input methods: verify XIM, and decide about the host IME. **M**

**Current.** Unknown - and that is the item. An X input method (fcitx/ibus with the XIM protocol, or
client-side modules) runs as an X *client* and talks to applications through the `XIM_SERVERS`
selection and X properties, which this server implements (selections work - see C4). Typing Chinese
through the *host's* IME into the host window is a different path: the JavaFX key events carry the
composed characters of a dead-key sequence only if the platform delivers them, and nothing forwards
the host IME's candidate window into the X session.

**Why it matters.** The author writes Chinese; a forwarded terminal that cannot be typed into in
Chinese is not usable for real work, and this is the kind of gap that is discovered at the worst
moment.

**Plan.** First verify: run `fcitx5` (or `ibus`) on the VM against this server and type into a
forwarded `xterm`; the XIM handshake (`XOpenIM`) and the conversion must work, and if they do, this is
a documentation item ("run an X IME on the client side"). If they do not, the failure is in the
selection/property path and is a bug to fix. The host-IME bridge - macOS IME composing into the
forwarded window - is a much larger feature (the IME's candidate window is native UI); decide
explicitly whether it is in scope.

**Verify.** Chinese (and Japanese/Korean) input into a forwarded `xterm` and into termora's terminal
with an X IME running on the VM; the same against Xvfb as the reference for "the IME itself is fine".

---

## 5. D - Large / product-level

### [ ] D1. Session persistence and re-attach. **L-XL**

**Current.** The session ends with the host window: closing it sends `WM_DELETE_WINDOW` to the clients
that asked for it, waits `--close-grace-ms`, then disconnects them. A client that dies takes its
windows with it. There is no way to leave the session running without a window and come back to it.

**Why it matters.** It is the difference between "a display" and "a place where work survives"
(`xpra`/`x2go`/`tmux`-for-GUIs). For a user who forwards a long-running application, losing it when
the window closes is the most painful failure mode.

**Plan.** Two halves, and both are needed: (a) keep the X server and its clients alive with no host
window (the screen keeps existing; clients keep drawing; nothing is presented), and (b) let a new host
window attach to a running screen, at the framebuffer level (`setViewport` already exists) and
eventually per client. Persisting across a *server* restart is a third, much larger step (the
framebuffer would have to be journalled and the clients' connections re-established, which plain X11
cannot do - the realistic answer is "the server stays up, in the background").

**Traps.** A detached server has no user to say "this window is in the way" - the raise-on-show
behaviour (C2) must not fire without a window; the tick can idle to save CPU when nothing is presented
and nothing is damaged; and the "close the window means close the session" contract has to become a
flag (`--detach-on-close`) rather than the only behaviour.

**Verify.** Start a client, detach, kill the host window, re-attach: the same windows with the same
contents, and the client never sees a disconnect.

---

### [ ] D2. Built-in remote transport. **L-XL**

**Current.** The framebuffer is presented by three backends: JavaFX and Swing windows on the machine
the server runs on, and a headless one that presents nothing. To watch a screen from elsewhere the
user runs `x11vnc` on the *client* machine (which is what the test bed does today on the VM).

**Why it matters.** "Forward one application, or a whole desktop, to another machine or a browser" is
the natural product shape of this codebase, and it needs no client-side software beyond a browser -
which is exactly what the author's own test bed is missing today (an `Xvfb` + `x11vnc` stack on the
VM exists only to *compare*).

**Plan.** A fourth backend: an RFB (VNC) or WebSocket+canvas presentation of the same framebuffer, on
the same tick. The damage rectangles and the viewport the two backends already use are exactly the
presentation contract; a `DisplayBackend` that writes into an RFB framebuffer-update encoder is the
smallest honest version (raw encoding first, then `ZRLE`/`Tight` if bandwidth matters). Input comes
back as RFB pointer/key events into the same `InputState`.

**Traps.** Bandwidth: raw 1300x800x4 per frame is 4 MB - the damage rectangles and a dirty-region
update are what make it usable, and `--scale`/colour-depth reduction are the knobs. Input mapping
needs a reverse of `screenCoordinate`. Authentication: RFB's own (`VNC`/`VncAuth`) or the server's
`--auth` (A7) - and A7 must be fixed before either is offered on a network.

**Verify.** A real VNC client on another machine, and the browser path in a page with a canvas; the
same scripted gesture on both, compared with the frame dumps.

---

### [ ] D3. Core-font fidelity. **M-L**

**Current.** Fonts are Java fonts looked up by X font name (`XFont`), not rasterised X font files: the
shapes and the metrics are the JVM's, and `SetFontPath`/`GetFontPath` are bookkeeping. Xft text (which
goes through RENDER) is unaffected and looks identical to a real server.

**Why it matters.** `xterm` with a core font (`-fn fixed`), `xfontsel`, and any client that measures
text through `QueryTextExtents` gets the JVM's metrics; a *font path* pointing at a real `misc-fixed`
or a bitmap font is not honoured. This is visible in the fonts' look.

**Plan.** Parse X font files (`.pcf`/`.bdf`, not `.pfb` - the vector ones are a different project) for
the fonts a user would put on a font path, and serve those bitmaps and metrics through the existing
`XFont`/`QueryFont`/`ImageText8` path; or, cheaper and honest, keep the Java-font approximation and
document it as a limitation (the README already does).

**Traps.** `QueryFont`'s reply is a large structure with per-character metrics; a client that gets them
subtly wrong mis-lays-out every string. Font names are aliases (`fixed` -> a specific file) resolved by
the font path.

**Verify.** `xfd`/`xfontsel` and an `xterm -fn`-based comparison against Xvfb: the same glyphs and the
same `QueryTextExtents` numbers.

---

### [ ] D4. Audio and other devices - out of scope by default.

Not an X server's job, but users will ask. The usual answer is a side channel (a PulseAudio/PipeWire
client or an RDP-style redirection), not the display protocol. Note it here so the question has an
answer, and keep it out of the plan unless the product is a full remote desktop.

---

## 6. E - Engineering and quality

### [ ] E2. Continuous integration. **S**

**Current.** No `.github/` at all. `mvn test` is 119 tests, most of them seconds, and it runs offline
(`-o`) with a Maven that is not on `PATH` on the author's machine.

**Why it matters.** Every item in this document ends in "the suite is still green"; a CI run is what
makes that checkable by somebody else. It also pins the Java/Maven versions (JavaFX 25 from the
classpath, `release 25`).

**Plan.** A workflow that runs `mvn -o test` on a JDK that satisfies the pom (temurin 25 or newer) and
publishes the surefire report. Note that the JavaFX backend test opens a real window when JavaFX is
available - on a headless runner it must skip, which the test already does if written that way;
check, and if it does not, make it.

**Verify.** A green run on a pull request, and a deliberately broken commit failing it.

---

### [ ] E4. Per-client framebuffer memory. **M**

**Current.** In the default mode every client gets its own `Screen`, and every `Screen` allocates its
own `Framebuffer` (`width * height * 4` bytes; 4.2 MB at 1300x800, 16.7 MB at `--scale 2`). The
server allows 256 clients: at 1x that is 1.1 GB of framebuffer alone, before any window, pixmap or
glyph.

**Why it matters.** It is invisible with two clients and fatal with twenty; a "one window per client"
server invites many.

**Plan.** Either share one framebuffer among screens of the same size (per-client screens already show
a *viewport* of a shared screen in `--single-window` mode, so the mechanism exists) or allocate a
client's framebuffer lazily and free it when its last window goes away. The second is smaller and
keeps the isolation the per-client mode has today at the framebuffer level.

**Traps.** Freeing a framebuffer while a picture, a glyph or the pointer sprite still points at its
array is a use-after-free inside the JVM's own heap - the same array identity trap as C1. `Screen`
holds the framebuffer as `final`.

**Verify.** A test that connects N clients (the suite already drives real connections) and reports
heap use, or simply asserts that two clients of the same size share/allocate as designed.

---

### [ ] E5. Backpressure to a client that has stopped reading. **M**

**Current.** Events are written to a client's socket while the server lock is held; a client that
stops reading fills the socket buffer and blocks the server (README's known limitation). A real server
buffers per client in user space and drops the connection when its buffer overflows.

**Why it matters.** One hung client freezes every other client's screen - the worst failure mode a
display server can have.

**Plan.** A per-connection output queue with a bound, written by the connection's own thread (or a
writer thread per connection) instead of inline under the server lock; on overflow, log and drop the
connection the way `killClient` does. `ClientOutput` is where the writes happen today.

**Traps.** Event ordering must survive: everything a client is sent must leave in the order it was
generated, so the queue cannot be per-event-thread without a sequence. Replies and events share the
stream. A test needs a client that deliberately stops reading (a socket that connects, handshakes and
then never reads again) while another client keeps drawing.

**Verify.** The test above: the second client's screen must keep updating, and the stalled one must be
disconnected with a log line.

---

### [ ] E3. Performance: local blending, fewer copies. **M-L**

**Current.** The rasteriser blends pixel by pixel in Java (`Compositor.draw` → `blend`), the `Picture`
sampler resolves format/repeat/alpha per pixel, and the JavaFX backend copies the framebuffer into a
`WritableImage` per damage rectangle before the canvas draws it. It is fast enough for one 1300x800
window at 60 Hz (the pulses log ~8 ms/frame at 120 fps, which includes the whole tick).

**Why it matters.** `--scale 2` is 4x the pixels, `--scale 3`/`4` more, and C1 makes the screen
growable. A user with a 4K panel wants scale 2 at 60 fps, which today means 13 M pixels a tick.

**Plan, in order of payoff:** (a) blend only the damage region (the damage machinery exists; the
per-request clip is computed per request already); (b) specialise the hot paths - `Over` with an
opaque source and no transform is a copy, and `Src`/`Over` with an A8 mask is the case every toolkit's
text and icons take - instead of the general double-precision blend; (c) skip the `WritableImage`
round trip by writing directly into a pixel buffer the canvas can use, or present only the damage
rectangle (already done) with a smaller buffer.

**Traps.** Every optimisation here is a pixel-exactness risk, and the current output is already within
one unit of Xvfb's; keep `Blocks`/`Xwd` comparisons as the gate (they can show a regression that the
test suite cannot). Do not introduce a fast path that changes rounding without saying so.

**Verify.** Timing from the pulse log before and after, on the same scene, plus the pixel comparison
against Xvfb for a toolbar/text screen (the depth-8/warm-up cases in `docs/design-notes.md` are the
sensitive ones).

---

### [ ] E1. The `eventcopy` generator fix lives in the protocol fork. **S** (in the fork)

**Current.** `ClientOutput.EVENT_NUMBERS` rewrites five event type bytes by hand, because the
generator in the `moaxcp/x11-protocol` fork ignores the `number` on an `eventcopy` and renumbers
sequentially. The workaround is documented in the README and in the design notes.

**Why it matters.** Any new event that the fork generates with an `eventcopy` - which is how the
protocol defines most of the aliases - will be wrong again, and the failure mode is silent and
confusing: a release arriving as a press is exactly the bug this workaround fixes.

**Plan.** Fix the generator (emit the `number` the protocol XML gives), regenerate, delete
`EVENT_NUMBERS`, and keep a test that compares every event class's number against the protocol
definition.

**Verify.** The suite's event tests plus a raw client that checks a `KeyRelease`'s type byte.

---

### [ ] E6. Keep the three documents in step. **S, ongoing**

`README.md` (what a user sees), `docs/design-notes.md` (why it is built this way) and this file (what
is missing) answer three different questions and each duplicates part of the others. When an item here
is done, the README's "Known limitations" and the design notes' "Deliberately not done" are usually
where the change has to be *recorded*, or the next reader re-derives it. Treat the tick as part of the
item, not as an afterthought.

---

## 7. Deliberate deviations (do not "fix" without re-measuring)

These are recorded in `docs/design-notes.md` with the measurement that settled them. Each one was
changed once, measured against a real client, and reverted - so a change here needs the same
measurement *before* it is proposed, not after.

- **No window manager semantics.** No decoration, no `_NET_*` handling beyond what is listed in C3, no
  activation protocol, no `WM_S0` owner. The host window is the frame and the "window manager" is the
  user's own desktop.
- **The crossing pair a grab reports at its start and end (`NotifyGrab`/`NotifyUngrab`) is not
  delivered.** Measured 3 runs out of 3: EasyShell tears its menus down within a millisecond of
  receiving it. A real server sends it.
- **A window that appears under a stationary pointer is reported as entered at the map**, so the
  client knows the pointer is inside it before the first move. The *pair* that a grab implies is still
  withheld (previous item).
- **A menu that is left and re-entered**: the crossings match the reference event for event; the
  dismissal that made this look like a server bug was the missing focus events, now implemented. Do
  not "improve" the crossing rules without re-running that gesture - the notes describe the harness
  (`/tmp/menu-outin.sh` and friends) and the 6/6 result.
- **The blend rounds; Xorg's fixed-point composition truncates.** A one-unit difference in a channel is
  expected and is not a bug to chase.
- **No backing store**: a revealed window is restored by painting the lower window's background and
  asking it to draw (`Expose`). A client that ignores the expose shows its background there. A9 is the
  item that would change this deliberately.
- **`IncludeInferiors` behaves as `ClipByChildren`** (A9 is the item).
- **Writing to a client that has stopped reading blocks the server** (E5 is the item).
- **The pointer cannot leave the host window's viewport** and is clamped to the screen: the sprite is
  hidden outside it, and a warp beyond it is clamped rather than moving the platform pointer.

---

## 8. Suggested order

Sized so that each phase ends with something a user can see, and ordered so that the cheapest
high-value items come first:

1. **Groundwork**: E0 (check the tools in), E2 (CI). Everything after this is cheaper to verify.
2. **Trustworthiness**: A7 (auth), A4 (xdotool), A3 (font cursors), A2 (bounding shape), A11
   (autorepeat). Five small items, each with a one-line user-visible outcome.
3. **Host window**: C1 (auto-size) and C2 (raise) - both already have their decisions recorded - then
   C6, C7, C3.
4. **Interaction**: C4 (clipboard) and C5 (drag and drop). These are what make it feel like a desktop.
5. **Protocol depth**: A5/A6/A8/A1 (the input and notification gaps), then B2/B3 (RandR, Xinerama) and
   B1 (XKB).
6. **Scale and shape**: E4/E5/E3 (memory, backpressure, performance), C8 (one host window per top
   level), C9/C10 (tray, input methods).
7. **Product decisions**: D1 (session persistence) and/or D2 (remote transport); D3 (fonts) if core
   fonts matter; B4/B5/B6 (XInput2, compositing, GLX) only with a reason that pays for them.

Items that are *observations* rather than features - A6, A10, A12, A13 - can be folded into whatever
phase touches their code; none of them is worth a branch of its own.
