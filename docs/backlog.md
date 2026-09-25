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
| ~~E0~~ | ~~finish the verification kit~~ **done 2026-09-25** | engineering | S |
| ~~A6~~ | ~~a keyboard-grab focus change is reported `NotifyNormal`~~ **done 2026-09-25** | correctness | S |
| ~~A14~~ | ~~crossing flags byte, focus bit, chain order and detail~~ **done 2026-09-25** | correctness | S-M |
| ~~A7~~ | ~~`--auth` can be bypassed by offering no credentials~~ **done 2026-09-24** | security | S |
| ~~A3~~ | ~~font cursors are drawn as a filled cell~~ **done 2026-09-24** | correctness | S |
| A4 | `xdotool` segfaults against this server **- crash is at startup** | correctness | S-M |
| ~~A2~~ | ~~a bounding shape does not clip drawing~~ **done 2026-09-24** | correctness | S-M |
| ~~A11~~ | ~~no keyboard autorepeat~~ **done 2026-09-24** | correctness | S-M |
| ~~A10~~ | ~~`GetMotionEvents` returns nothing~~ **done 2026-09-24** | correctness | S |
| ~~A12~~ | ~~`winGravity` is stored but not applied~~ **done 2026-09-25** (`bitGravity` still open) | correctness | S-M |
| ~~A8~~ | ~~XFixes selection/cursor notifications are never delivered~~ **done 2026-09-24** | correctness | S-M |
| A6 | a keyboard-grab focus change is reported `NotifyNormal` | correctness | S |
| A5 | `PointerRoot` focus reporting: the premise was wrong, five measured differences remain | correctness | M |
| ~~A1~~ | ~~passive grabs are recorded but never matched~~ **done 2026-09-25** | correctness | M |
| A9 | `IncludeInferiors`, backing store, save-unders are recorded only | correctness | M-L |
| A13 | `AllowEvents` freeze/thaw: premise disproven (measured), nothing to do | correctness | S |
| ~~C1~~ | ~~screen auto-size (grow the screen with the window)~~ **done 2026-09-24** | host window | M |
| C2 | raise/activate the host window when a client window appears | host window | S-M |
| ~~C4~~ | ~~bridge the host clipboard to the X selections~~ **done 2026-09-24** | host window | M |
| ~~C6~~ | ~~HiDPI automation (`--scale` without hand-editing the client's flags)~~ **done 2026-09-24** | host window | S |
| ~~C7~~ | ~~remember the host window's position per client~~ **done 2026-09-24** | host window | S |
| ~~C3~~ | ~~fullscreen / maximize / urgency from the client~~ **done 2026-09-24** | host window | S-M |
| C5 | host → X drag and drop (XDND); the drag *out* is still open | host window | M |
| C9 | tray icons (`_NET_SYSTEM_TRAY`) in the macOS menu bar | host window | M |
| C10 | input methods: verify XIM, and decide about the host IME | host window | M |
| ~~B2~~ | ~~RandR~~ **done 2026-09-25** | extension | M |
| ~~B3~~ | ~~Xinerama~~ **done 2026-09-25** (the three a client asks; the 1.1 extras measured and left) | extension | S-M |
| B1 | XKEYBOARD | extension | M-L |
| ~~B7~~ | ~~DPMS and the XScreenSaver extension~~ **done 2026-09-25** | extension | S |
| E2 | continuous integration | engineering | S |
| E4 | per-client framebuffer memory | engineering | M |
| ~~E5~~ | ~~backpressure to a client that has stopped reading~~ **done 2026-09-25** | engineering | M |
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
two-machine test bed. **The kit is now in this repository and complete**: the tools live in
`src/test/java/io/github/oyzh1994/xserver/tools/` (so `mvn -o test-compile` compiles them), the
two-machine harness is `tools/vm.sh` and `tools/bed.sh`, the reference display is `tools/xvfb.sh`, and
`tools/compare.sh` runs a whole comparison as one command. `tools/README.md` says what each tool is
for and how to run it, which tools were **lost** when `/tmp` was cleaned (and which of those came back
in E0), and the measurement rules that go with them. Read that file before running any comparison:
some of the rules exist because ignoring them produced an afternoon of chasing an imaginary rendering
bug - and one of them, the pointer outliving every client, is new in E0 and has the same shape.

### [x] E0. Finish the verification kit. **S** - done 2026-09-25

Restored on 2026-09-24: `Drive`, `WinTree`, `Focus`, `Pixel`, `Xwd`, `Blocks`, `Colors`, `Shift`,
`Rect`, `Crop`, a reconstruction of `XProxy`, a new `AuthProbe` (written for A7), and `SelectionProbe`
with `CursorProbe` (written for A8). `RawXClient`
grew a key driver, an auto-repeat setter and - worth more than the rest - a **reply sequence check**:
a reply that is not the one being waited for is now an error instead of a wrong value, which is how a
test that asked for an empty range once came back with the previous query's three events.

**Written 2026-09-25** (the rest of the item), all in
`src/test/java/io/github/oyzh1994/xserver/tools/`:

- **`Keys`** - types a string through XTEST, resolving each character against `GetKeyboardMapping`
  rather than carrying a layout; a character no key carries is reported and skipped. Measured against
  Xvfb with `xev`: `"Hello, World 123!"` produced exactly the expected presses, shift included
  (`Shift_L` + `keycode 25 (keysym 0x57, W)`), and 17 characters typed with none skipped. Against this
  server the same string types 18 and reports the two Chinese characters as untypable, which is the
  intended answer until C10's keycode claiming is in play.
- **`Shot`** - `GetImage` of the root or a window to a PNG, with no `xwd` and nothing installed on the
  machine that runs it. Measured against both: Xvfb's `xev` window came back white at 5,5 and this
  server's root back as `#E8E8E8`, which is the design's root colour.
- **`Wire`** - the bare-protocol base the probes share, which hands back *every* message as it
  arrives. `RawXClient` cannot be used for a probe: it finds a reply by reading until one turns up,
  and therefore eats the events a probe is counting.
- **`FocusProbe`** - the `Probe` the design notes' focus work was measured with. A three-window tree,
  a scripted sequence of `SetInputFocus` / warp / grab steps, and every focus and crossing event
  printed with detail and mode. **This is the harness for A5 and A6**, and it immediately found the
  differences recorded under A6 below.
- **`MenuProbe`** - the menu gesture as a tool: a popup mapped under a held button, grabbing the
  pointer, left and re-entered, with every crossing printed and an `EnterNotify` arriving in a step
  with no motion flagged `! enter without a move` - the event shape the design notes measured to tear
  EasyShell's menus down. **Measured 2026-09-25: its output against this server and against Xvfb is
  identical, line for line**, so the §7 deviations are intact for this gesture and any future change
  that breaks them shows up as a diff (or as the marker).
- **`PointProbe`** - walks `QueryPointer` from the root down, so "the child of the queried window or
  the deepest window?" is read off directly (A4's question). Both servers answer with the immediate
  child.
- **`tools/xvfb.sh`** - `start`/`stop`/`status` for the VM's reference display, because three of its
  flags are easy to get wrong and each failure looks like something else: `-listen tcp` (a stock
  build opens no TCP, so nothing here can reach `:98`), `-ac` (it refuses the kit's hand-written
  clients, which offer no credentials - the failure reads as "the setup was refused", i.e. as a dead
  display), and not `-nolisten unix` (the VM's own clients use the socket).
- **`tools/compare.sh`** - the six steps a rendering comparison takes as one command: both beds, the
  same gesture driven against each, `xwd -root` on the VM on both sides (rule 3), then the `Xwd` diff
  and the `Blocks` grid.

**Two traps the item itself cost, both now in `tools/README.md`:** the pointer outlives every client,
so a probe that does not park it before mapping gets a crossing at the map on one run and not on the
next - a diff then shows the *previous* run's difference; and the core grab opcodes are contiguous and
easy to shift by one (`GrabKeyboard` 31, `UngrabKeyboard` 32, `GrabKey` 33, `UngrabKey` 34), where
sending the wrong one is either silent or answered with a `BadRequest` that reads as a malformed
request rather than a misnumbered one. A probe that sends `GrabPointer`/`GrabKeyboard` must also read
the reply, or every event afterwards is attributed to the step before it.

`mvn test` 198/198 with all of it in the tree. Full list and usage in `tools/README.md`.

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
| `SelectionProbe` | subscribes to a selection through XFixes and prints every notification, while other connections take the selection, destroy its window and disconnect - the three subtypes A8 is about. Takes a selection name, so a client watching something other than CLIPBOARD can be checked. |
| `CursorProbe` | the same for `SelectCursorInput`: two windows with different glyph cursors, the pointer warped between them, every `CursorNotify` printed. |
| `ClipboardProbe` | plays one end of the clipboard bridge against a live server - `read` prints what a client would paste, `write <text>` takes the selection and serves it, `owner` prints who holds it - so the host's own pasteboard can be the other end of the check. |
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

### [ ] A4. `xdotool` segfaults against this server. **S-M** - worked on 2026-09-24, one cause ruled out

**Where it crashes, confirmed**: `xdo_new_with_opened_display` in `libxdo.so.3`, from a gdb backtrace
on the VM - xdotool's own library, in its startup, before any of the subcommand's own requests. Every
subcommand dies there, `getdisplaygeometry` included.

**Ruled out: Xinerama.** The obvious suspect was an extension xdotool probes at startup that this
server lacked, and XINERAMA was the one extension Xvfb has and this server did not. It was implemented
(see B3) and **the binary still segfaults in the same function** - so it is not the missing extension,
and the next person does not have to try it.

**What the next step is**: something the library reads out of a reply is malformed enough to crash it,
and the tool for that is a proxy that prints reply *bodies* - `tools/XProxy.java` is a byte-level proxy
in the kit that logs a client's stream and deliberately does not decode replies (E0 records extending
it as this item's first step). A strace of the socket writes gets the request bytes and nothing of the
answers, which is the wrong half.

**Parked 2026-09-24** as too large for the time available: this needs a diagnostic tool before it needs
a fix.

---

### [ ] A4old. `xdotool` segfaults against this server. **S-M**

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

### [x] A12. Window gravity is stored but not applied. **S-M** - `winGravity` done 2026-09-25

**What it was.** `bitGravity`/`winGravity` were set, reported in `GetWindowAttributes`/`QueryTree`, and
never used: a parent resize did not move a child by its win gravity. (A window's own `bitGravity` is
the other half, and is still the model's business - see below.)

**Why it matters.** Small in practice - the default is `NorthWest` and almost every toolkit leaves it
there - but the ones *used* in the wild are exactly the ones a caller notices: `EastGravity`/
`SouthGravity` for something pinned to an edge, `StaticGravity` for a window whose content must not
move.

**The reference's whole table, measured 2026-09-25 with `tools/GravityProbe`** (new in the kit: a
parent 300x300, a child 100x100 at 150,150, the parent grown by 200x100, the child's `GetGeometry`
read back). Every value, one gravity at a time, and the two halves are what make it worth pinning -
`North` moves a child by half the *width* change and `South` by all of it:

```
gravity        1 NW  2 N      3 NE     4 W     5 Center   6 E      7 SW    8 S      9 SE   10 Static  0 Forget
moved by       +0,0  +100,0   +200,0   +0,50   +100,50   +200,50  +0,100  +100,100 +200,100 +0,0   +0,0
```

The grid is a three by three: the row says which vertical edge's distance is kept (north keeps the
top, centre splits it, south keeps the bottom) and the column the same horizontally - so the shift is
none, half or all of the change. `Forget` and `Static` move nothing. Rounding is truncation toward
zero, also measured: a change of +201 moves a centred child by +100, and one of -101 by -50.

**What changed.** `WindowHandlers.applyWinGravity`, called from `applyConfigure` when a window's own
size changed: each child is moved by its gravity's share, and the area it leaves is handed back
through the ordinary move path (`visibilityChanged(..., moved=true)`, so the parent repaints where the
child was and the child is asked to draw where it now is).

**Two things measured that the implementation deliberately keeps:**

- **The reference moves the child silently** - no `ConfigureNotify`, no expose, nothing at all to the
  child (measured with the child selecting `StructureNotifyMask` and `ExposureMask`, with and without a
  background). That is not as odd as it sounds: the client declared the gravity, so it can predict
  where the child went. This server sends no `ConfigureNotify` either.
- **It does expose the child, and that is a deliberate divergence.** There is no backing store here
  (A9), so the alternative is a child showing whatever used to be under it. The pixels are ours to
  keep coherent; the events are where the reference's behaviour was copied.

**`bitGravity` is not implemented, and the plan for it in this item was wrong.** It is about a
window's *own* contents on its own resize, and with no per-window backing store there is no content to
shift: the window's pixels are wherever they were drawn, the server paints the new area with the
window's background and sends an `Expose`. For `NorthWest` - what everything uses - that is what
already happens; anything else would need A9's backing store first. Left open here deliberately.

**Measured 2026-09-25.** `handler/WindowGravityTest` (3 tests, the whole nine-gravity grid plus the two
that do not move and the "a move is not a resize" case). **It fails without the fix** (`+0,+0` where
`+100,+0` is expected). `GravityProbe` now reports the same numbers against this server and against
Xvfb for **all eleven** gravities - checked one at a time, which is how the table above was built.
`mvn test` 210/210. Live: EasyShell connected and its context menu still opened and survived the
out-and-back gesture, and **neither EasyShell nor termora declares a gravity at all** (the server's own
log of gravity moves stayed empty through both sessions), so this change is inert for both of them -
the verification is the reference diff.

**Traps.** The gravity is applied when the *parent's* size changes, not when it moves: a move's delta
is zero and nothing shifts, which the third test pins. And a child's own sub-tree follows it for free -
positions are relative to the parent, so moving a child moves its descendants with it.

---

### [x] A8. XFixes selection and cursor notifications were never delivered. **S-M** - done 2026-09-24

**What it was.** `XFixesExtension` implemented `SelectSelectionInput`/`SelectCursorInput` - it recorded
them - and never sent `SetSelectionOwnerNotify`, `SelectionWindowDestroyNotify`,
`SelectionClientCloseNotify` or `CursorNotify`. A clipboard manager, an `xclip`/`wl-paste` bridge or a
cursor-theme tool, all of which subscribe instead of polling the owner, saw nothing ever change.

**What changed.** The three selection subtypes, raised from the code that changes a selection's owner
(`XFixesExtension.notifySelection`, called from `AtomHandlers.setSelectionOwner`, from
`WindowHandlers.destroyWindowTree` and from the client-disconnect cleanup), and `CursorNotify`, raised
from the display tick - which is where the server knows what the pointer is over. Ownership is now
`XServer.Selection{owner, timestamp}` rather than an owner id alone, because the notification carries
the selection's own timestamp and it is not always now. A subscription is a
`(window, selection, mask)` triple: the window is what the event names, and the selection matters
because a client that watches CLIPBOARD must not hear about PRIMARY - the first version keyed
subscriptions by window alone and the regression test caught it.

**Measured 2026-09-24**, two ways:

- `tools/SelectionProbe` and `tools/CursorProbe` (new in the kit) run the same requests against Xvfb
  and against this server and print every event. The two outputs are **identical** once the things a
  server assigns for itself are normalised away (opcodes, atom ids, cursor serials, absolute clocks),
  which is how the rules in `docs/design-notes.md` ("What a subscriber is told") were established.
  Among them: a destroyed owner window and a disconnecting owner report `owner=0` with the
  *selection's original* timestamp, while every `SetSelectionOwner` - including one that changes
  nothing - sets both times to now; and a cursor's `cursorSerial` names the cursor, so it goes back
  down when the pointer returns to one it has shown before.
- Live, with the real client: EasyShell on the VM subscribes to `_NET_WM_CM_S0` as it starts
  (measured: `mask 0x7 on 0x400001`), and taking that selection from a probe delivered the
  notification to it. Taking `CLIPBOARD` did **not** notify it, which is the point of the triple -
  and what the old "a subscription means this client wants selection events" reading got wrong.

`extension/XFixesNotifyTest` (7 tests, real connections, the display loop running) pins all of it.
Five fail without the notifications, the subscription-lifetime test fails without the window cleanup,
and the seventh is the "did not subscribe, hears nothing" guard that caught the window-only key.

**Traps worth keeping.** The two timestamps are not interchangeable (see above), and sending the
notification *before* the owner is updated makes the client query the old owner. A reference server
**refuses every XFixes request from a client that never called `XFixesQueryVersion`** (`BadRequest`) -
measured while probing, and deliberately not copied: a real client always asks first, so all it would
change is a hand-written probe. Registration order is not ours to pick, so the event base comes from
`firstEvent()`, and the two event classes are numbered correctly by the fork (`NUMBER` 0 and 1),
unlike the five in E1.

**Left open here**: `ChangeCursor` and `CreateRegionFromPicture` are still refused with
`BadImplementation`, and `HideCursor`/`ShowCursor` still only count (the sprite is drawn
unconditionally) - neither is about notifications.

---

### [x] A6. A focus change made by a keyboard grab is reported as `NotifyNormal`. **S** - done 2026-09-25

**Current.** `EventDispatcher.deliverFocusChange` always reports mode `NotifyNormal`, including the
focus changes a keyboard grab causes; a reference server reports `NotifyGrab`/`NotifyUngrab` for those.

**Why it matters.** A toolkit that tracks the mode tells "my window lost the focus because somebody
grabbed the keyboard" from "because the user clicked elsewhere"; the difference is visible in menus
and in dialog activation policies. Narrow, but it is a documented deviation
(`docs/design-notes.md`, "The focus is the client's to be told about").

**Plan.** Thread the mode through `deliverFocusChange` from the two call sites that can be inside a
grab (`InputState.keyboardGrab`/`UngrabKeyboard`), and leave `NotifyNormal` everywhere else.

**Measured 2026-09-25 with `tools/FocusProbe`** (E0 built it), which is what the plan should actually
be built against - the reference does more than change a mode word:

```
step: grabkey A          (GrabKeyboard on A; the focus is already A)     Xvfb: (nothing)
step: focus C            (a focus change asked for inside the grab)
    Xvfb: FocusOut B detail=Pointer mode=NotifyWhileGrabbed
          FocusOut A detail=Nonlinear mode=NotifyWhileGrabbed
          FocusIn  C detail=Nonlinear mode=NotifyWhileGrabbed
    here: ... the same three, mode=NotifyNormal
step: ungrabkey
    Xvfb: FocusOut B detail=Pointer mode=NotifyUngrab
          FocusOut A detail=Nonlinear mode=NotifyUngrab
          FocusIn  C detail=Nonlinear mode=NotifyUngrab
    here: (nothing)
```

So the reference's model is: **a keyboard grab moves the focus to the window it is taken on and says
so, with `NotifyGrab`; ending it reports the way back with `NotifyUngrab`; and a change asked for
while it is held is reported `NotifyWhileGrabbed`.** This server did none of it: `NotifyNormal`
everywhere and nothing at all at either end of a grab.

**A correction, because the first version of this measurement was wrong.** The paragraph above first
read "a grab on a window that already has the focus reports nothing at its start", and it was written
from a `FocusProbe` run that printed exactly that - because the probe's reply reader looked for the
`GrabKeyboard` reply and *dropped the events it passed over*, and the reply is written before them.
Every measurement in this area must come from a probe that prints what it passes over; the one in the
kit does now, and the difference between the two readings is two events and a wrong model.

**Traps.** The windows named in a grab's event group include the *pointer's* window with detail
`Pointer` when the focus side is PointerRoot, so a fix for the mode word alone still leaves the diff
short. And the whole area is the one the §7 deviations come from: a crossing or focus event that a
client did not cause is what tears EasyShell's menus down (`MenuProbe`'s `! enter without a move`
marker). **Re-run `MenuProbe` before and after** - its output against Xvfb and here is identical
today, and the gesture must still match when this is done.

**Verify.** `FocusProbe` against Xvfb and here, the grab steps byte for byte; **and `MenuProbe` on
both, whose output is identical today**. Then the EasyShell menu gesture on a live server (6 runs, no
dismissal).

**Done 2026-09-25**, in `InputState.setKeyboardGrab` (which now reports the two transitions),
`InputState.setInputFocus` (which reports `NotifyWhileGrabbed` when a grab is held) and
`EventDispatcher.deliverFocusChange`, which grew a mode and a `force` flag - the flag is what lets a
change between two windows that are the same one be reported, which is the grab-on-the-focused-window
case. The reference's rule, measured four ways with `FocusProbe` first:

```
focus on X, grabkey Y     FocusOut X (NotifyGrab), FocusIn Y (NotifyGrab)
focus on X, grabkey X     FocusOut X (NotifyGrab), FocusIn X (NotifyGrab)   - a self-pair
focus none, grabkey Y     (nothing)
ungrabkey                 FocusOut <grab window>, FocusIn <focus now>        - both NotifyUngrab
focus change inside grab  reported NotifyWhileGrabbed
```

**Measured 2026-09-25.** `input/KeyboardGrabFocusTest` (5 tests) pins all four cases plus the guard
that a change outside a grab is still `NotifyNormal`; **four of the five fail with the reporting
disabled** (the fifth is the guard, and passes either way, which is the point of it). `FocusProbe`
against Xvfb and here now agrees on every grab line - the two remaining differences in its default
sequence are the crossing and `PointerRoot` differences recorded under A5, none of which is about
this item.
`MenuProbe`'s output on both servers is still **identical**, so the §7 menu behaviour is intact.
`mvn test` 203/203.

**Live, both clients**: with `--single-window` and a request log, **neither EasyShell nor termora
ever calls `GrabKeyboard` or `GrabKey`** - JavaFX grabs the *pointer* for its context menu
(`GrabPointer`/`UngrabPointer`, which is why §7's crossing deviation exists) and termora draws its
menu as a lightweight Swing popup with a pointer grab. So this fix is currently inert for both, and
the honest verification is the reference diff rather than a client. The menu gesture was run anyway,
6 out-and-back passes each: EasyShell's menu (an override-redirect window) stayed up 6/6 and
termora's stayed up 6/6 with the hovered item tracking the pointer, which is the regression check the
item's own Verify asked for. (The request log also turned up a bug in the kit: `WinTree` wrote its
property length into the **offset** field, so every title read was refused `BadValue` and the tool
printed no names - fixed, and it now prints `WM_CLASS`/`WM_NAME` for both clients:
`cn.oyzh.easyshell.EasyShellApp` and `Termora`.)

**Traps worth keeping.** The grab requests answer with a reply **before** the events they cause, so a
reader that looks for the reply first throws the events away - the first version of `FocusProbe` did
exactly that and produced a measurement that was wrong in a way nothing else would have shown. And
`NotifyWhileGrabbed` (3) is a mode core focus events have but EnterNotify/LeaveNotify do not: the two
event families share the detail lattice and not the mode numbering.

---

### [ ] A5. The focus under `PointerRoot`: the premise was wrong, the details are not. **M**

**What the item claimed.** That with the focus on `PointerRoot` a server "is supposed to move the
focus from window to window as the pointer moves and report each move", that this server does not, and
that it should be implemented in the crossing path.

**Measured 2026-09-25 with `tools/FocusProbe`, and the premise is false.** Against Xvfb, with the
focus on `PointerRoot` and three of one client's windows each selecting `FocusChangeMask`, a sequence
of pointer warps from the root into A, into B (A's child), into C and back out produced **crossings
only - not one `FocusIn` or `FocusOut`**:

```
step: focus pointer                     focus = PointerRoot
step: warp 5 5                          EnterNotify A detail=Ancestor mode=NotifyNormal focus=yes
step: warp 50 50                        LeaveNotify A detail=Inferior, EnterNotify B detail=Ancestor   focus=yes
step: warp 500 150                      LeaveNotify B, LeaveNotify A, EnterNotify C                    focus=yes
step: warp 1000 600 (onto the root)     LeaveNotify C detail=Ancestor                                 focus=yes
```

The specification agrees: "If focus is PointerRoot, the focus window is dynamically taken to be the
root window of whatever screen the pointer is on at each keyboard event" - the root, per screen, not
the window under the pointer, and nothing in that generates a focus event per window. What a client
learns from a `PointerRoot` focus is carried by the **crossing's focus bit**, which the reference sets
in every one of them. The key events themselves *do* go to the window under the pointer: measured with
`xev` on Xvfb - focus `PointerRoot`, pointer parked over xev's window, a key sent through XTEST - xev
printed the `KeyPress` and the `KeyRelease`. This server does the same, so that half already matches.

**So do not implement "focus follows the pointer".** It is not what a reference server does, and an
event the pointer did not cause is exactly the shape §7 records as tearing EasyShell's menus down.
"Sloppy focus" is a *window manager's* job - it watches `EnterNotify` and calls `SetInputFocus` itself
- which is the division this server is built on.

**What the same runs did turn up.** The default sequence still differs from the reference, none of it
about the pointer moving. Two of the differences were the crossing events' own reporting and are
**done** - see A14 below: the flags byte, the focus bit, the chain order and the chain detail. What is
left is the `PointerRoot` and `None` corners, and one of them is a state this server does not have at
all:

1. **The detail lattice against `PointerRoot`.** Going *to* `PointerRoot`, the reference relates the
   leaving window to the **root window** (`FocusOut C detail=Ancestor`); this server relates it to the
   window the pointer is in (`Nonlinear`). Coming *from* `PointerRoot` the two agree - so it is the
   leaving side that is wrong. (`FocusProbe`'s default sequence has two lines of this, the last
   step's.)
2. **A `FocusIn` with detail `Pointer` that the reference sends to the window the pointer is in**,
   around a change from `None` to a window holding it - six events in the default sequence, all of
   them around the grab steps, none of them sent here. Not explained yet: it looks like Xorg's
   per-window "focus in" bookkeeping being kept consistent (`dix/events.c` and its `focus->focusin`),
   and guessing at a bookkeeping rule is how the wrong model gets written down.
3. **`SetInputFocus` on the root window is not the same as `PointerRoot`.** `FocusProbe`'s `focus
   root` sends the root *window id*; the reference then answers `GetInputFocus` with that id
   (`focus = 0x1ff`) and reports the transition against it, where this server folds "the root window"
   into `PointerRoot` and answers `1`. The two behave differently - keys go to the root window in one
   and follow the pointer in the other - and a client that focuses the root window to "hand the
   keyboard back to the desktop" is the case that tells them apart.

**Traps.** Both the pointer *and* the focus outlive the connection, so a probe that does not park the
pointer and set the focus before its first step is measuring its own previous run: "the reference
sends a pair here" and "the reference sends nothing at all" were both true of the same request, an
hour apart, for exactly that reason. `FocusProbe` now parks the pointer, sets the focus to `None`, and
prints the server's own `GetInputFocus` answer after every step - which is what made point 5 visible.

**Verify.** `FocusProbe` (default sequence) against Xvfb and here, diffed line by line - **22 differing
lines today**, and the five groups above are all of them. `MenuProbe` on both must stay identical.
Then the EasyShell and termora menu gestures, 6 runs each.

### [x] A14. The crossing events' flags byte, focus bit, chain order and chain detail. **S-M** - done 2026-09-25

**What it was.** Four things a crossing event says besides the window it names, all of them measured
wrong here and none of them visible from the server's side of the socket.

- **The last byte packs the focus flag in bit 0 and `same_screen` in bit 1** - the opposite of what
  the field's name suggests. This server sent `0x01` where a reference server sends `0x02` in the same
  state, which **Xlib decodes as "same screen: no, focus: yes"**: every crossing this server sent
  claimed the pointer was on another screen, and reported the focus bit inverted whenever the
  crossing's window was not focused. Found by reading the same event with an independent decoder -
  `xev` on both servers - after the probe's own reading of the bit had been taken at face value for an
  hour.
- **The focus bit is set when the crossing's window is inside the focus window** (the focus window or
  a descendant of it). This server asked the relation the other way round
  (`window.isAncestorOf(focus)`), so it was set for the focus window's *parents* and clear for its
  children - the wrong answer for all three cases the measurement pins: a child of the focus (set),
  the focus's own parent (clear), an unrelated window (clear).
- **The `EnterNotify` chain arrives outermost-first, with the window itself last**; this server sent it
  innermost-first. Leaving is innermost-first in both, so the two directions are genuinely
  asymmetric - a toolkit walks *down* into the window the pointer entered, which it can only do if the
  windows holding it are named first.
- **A window in the middle of a chain reports `Virtual` when the window on the other end is above it**
  and `NonlinearVirtual` when the other end is unrelated. This server reported `Virtual` for every
  window in the chain, which tells a toolkit the pointer moved inside its own subtree when it left it.

**Measured 2026-09-25.** `input/CrossingReportTest` (4 tests, a three-deep tree so that a window can
be a *virtual* ancestor rather than a parent): the flags byte for a focused and an unfocused crossing,
the focus bit for a child, the parent and an unrelated window, the order of a three-window enter
chain, and both chain details. **All four fail when their own fix is reverted** - verified one family
at a time, since three of them are one assertion away from each other. The flags-byte fix was also
read back through Xlib: `xev` on this server now prints `same_screen YES, focus NO` for exactly the
crossing that used to decode as `same_screen NO, focus YES`, matching the reference word for word.

**And the regression check that matters**, because these four change what every popup is told:
`tools/MenuProbe`'s output against Xvfb and here is **still identical** (it was identical before, and
the whole point is that it stays so - §7's deviations live in this path); and live, with
`--single-window` on a real server, **EasyShell's context menu survived the out-and-back gesture 6
runs out of 6** with its hover highlighting working, and **termora's menu survived 6 out of 6** with
the hovered item tracking the pointer. `mvn test` 207/207.

**Traps worth keeping.** A probe that reads a bit out of a byte is asserting its own reading of the
protocol, not the server's behaviour: the fix here was to print the **raw byte** and compare it with
what Xlib decodes the same event into, and the two disagreed. Every one of the four was also invisible
to the suite as it stood - the server side agreed with itself in each case - so a test written from
the server's own model would have passed against every one of them.

---

### [x] A1. Passive grabs are recorded but never matched. **M** - done 2026-09-25

**What it was.** `GrabButton`/`GrabKey` were added to `XServer.passiveGrabs()` and removed by the
matching `Ungrab*`; **nothing read the list**. A client that armed one - a global shortcut, a toolkit's
popup binding, a window manager's frame buttons - never heard about the button or the key it asked for.
The last hole in the input model.

**What changed.** `InputState.matchingPassiveGrab` walks the window the event landed on and its
ancestors, so a grab on a frame beats one on the root, and `buttonChanged`/`keyChanged` match before
the event is delivered - a grab that fires means the input belongs to the client that armed it,
wherever it landed. The rules, and all of them were measured against a reference server with
`tools/GrabProbe`:

- **The modifiers**: `AnyModifier` (0x8000) matches any state; a specific one must match the state
  **exactly**. Measured: a grab armed with `ShiftMask` fires with Shift held and does not fire without
  it - and the state compared is the *modifiers*, not the whole pointer state, which carries the button
  that was just pressed (the first version compared that, and a Shift grab never fired).
- **The detail**: `AnyButton`/`AnyKey` (0) matches everything; a specific one only itself.
- **Two arms that match**: the specific modifier beats `AnyModifier` (the closest match), and among
  equals the **last armed wins** - which is what the protocol's "an existing grab with the same
  parameters is replaced" amounts to, and why `grabButton`/`grabKey` now remove an equivalent arm
  before adding theirs.
- **The lifetime**: the *grab* lasts from the press to the release (a button grab holds the pointer
  for exactly that long, a key grab the keyboard), and the *arm* lasts until the client disarms it -
  so the next click fires it again, which is what a shortcut is. Ending the grab is reported to the
  application as the crossing pair's second half.
- **The crossing pair a reference server sends is sent**: `LeaveNotify(NotifyGrab)` on the window the
  pointer is in and `EnterNotify(NotifyGrab)` on the grab window, and the reverse with `NotifyUngrab`
  at the release. **This is the pair §7 withholds for a grab a client takes on its own request**, and
  the measurement is what separates the two cases: a reference server sends it for a *passive* grab
  (measured), and a shortcut's client is not a menu that can be dismissed by it.
- **The press reported to the grabbing client names the child** of the grab window the pointer is in,
  which is how a client that owns the whole screen tells which of its windows was clicked.

**Measured 2026-09-25**, three ways:

- **`tools/GrabProbe` against Xvfb and here, line for line**: with the grab on the root and the pointer
  over another client's window, the press and release go to the grabbing client (reported against the
  grab window, `child` naming the window actually under the pointer), the other client gets the
  `LeaveNotify(NotifyGrab)`/`EnterNotify(NotifyUngrab)` pair, and the two outputs differ in **nothing
  but the window ids**.
- **`input/PassiveGrabTest`** (5 tests): the click belongs to the client that armed the grab and the
  client under the pointer hears nothing; the grabbing client is told the button came up; the pair
  that says the grab is over; a specific modifier fires only with it held (and the event reports the
  state it happened in); a key grab takes the key from the focused window; and the guard that a client
  which armed nothing is unaffected. **Four of the five fail with the matching disabled** (the fifth is
  the guard, and passes either way, which is the point of it).
- `mvn test` 233/233, `MenuProbe`'s output against Xvfb and here still **identical** (§7's gate), and
  live: EasyShell's context menu opened and survived the 6-run gesture, termora's menu opened, and the
  server log had no ERROR lines.

**Two traps, both of which cost a test rewrite.**

- **An event *mask* is not an event *type*.** The first version read its assertions with the mask
  values: `ButtonReleaseMask` is 8 and the event's type is 5, `KeyPressMask` is 1 and the type is 2 -
  and `ButtonPress` is 4 in *both*, which is what hides the mistake. A test reading with the mask waits
  for an event that never comes, and that looks exactly like a server that delivers nothing (the
  client was being sent everything, and the debug print showed it).
- **An armed grab is not an active one.** "The grab ends with the button" is about the *active* grab;
  the arm stays until `UngrabButton`, so the *next* click fires it again. The first version of the test
  expected the second click to be the application's and read as "the grab outlived the button" - the
  implementation was right and the test was wrong.

**Also found while measuring**: `GrabProbe`'s own `UngrabButton`/`UngrabKey` put the button in the
field the window goes in (the button is the byte right after the opcode), so the probe had been
disarming nothing; both it and `RawXClient`'s new helpers are written from the generated reader.
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

### [ ] A13. `AllowEvents` freeze/thaw semantics. **S** - the premise was measured, and the modes are inert on the reference too

**What the item claimed.** That the server "never freezes the pointer or the keyboard on a grab", that
`AllowEvents` is therefore close to a no-op, and that the `Sync*`/`Replay*` modes are where that is
wrong. The plan was: verify first with a raw client that grabs with `pointer_mode=Sync`, presses, and
checks that nothing arrives until `AllowEvents(SyncPointer)` - then implement freezing in `InputState`.

**Measured 2026-09-25 with `tools/AllowEventsProbe`** (new in the kit, and written to the item's own
plan), and **the reference holds nothing back**:

```
step: grab the pointer with pointer_mode = Sync                     grab status = 0
step: press button 1 (the press that freezes the pointer)           ButtonPress button=1
step: motion while frozen                                           MotionNotify
                                                                    MotionNotify
step: release button 1 while frozen                                 ButtonRelease button=1
step: AllowEvents(SyncPointer)                                      (nothing)
step: AllowEvents(AsyncPointer)                                     (nothing)
```

Three arrangements, all the same: an **active** `GrabPointer` with `pointer_mode=Sync`; a **passive**
`GrabButton` on the root with `pointer_mode=Sync`, activated by the press (which it takes - the
`NotifyGrab`/`NotifyUngrab` crossing pair arrives exactly as A1 measured); and a passive `GrabKey`
with `keyboard_mode=Sync`, where `AllowEvents(ReplayKeyboard)` released **nothing** - the replayed key
did not reach the focused window of the *other* client, which is the one arrangement where a replay is
unmistakable. The pointer case was also run with the input injected from a **second connection** and
with the motion made by **`WarpPointer`** instead of XTEST, since those take different paths into the
server and "the freeze is real but XTEST bypasses it" would have looked the same from one of them.
Neither changed anything.

`tools/AllowEventsProbe` against Xvfb and against this server, for all three arrangements, differs in
**nothing but window ids** (and one stale-pointer artifact from the run before). The key replay case is
two connections on purpose: a replay is only observable if the event could have gone to somebody else.

**So there is nothing to implement, and implementing a freeze the reference does not have would be
exactly what §7 warns about** - a device that stops delivering events is felt by every client at once,
and none of them asked for it. The item's own Verify step is what disproved the premise.

**What is *not* settled, and why the item stays open.** The measurement is XTEST-injected: the test bed
has no hardware pointer or keyboard in front of `Xvfb` on the VM, so a freeze that the real input path
honours and the injected one bypasses cannot be told apart here. Xorg's freeze lives in the event-
delivery path and should apply to injected events too - it did not - but "the reference does not freeze
this device" and "the reference does not freeze a device driven this way" are different claims. If a
client is ever seen depending on the modes (a game, a remote-desktop client), the next step is to
re-run this probe against a server with a real input device, or against an X server whose input is
driven by a uinput device on the VM.

**Not changed here**: `AllowEvents` still validates its mode and does nothing else, which is what the
reference does in every case measured.
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

### [x] B2. RandR. **M** - done 2026-09-25

**What it was.** Not advertised at all: a client could not learn the monitor's geometry or its device
pixel ratio, and the screen growth C1 landed had no way to tell anybody - the core protocol's only
answer is a `ConfigureNotify` for the root, which no modern client listens for.

**The model: one CRTC, one output and one mode covering the whole screen**, with the three ids drawn
from the server's own resource space so a client can pass them back. `QueryVersion` (1.6, capped at
what the client asks for), `GetScreenSizeRange`, `GetScreenResources`/`Current`, `GetCrtcInfo`,
`GetOutputInfo`, `GetCrtcGammaSize`/`GetCrtcGamma`, `GetCrtcTransform`, `GetPanning`, `GetScreenInfo`,
`GetMonitors`, `GetOutputPrimary`, `ListOutputProperties` and `SelectInput` with `ScreenChangeNotify`.
Anything that would *change* the screen - `SetScreenConfig`, `SetCrtcConfig`, `CreateMode`, the panning
setters, providers and leases - is refused with `BadImplementation`, and so is `GetOutputProperty`
(see below).

**Measured 2026-09-25**, with the real client and with the kit, and **it is the client that found every
bug in this one** - each of these was a visible failure rather than a wrong number:

- **`xrandr` against this server** prints the same three lines as against Xvfb, byte for byte apart
  from the numbers that are deliberately ours:
  `Screen 0: minimum 1300 x 800, current 1300 x 800, maximum 1300 x 800` /
  `screen connected 1300x800+0+0 344mm x 212mm panning 1300x800+0+0` / `1300x800 0.00*+`.
  **Getting there was one request at a time, because a refusal does not degrade - it crashes the
  client**: `xrandr` died with SIGSEGV on a `BadImplementation` for `RRGetPanning`, and again for
  `GetOutputPrimary`, so `GetPanning`, `GetCrtcTransform`, `GetOutputPrimary` and
  `ListOutputProperties` are there because a real client asked for them and could not carry on without
  an answer. (`GetCrtcGammaSize` answering **0** - the honest "there is no ramp" - made `xrandr` print
  "Failed to get size of gamma" and give up on the output's detail, so the ramp is a real 256-entry
  identity.)
- **`xrandr --listmonitors`** printed nothing and died (`free(): invalid pointer`) until the reply's
  own `nOutputs` was set: it was left at zero, and a client reads that count to know how far the
  monitor array reaches. Found by dumping the reply's bytes next to Xvfb's
  (`tools/RandrProbe`, new). **`xrandr --verbose`** prints the whole block - gamma `1.0:1.0:1.0`, the
  identity transform, the mode, the mm size.
- **`tools/RandrProbe`** against Xvfb and here, field by field: the version, the size range, the one
  CRTC/output/mode, the mode's name length, the output's status/crtc/preferred/counts, the CRTC's
  geometry/mode/rotation/output, and the monitor's atom name/geometry/mm size - all the same. **Two
  differences, both deliberate**: this server reports a real physical size where Xvfb reports `0mm`
  (the core setup reply already tells clients a real one, and the two must agree or a client computes a
  nonsense DPI - the item's own trap), and its size range does not reach down to `1x1` because this
  screen never shrinks.
- **`extension/RandRTest`** (7 tests): the version answered in the client's own terms, the output and
  CRTC both answering about the ids the resource list gave out (including the mm size against the
  screen's own accessor), the size range, the identity gamma ramp, the monitor with a *resolvable* atom
  name, **the `ScreenChangeNotify` a resize delivers to a client that selected it** - the half of C1
  that was missing - and the guard that a client which did not select hears nothing. Removing the
  extension fails five of the seven.

**Live, and this is the part that matters for a forwarded application**: **JavaFX asks RandR seven
times** as EasyShell starts - `QueryVersion`, `GetScreenResourcesCurrent`, `GetCrtcInfo`,
`GetOutputInfo`, `GetMonitors`, `GetOutputProperty`, `SelectInput` - so this is not a protocol for
hypothetical clients. With the extension in, EasyShell's window and its context menu are exactly as
they were, and the log has **no ERROR lines**: the one request it makes that is not implemented
(`GetOutputProperty`) is answered with a clean `BadImplementation` that the application carries on
past. `mvn test` 226/226.

**Traps.** RandR's replies are read positionally and some of them are long, so a field in the wrong
place is not an error but a plausible wrong number - or a client that dies in its own library, which is
how the missing `nOutputs` and the refused `GetPanning` showed up. A reply can be longer than thirty
two bytes (`GetMonitors`' array follows twelve bytes of padding), and a probe that reads only the fixed
part leaves the rest of the stream out of step. `xrandr` asks for `GetCrtcTransform`, `GetPanning` and
`GetOutputPrimary` *before* printing anything, and `--verbose` adds `ListOutputProperties` - a
half-implemented extension is not "mostly working", it is a crash. And **`GetOutputProperty` is left
unanswered on purpose**: this server's outputs have no properties (an empty `ListOutputProperties` is
the honest answer), the reference could not be used as a model for the refusal because the request this
probe builds is itself refused by it (`BadLength`), and no client here needs it - JavaFX asks once and
carries on.
---

### [x] B3. Xinerama. **S-M** - done 2026-09-25 (the part a client needs, measured)

**Done.** `extension/XineramaExtension` answers the three requests a client asks before it has decided
anything - `QueryVersion` (1.1), `IsActive` (**no**: this server is one screen per client and cannot
span more than one) and `QueryScreens` (that one screen, at the origin, sized to the root).

**Measured 2026-09-25 with `tools/XineramaProbe`** (new) against Xvfb and here, field by field:

```
                     Xvfb                        here
QueryVersion         1.1                         1.1          same
IsActive             yes - several heads         no - one screen   deliberately different
QueryScreens         1 head [0,0 1300x800]       1 head [0,0 1300x800]   same shape and size
```

**And live, with a real client**: `xdpyinfo -ext XINERAMA` against this server prints
`XINERAMA version 1.1 opcode: 133, base event: 67, base error: 21` and `Xinerama is inactive`, with a
zero exit status; against Xvfb it prints the head instead because Xvfb says it *is* active. The opcode
and the two bases are the part a client caches, and they are there.

**The three Xinerama 1.1 requests are deliberately left unanswered**, and this is the measurement that
settles it rather than a preference: `GetState`, `GetScreenCount` and `GetScreenSize` are for a client
that already knows it is on a Xinerama display, and **the reference's own answers are not a model** -
255 for the state, 255 for the count (neither is a value that means anything), and `BadLength` for the
size request even with the window argument the other two take. Both of those first two look like
replies the reference fills in without implementing the request, and building on them would be
guessing for a client that does not exist here. `extension/XineramaTest` (4 tests) pins the three
answers that *are* implemented, the head's size, and the three that are refused - the last one so that
a future session that implements them has to come back to this paragraph.

**Why it exists at all**: `xdotool` calls `XineramaIsActive` as it starts, and a client that asks a
question has to get an answer. Measured: with the extension present the xdotool segfault below is
**still** there, so Xinerama was not its cause - which is what A4's investigation needed ruled out.

### [ ] B3old. Xinerama (the original wording). **M**/**S-M**

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

### [x] B7. DPMS and the XScreenSaver extension. **S** - done 2026-09-25

**What it was.** The core screen-saver requests were implemented (`Get/Set/ForceScreenSaver`) and the
two extensions were not advertised at all, so `xset q` printed no DPMS section and anything that wants
to say "do not blank while this plays" - a video player, a presentation tool, a remote-desktop client -
was told the extension was missing. The core handlers also answered *constants*: a client that set a
timeout and read it back saw 600 whatever it had set.

**What changed.**

- **`extension/ScreenSaverExtension`** (MIT-SCREEN-SAVER): `QueryVersion` (1.1, the reference's answer),
  `QueryInfo` (state, saver window, the two countdowns, the caller's event mask and kind), `SelectInput`
  (kept per client, for the notification), `Suspend` (kept), and `SetAttributes`/`UnsetAttributes`
  accepted and ignored - they configure a saver *window*, and there is none to configure here. The
  `ScreenSaverNotify` is sent to subscribers from the core `ForceScreenSaver`, which is the one state
  change this server has.
- **`extension/DpmsExtension`**: `GetVersion` (1.2), `Capable` (**no** - see below), `GetTimeouts`/
  `SetTimeouts`, `Enable`/`Disable`, `ForceLevel` and `Info`, all backed by real state.
- **`display/ScreenSaverState`**, one per server rather than per screen: what would blank is the host
  window and there is one of them. It keeps the core settings, the saver's state, and - hooked from
  the input path, one assignment per motion, button and key - the time of the last input, so
  `QueryInfo`'s countdowns are real numbers rather than constants.
- **The core handlers now keep and report what was set** (`GetScreenSaver` answers the timeout and
  preferences a client set, `SetScreenSaver` stores them, `ForceScreenSaver` changes the state and
  raises the notification) - the same rule the auto-repeat work set for `GetKeyboardControl`.

**Measured 2026-09-25**, three ways:

- **Live, with the real client, which is this item's own Verify.** On the VM against this server:
  `xset q` prints the `DPMS (Display Power Management Signaling)` section - it printed none before -
  and `Display is not capable of DPMS`, which is the honest answer; `xset s 60 0` returns 0 and
  `xset q` then reads back `timeout: 60  cycle: 0`, **byte for byte what Xvfb prints for the same
  command**; `xset s activate` and `xset s reset` are accepted and the state a probe reads through
  `QueryInfo` goes On and back to Off, with `until_server` counting down against the timeout that was
  set; `xset dpms force standby` is accepted. No client request was refused (`rejected` count in the
  server log: 0).
- **`tools/ScreenSaverProbe`** (new): against Xvfb and here, MIT-SCREEN-SAVER answers **1.1**,
  `state=Off` before anything forces it, `kind=0`, `eventMask=0`, and two countdowns that are real
  numbers on both. The one field that deliberately differs is the saver window: Xvfb names the black
  window it maps to blank the display, this server answers `None` because it maps none.
- **`extension/ScreenSaverTest`** (8 tests): both extensions advertised; both versions; DPMS's
  `Capable` = no and the timeouts/level read back; the core settings read back as set; `QueryInfo`'s
  countdown inside a one-minute timeout; the notification reaching a subscriber with `state` and
  `forced` and the drawable it subscribed with; **a client that did not subscribe hearing nothing**
  (the guard that makes the previous test mean something); and `Suspend` kept and released.
  Removing the kept settings fails two of them; removing the extensions fails the rest.

**Decisions worth keeping.**

- **`Capable` is no.** The display is the host window on a machine whose power management is the
  system's business; a client that checks capability then skips its DPMS logic, which is right here.
  Same shape as XINERAMA's `IsActive` answering no.
- **Nothing blanks.** The host window owns the display and on this platform the system's screen saver
  is what blanks it, so the settings and the state are kept and reported and no black window is mapped.
  `QueryInfo`'s saver window being `None` is that decision showing.
- **There is no DPMS reference to diff against**: Xvfb does not have the extension at all (measured -
  `QueryExtension` answers 0), so its replies follow the protocol and the two version numbers the
  extension has ever had.

**Traps.** `ForceScreenSaver` is a **four byte** request - a mode byte and a length - and this server
accepts an eight byte one, which a reference server refuses with `BadLength`; the same class of
leniency the design notes already record for `SetInputFocus`. A probe that also *forces* the saver on
every run is measuring its own doing: the first version of `ScreenSaverProbe` sent a
`ForceScreenSaver(activate)` before reading the state, and every reading said `On`. And the core
opcodes are 107/108/115: a test written from memory used 116/117/124, of which 124 is
`unsupported opcode` here - the live `xset` is what settled them.
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

### [x] C1. Screen auto-size: grow the screen with the window. **M** - done 2026-09-24

**What it was.** The screen was the size `--geometry` gave it for the life of the server, so a client's
window was capped at the screen and a forwarded application that wanted to be 2000 pixels wide could
not be. Dragging the host window's edge stretched the frame and stopped.

**What changed.** The screen grows to fit what a drag asks for:

- `Framebuffer.resize` moves the pixels into a larger array and keeps this object's identity - the
  backends wrap `pixels()` and every picture over the screen holds the store - so what changes is the
  size, not the object. `Screen.resize` changes the framebuffer, the root window's geometry and the
  reported size together, because the damage rectangles, the pointer clamp and every window's clip are
  computed from them and a screen whose parts disagreed would draw at the wrong stride.
- The resize happens from the display tick, under the server lock, in `applyRequestedSize` - the same
  place a drag is already applied - and the backends are told afterwards, outside the lock, through a
  new `DisplayBackend.screenResized` (the JavaFX `PixelBuffer`/`WritableImage` and the Swing panel's
  `BufferedImage` are rebuilt there).
- Clients that asked to hear it are sent a `ConfigureNotify` for the root: there is no RandR here, so
  it is the only way a client learns the display changed under it.
- `ConnectionSetup.sendSetup` reads the screen's size under the server lock, so a client cannot be
  told a size the framebuffer does not have.
- `--max-screen WxH` (default 8192x8192) is the ceiling: the setup reply's fields are sixteen bit, and
  8192x8192 is already 256 MB of pixels.
- The window's maximum size (`setMaxClientArea`) is now `--max-screen` rather than the screen. A limit
  at the screen's present edge would stop the very drag that was meant to grow it.

**A screen never shrinks.** Growing is what a user asks for by dragging a window bigger, and there is
no matching gesture that says "and now the desktop is smaller".

**Measured 2026-09-24**, two ways:

- `handler/ScreenResizeTest` (4 tests, a real client over TCP): a drag past the screen grows it in both
  dimensions; what was drawn is still there afterwards *and* the area it grew into really exists (a
  screen claiming 500x400 over a 400x300 array reads nothing at that corner); the growth stops at
  `--max-screen`; and the client is sent a `ConfigureNotify` naming the root and its new size.
  Disabling the growth fails all four, disabling the notification fails exactly one, and disabling the
  pixel move fails exactly one. `HostWindowTest`'s drag tests now assert the new behaviour - the drag
  is honoured rather than capped - and its maximum-size test asserts `--max-screen`.
- Live, on the Mac with EasyShell from the VM on a 900x600 screen: a synthesized drag of the frame's
  bottom-right corner grew the screen to **1200x789** (confirmed from a framebuffer dump) and the
  client's window was resized with it. EasyShell refuses a window larger than 1000x756, so the frame
  came back to **1000x784** = its content plus the title bar - which is the "the frame is pulled back
  to the content" behaviour the design notes describe, now working in the direction it never did.

**Traps.** Two things this ran into, both now in the design notes: an unmanaged canvas contributes
nothing to its parent's preferred size, so `sizeToScene()` leaves the window at the size the scene was
built with - a window whose content changed used to keep whatever size the user had dragged it to - and
a window size applied while the user is still dragging is discarded by the platform, so the fit has to
be re-checked every frame rather than only when the viewport moves.

**Left open**: a screen does not shrink (above); and a client that has not asked for structure events
learns the new size only by asking again (`xdpyinfo` reports it, since each connection is answered from
the screen as it is now).

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

### [x] C4. Bridge the host clipboard to the X selections. **M** - done 2026-09-24

**What it was.** Selections worked *inside* the X server, so copy/paste between two forwarded windows
worked, and nothing connected them to the macOS pasteboard: text copied in a forwarded application
could not be pasted into a Mac application, and the other way round.

**What changed.** `clipboard/ClipboardBridge`, a window of the server's own that owns `CLIPBOARD`
whenever a client does not, plus `clipboard/HostClipboard` over the platform's. The design decisions
that were not obvious, and the measurements behind them:

- **The host-to-X half is a poll, not a request.** Every X request runs while the server lock is held,
  and reading a platform pasteboard is a round trip that can block, so the poll thread (200 ms) keeps
  the text and the request path only ever reads the copy. The bridge's state is guarded by the *server
  lock* and nothing else, so the two threads cannot take two locks in opposite orders.
- **The X-to-host half has to be eager**, which contradicts the plan above: macOS does not tell anybody
  that a pasteboard was *read*, so there is no "someone is about to paste" moment to fetch on. When a
  client takes the selection the bridge asks it for the data straight away and puts it on the host -
  which is also why copying inside a forwarded application replaces the Mac's clipboard, the way every
  bridge on a platform without a read notification behaves.
- **The bridge owns the selection only while the host has text to serve**, and gives it up when it does
  not, so a paste with a picture on the Mac finds no owner and leaves what the user had alone.
- **A host copy wins over a client's**, or the client that copied before would answer the next paste
  with its old text: a change to the pasteboard that this bridge did not make takes the selection over.
- `PRIMARY` is not bridged (a pasteboard has no three-button pointer) and neither are images or files
  (no X11 equivalent to offer). `INCR` is honoured when *receiving* from a client, whose choice it is;
  the bridge serves a property in one write, because it is not a client and is not bound by a request
  size.

**Measured 2026-09-24**, three ways:

- `clipboard/ClipboardBridgeTest` (9 tests) against a clipboard the test owns: the bridge takes the
  selection when no client does and gives it up when the host holds no text; a client pastes what the
  host holds and is told `TARGETS`/`UTF8_STRING`/`STRING`/`TEXT`; a target it cannot serve is refused
  with `property None`; a client's copy reaches the host and the client keeps the selection; both
  directions in Chinese; a chunked (`INCR`) transfer arrives whole; and a Mac-side copy takes the
  selection over. All nine fail when the bridge is neutered.
- Live, against the **real macOS pasteboard**: both directions with `tools/ClipboardProbe`, and both
  directions with a **real toolkit** (`java.awt`'s clipboard on the VM, which is the X selection - the
  same path termora's Swing clipboard takes), Chinese included, each end checked with the platform's
  own pasteboard. EasyShell was connected throughout and the round trip left it alone.
- `mvn test` 158/158.

**Found on the way, and fixed**: `GetProperty` read `long_length` as a signed int, so the
`0xFFFFFFFF` a client sends to mean "the whole property" was `-1` and came back as
`BadImplementation` instead of data - which is what *every* client sends when it reads a clipboard
property. `handler/GetPropertyTest` (4 tests) pins the read sizes.

**Traps worth keeping.** A probe that holds the selection and does not answer `TARGETS` looks exactly
like a broken bridge: a toolkit asks for `TARGETS` first and gives up if it is refused, and that hunt
cost an hour (`tools/ClipboardProbe` did not answer it either until a real client pointed it out).
`UTF8_STRING` is what carries anything but ASCII, and `STRING` is Latin-1 by the letter of the spec
and UTF-8 in practice, so it is read as UTF-8 when it is valid UTF-8 - the only reading that gets both
kinds of client right. The shell's `pbcopy`/`pbpaste` are blocked in this environment's sandbox while
AWT is not, so the host end of a live check goes through Java.

**Not covered here**: pasting into a real application's widget by hand (the last mile is a mouse and a
keyboard, and a scripted gesture inside EasyShell has side effects); images and files; `PRIMARY`.

---

### [x] C6. HiDPI automation. **S** - done 2026-09-24

**What it was.** `--scale N` made the picture sharp on a Retina panel, but the user had to know the
panel's scale factor and pass it by hand, and a client that sizes itself from the display had to be
started with a flag of its own for every application.

**What changed**, and it is two halves with only one of them a server's to do:

- **`--scale auto`** resolves to the host's own scale factor (`ServerOptions.hostScale`, read from the
  platform's default transform - the one piece of HiDPI information the JDK gives up without a
  toolkit). The scale stays 1 by default, and the reason is measured below.
- **The screen publishes `Xft.dpi`** in the root window's `RESOURCE_MANAGER` whenever it is scaled -
  the same property `xrdb -merge` sets, and the question a toolkit that renders through Xft (GTK, Qt,
  an `xterm` with Xft fonts) asks the display. A client that reads it draws its interface at the
  resolution the screen really has, which is what makes a forwarded GTK application sharp without
  being started with anything special. A client that does not read it is unaffected, which is why
  publishing it is safe.
- **The other half is not automatable here**: a JavaFX or AWT client sizes itself from its own toolkit
  settings, so it needs `-Dsun.java2d.uiScale=2` (or the equivalent) to draw at 2x. The server cannot
  set a client's JVM flags, so the honest version is a loud line in the log, which an unscaled server
  on a scaled display now prints with the option's name in it.

**Measured 2026-09-24**:

- `ServerOptionsTest` (5 tests) and `handler/HighDpiTest` (2): `--scale auto` is the host's scale and
  the screen is that many times the logical size; a scale no display can line up with is refused;
  `--max-screen` is parsed and capped at what sixteen bit fields can describe; a scaled screen
  publishes exactly `Xft.dpi:\t192\n` and an unscaled one publishes nothing. Not publishing fails one
  and making `auto` a constant fails the other - on this machine, where the host really is 2x.
- Live, on the Retina panel: `--scale auto` resolved to 2 (in the log), `xdpyinfo` from the VM
  reported **1280x800 pixels (169x106 mm) / 192x192 dpi** for a 640x400 `--geometry`, and the
  resource manager carried `Xft.dpi: 192`.
- **And the measurement that keeps the default at 1**: EasyShell, run against that server, is a
  JavaFX application that does not read `Xft.dpi`. Its toplevel came out 1280x800 - filling the screen
  it was told about - so the host window was **640x428 logical points**, with the application's
  interface at half the size it should be. Sharp, and half-sized: exactly the trade-off
  `docs/design-notes.md` records for `--scale`, now measured with the client the author actually
  forwards.

**Traps.** The screen is four times the memory at scale 2 (see E4) and the software blend has four
times the pixels (E3). A client that scales by the reported DPI *and* is given its own `uiScale`
double-scales - the DPI is there to be followed, so do not also pass the flag to something that
already reads it.

**Left open**: a per-client scale setting, which is what would let one server serve a follower and a
non-follower at once; it needs the client to say what it wants, and the protocol has no way to.

---

### [x] C7. Remember the host window's position per client. **S** - done 2026-09-24

**What it was.** Every session opened the host window at the platform's default position.

**What changed.** The position is remembered per client in `~/.xserver/host-windows.properties` and
handed back to the backend when the window opens, clamped to the part of the display windows may
occupy. `DisplayBackend` grew `setWindowPosition` and an `InputSink.windowMoved` report; the JavaFX
and Swing backends implement both, and the record of a position is written from the display tick
rather than from the toolkit's thread.

**Only the position, not the size.** The size half of the item is already how the server works: the
host window is sized to the client's windows, and dragging its edge resizes the client's window - so a
client that cares how big it is remembers that itself and asks for it next time. Opening a window
larger than its content is the failure mode the design notes call out (the part it grew into shows
nothing, and the next content change pulls it back), so remembering a *size* would fight the frame
the server already keeps sized to what it shows.

**Measured 2026-09-24**, two ways:

- `HostWindowPositionTest` (4 tests, a recording backend) and `HostWindowStoreTest` (6): a window opens
  where it was left; a second client's remembered position is not applied to another's; the position a
  window *opens* at is not treated as a move; a client with no `WM_CLASS` is not remembered at all;
  and remembered positions are clamped - off the right and bottom, off the left and top, and a window
  larger than the display keeping its top-left. Neutering the write and the restore makes them fail.
- Live, on the Mac with EasyShell from the VM: its real `WM_CLASS` is
  `cn.oyzh.easyshell.EasyShellApp`; a synthesized title-bar drag (CGEvent) moved the window from
  `106,81` to `-194,195` and the store followed it exactly. On restart the server logged
  `opened a window for display :91 ... at -194,195` and the window came up at **`0,195`** - the
  backend clamped the part that was off the left edge - while the store kept the user's own value.

**Traps.** A window's *opening* position arrives through the same report a drag does, and writing it
out overwrites what was remembered with the very place the restore was about to change: measured
happening on the first live run, which is why the first position of a session is adopted as a baseline
rather than recorded. A drag reports a position per frame, so the write waits for the position to hold
still (400 ms) - the same settle the viewport uses. And the clamp belongs to the backend, not to the
store: only the backend knows what the display has room for now.

---

### [x] C3. Fullscreen and urgency from the client. **S-M** - done 2026-09-24

**What it was.** The host window took the client's title and icon and resized the client's window
when dragged, but nothing else of the EWMH vocabulary: a client that asked to be fullscreen, or set
its urgency hint, was ignored.

**What changed.** The two things a client asks its window manager for, applied to the frame:

- **Full screen** (`_NET_WM_STATE_FULLSCREEN`) shows the host window full screen and gives it back
  when the client asks for it back. `WindowIdentity` reads the state off the client's toplevel, and
  the tick applies it, the same path the title already takes. `JavaFxBackend.fitWindowToViewport`
  stands aside while the window is full screen - that is the one time the window is deliberately not
  the size of its content, and the platform is the one sizing it.
- **Urgency** (`WM_HINTS`' `UrgencyHint`) brings the window to the front, once per hint, and
  deliberately does **not** take the keyboard. This is *not* the raise-on-show that C2 describes and
  that the user parked: nothing happens unless a client says it wants attention, which is the one
  case where pulling a window out from behind another is what was asked for.

**The protocol half, which is where the work was.** A toolkit does not set `_NET_WM_STATE` - it sends
a `ClientMessage` to the root and waits for the window manager to write the state into the property,
and a client that is ignored asks again for ever. So `AtomHandlers` handles the message
(`applyWindowStateMessage`), and there is a **found bug** behind why it could not work before:
`SendEvent` filled in the event's window field with the window it was *delivered* to, which is right
for every event that is reported *against* a window and wrong for a `ClientMessage`, whose window
field is the subject the sender put there. Every EWMH request - and the `WM_DELETE_WINDOW` reply -
was arriving with its subject replaced by the root. Fixed in `AtomHandlers.sendEvent`.

**Measured 2026-09-24**:

- `handler/WindowStateTest` (6 tests, a real client, a recording backend): asking to fill the display
  shows the display *and* writes the state back; asking again gives it back; `toggle` adds and then
  removes; a request naming two states (maximised horizontally and vertically) applies both; a state
  written straight to the property is honoured too; and the urgency hint raises the frame exactly
  once - a second raise per frame is what a window the user cannot get away from would look like.
  All six fail when the message handling or the tick's application of it is disabled.
- Live, on the Mac with `tools/WindowStateProbe` (new in the kit): `fullscreen on` put the host
  window into the platform's full screen (`window is now full screen` in the log), the client read
  `[_NET_WM_STATE_FULLSCREEN]` back out of its own property, and `on,off` in one connection gave it
  back again.

**Decisions worth keeping.** Full screen is the **platform's** full screen - on macOS another Space,
with the animation - rather than a window stretched to cover the menu bar: it is what the system does
for an application that asks to fill the display, and the user leaves it the usual way. It is a
product decision rather than a technical one, and it is one line to change if it is wrong.
**Maximised** is deliberately *not* mapped to a host window state: the host window follows its
content, and a client that maximises itself resizes its own window, which the screen now grows to fit
(C1) - so the request is written back to the property and the client's own resize does the rest.

**Left open**: `WM_TRANSIENT_FOR` still only exists as a property - with one host window per client
(see C8) there is nothing for a transient to be relative to.

---

### [ ] C5. Host ↔ X drag and drop (XDND). **M** - the host-to-X half is done 2026-09-24, not ticked

**What it was.** Nothing: dragging a file from Finder onto a forwarded window did nothing.

**What was built (the half that can be).** `drop/DropBridge`, a window of the server's own that speaks
XDND as the drag's *source*: the platform reports the drop and the files, the bridge takes the
`XdndSelection`, sends `XdndEnter`/`XdndPosition` to the window under the pointer that publishes
`XdndAware`, waits for its `XdndStatus` (or 700 ms, whichever comes first) and sends `XdndDrop`, then
answers the `ConvertSelection` that follows with a `text/uri-list`. The host side is a few lines of
`DragEvent` in each backend - `InputSink.hostDrop` - because the platform's half of a drag is the only
part no X client can know. The bridge only exists for a server that presents a window, on the same
reasoning as the clipboard bridge.

**Found on the way, and it is a real protocol bug: `SendEvent` with no event mask did not deliver.**
X sends such an event to the client that *created* the window it names, whatever that client selected
- it has to be that way round, because a target cannot select for a message it does not know is
coming - and the server treated "no mask" as "nobody is interested", so every one of these messages
was dropped. XDND is built entirely out of them, which is where it was found; `WM_DELETE_WINDOW`
replies and everything else in EWMH use the same form. Fixed in `AtomHandlers.sendEvent`, pinned by
`handler/ClientMessageTest` (2 tests).

**Measured 2026-09-24**: `drop/DropBridgeTest` (3 tests, a real client playing the target): a dropped
file is offered to the window under the pointer, with the source window, the XDND version and the type
it carries; a target that answers `XdndStatus` is dropped on and can ask for the files, and gets
`file:///…` one per line out of the property; a window that does not publish `XdndAware` is left
alone. Disabling the `XdndEnter` send fails two of them.

**What is not done, and why this is not ticked: dragging *out* of a forwarded application.** A JavaFX
window cannot start a platform drag - the platform's drag is the user's gesture and the toolkit only
reports one - so there is no drag to hand a client's `XdndEnter` to. That needs a native drag source
(and on macOS a pasteboard file promise the client's `text/uri-list` request fills in lazily), which is
the second half of this item.

**Also unverified: a real application receiving a real drop.** The item's Verify says "drag a file from
Finder into a forwarded `xterm` (it should print the path)" - but **`xterm` does not speak XDND**; it
is a client from before drag and drop, and nothing it does will ever print a dropped path. The clients
that take drops are toolkits - GTK, Qt, and Swing's `TransferHandler` - so a forwarded GTK application
(or termora's terminal) is the thing to try. And the platform's half cannot be scripted here for the
same reason C10's could not: a drag is a real mouse gesture, and neither `osascript` nor a posted
`CGEvent` got one to reach the host window in this session.

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

**Worked on 2026-09-24: the server half is implemented and tested; not ticked.** What a forwarded
application can be sent is a keycode, and a keycode cannot express a character no key carries - so the
platform's composed text has to become a keycode the server claims for it:

- **`InputState.keyTyped(char)`, reached from every backend.** The host's input method composes the
  text, the platform hands it to the backend, and the server gives a keycode no keyboard carries the
  character as its keysym (`0x01000000 | codepoint`, X's own spelling for a character with no keysym
  of its own), sends `MappingNotify` so the clients re-read the mapping, and presses that keycode. A
  character the keyboard *does* carry is left alone - the key press already delivered it - and the
  keycode is claimed once and kept, because a mapping change per keystroke would be a mapping change
  per keystroke. Platform-independent by construction: every toolkit reports composed text the same
  way, and everything after that is the same X protocol.
- **`Keymap` owns the whole mapping again.** It had grown a second table in `InputHandlers` for a
  client's `ChangeKeyboardMapping`, so two places could disagree about what a keycode means.
- **A real bug fell out of the tests**: the server's own synthetic keycode counted as "a key that is
  on the keyboard", so the *second* time a user typed the same character it was swallowed. Typing the
  same character twice is two keystrokes.
- **JavaFX needs two hooks, and the second is the whole feature on a Mac - measured.** `KEY_TYPED`
  does not carry what an input method committed: the first live attempt produced nothing at all. It
  arrives as `Scene.setOnInputMethodTextChanged`'s committed text, now wired up beside it. AWT needs
  only the one, as `keyTyped`.

`input/TypedCharacterTest` (4 tests): the character reaches the client as a key press whose keycode
answers `GetKeyboardMapping` with it; the mapping change is sent; a character the keyboard carries is
not sent twice; the same character can be typed twice; one keycode is claimed and kept.

**Why it is not ticked: the live check could not be made to happen here, and not because of the
server.** A key event cannot be injected into the host window from a script in this session -
`osascript`'s `keystroke` fails on the process lookup, and a `CGEventKeyboardSetUnicodeString` event
from `tools/host-type.c` (which reports "typed 2 characters") reaches nothing: the observer on the X
side sees no key press at all. The X half is covered by the tests, so what is unverified is the two
lines between the platform's input method and `input.keyTyped`. Finishing it needs a hand on a
keyboard: start the server (`--single-window`, for the reason below), run an application that shows
text, and type Chinese with the host's own input method.

**And the client-side input method needs a mode, which is now measured.** An input method draws its
pre-edit text and candidate list in a window of its own placed over the application's caret - and in
the default one-window-per-client mode every client gets a screen of its own, so that window is drawn
in the *input method's* host window instead. The protocol half survives the split (selections,
properties and sent events are server-wide), the pixels do not.
`PerClientWindowTest.aClientThatIsNotAnApplicationGetsAWindowOfItsOwnToo` drives exactly that: two
connections, the second mapping an override-redirect window over the first client's window, and it gets
a host window and a screen of its own. **So an X input method belongs in `--single-window` mode.** The
same reasoning covers a window manager and a clipboard manager.

**The live check is blocked on the VM, not on the server**: that machine has no `dbus-launch` and no
D-Bus session bus, and `apt-get` has no network, so `ibus-daemon -drx` starts and exits without a word.
The remaining work is: install `dbus` (or run the input method from a desktop session), start `ibus`
against a `--single-window` server, and type into a forwarded application - which the VM has no
`xterm` for either, so the client would be termora's terminal. The host-IME question - macOS composing
into the forwarded window, which is what the work above is about - is answered; the client-side one
needs that environment.

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

### [x] E5. Backpressure to a client that has stopped reading. **M** - done 2026-09-25

**What it was.** Every reply and event was written to the client's socket on whichever thread held the
server lock - the display tick for an event, another client's request thread for a reply. A client that
stopped reading filled its socket buffer, that write blocked, and the lock was never released: **one
hung client froze every other client's screen**, which the README listed as a known limitation and
which is the worst failure mode a display server can have.

**What changed.** `ClientOutput` now has a **bounded queue (1024 messages) and a writer thread of its
own** per connection. Nothing writes to the socket from the caller's thread: the only thread that ever
waits for a client is that client's own, and a queue that fills up means the client has stopped reading
altogether - so it is dropped, with the reason in the log line (`the client is not reading: 1024
messages are already queued for it`) rather than a bare "broken pipe". Order is preserved by
construction: one queue, one thread taking from it, flushed after each message. `flush()` became a
no-op (the writer flushes every message, so nothing has to be pushed) and the request loop's
flush-after-each-request - which existed to break a deadlock this removes - no longer has anything to
do. A close **drains briefly first** (100 ms), because a refusal of the connection setup, a final error
and the farewell a graceful shutdown sends are the last thing a client will hear.

**Measured 2026-09-25.** `net/BackpressureTest` (2 tests, and the item's own Verify): a client that
handshakes, shrinks its receive buffer and then sends requests whose replies it never reads, while
another client draws and reads its own pixels back. With the fix: 2/2 in 0.7 s. **Without it: both
fail** - the second client's round trip dies with `SocketTimeoutException: Read timed out` (the server
frozen, exactly as the item says) and the stalled client is never dropped. `mvn test` 228/228.

**Two traps, and both cost a rewrite of the test.**

- **A test that hangs is not a regression guard.** The first version flooded from the test's own thread
  and, with the fix reverted, blocked there: a socket write has no timeout, so the *test* stopped
  instead of failing - for ten minutes. The flood now runs on a daemon thread of its own and the test's
  own waits are all bounded by read timeouts.
- **The first measurement was a lie, and it was the socket's rather than the server's.** One
  screen-sized `GetImage` reply as "more than any buffer can hold" is not more than any buffer can hold:
  the server absorbed it without blocking, the round trip succeeded, and the test read as "the unfixed
  server is fine". What is past any buffer is *several megabytes* of replies - measured by adding them
  until the freeze appeared.

**Left open, and named in the design notes**: a client that reads *slowly* rather than not at all is
served in order and keeps its connection; there is no per-client bandwidth policy and no coalescing of
pending motion events, which is what a real server does and the next thing to do here if a slow client
ever becomes a real case.
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
