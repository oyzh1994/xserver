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
| ~~A4~~ | ~~`xdotool` segfaults against this server~~ **done 2026-09-25** (the cause was B1) | correctness | S-M |
| ~~A2~~ | ~~a bounding shape does not clip drawing~~ **done 2026-09-24** | correctness | S-M |
| ~~A11~~ | ~~no keyboard autorepeat~~ **done 2026-09-24** | correctness | S-M |
| ~~A10~~ | ~~`GetMotionEvents` returns nothing~~ **done 2026-09-24** | correctness | S |
| ~~A12~~ | ~~`winGravity` is stored but not applied~~ **done 2026-09-25** (`bitGravity` still open) | correctness | S-M |
| ~~A8~~ | ~~XFixes selection/cursor notifications are never delivered~~ **done 2026-09-24** | correctness | S-M |
| A6 | a keyboard-grab focus change is reported `NotifyNormal` | correctness | S |
| ~~A5~~ | ~~`PointerRoot` focus reporting: the premise was wrong, five measured differences remain~~ **done 2026-09-25** | correctness | M |
| ~~A1~~ | ~~passive grabs are recorded but never matched~~ **done 2026-09-25** | correctness | M |
| ~~A15~~ | ~~a filled polygon's scanline crossings are taken half a pixel low~~ **done 2026-09-25**: the `xlogo` difference is now 0 pixels | correctness | S |
| ~~A9~~ | ~~`IncludeInferiors`, backing store, save-unders~~ **done 2026-09-25** (save-unders measured: the reference does not implement it either) | correctness | M-L |
| ~~A13~~ | ~~`AllowEvents` freeze/thaw: a `Sync` grab stops the device, `AllowEvents` starts it again~~ **done 2026-09-26** (X's grab states, the held queue, the replay, and the pointer's two positions) | correctness | M |
| ~~C1~~ | ~~screen auto-size (grow the screen with the window)~~ **done 2026-09-24** | host window | M |
| ~~C2~~ | ~~raise/activate the host window when a client window appears~~ **done 2026-09-25** (the live A/B is inconclusive: the bed activates it either way) | host window | S-M |
| ~~C4~~ | ~~bridge the host clipboard to the X selections~~ **done 2026-09-24** | host window | M |
| ~~C6~~ | ~~HiDPI automation (`--scale` without hand-editing the client's flags)~~ **done 2026-09-24** | host window | S |
| ~~C7~~ | ~~remember the host window's position per client~~ **done 2026-09-24** | host window | S |
| ~~C3~~ | ~~fullscreen / maximize / urgency from the client~~ **done 2026-09-24** | host window | S-M |
| C5 | host → X drag and drop (XDND); the drag *out* is still open | host window | M |
| ~~C9~~ | ~~tray icons (`_NET_SYSTEM_TRAY`) in the macOS menu bar~~ **done 2026-09-26** (the server is the tray host; AWT's `SystemTray` needs no JNI) | host window | M |
| [~] C10 | input methods: the host's composing is verified live; the client-side XIM needs dbus on the VM | host window | M |
| ~~C11~~ | ~~a server that cannot open a window~~ **done 2026-09-25**: the hang, the silence and the end of the fallback chain | host window | S |
| ~~B2~~ | ~~RandR~~ **done 2026-09-25** | extension | M |
| ~~B3~~ | ~~Xinerama~~ **done 2026-09-25** (the three a client asks; the 1.1 extras measured and left) | extension | S-M |
| [~] B1 | XKEYBOARD: read, set by a client that compiles its own map (`xkbcomp`), and the controls (repeat delay/rate, `RepeatKeys`); no compiler in the server, so no layout by name | extension | M-L |
| ~~B7~~ | ~~DPMS and the XScreenSaver extension~~ **done 2026-09-25** | extension | S |
| [~] E2 | continuous integration: the suite runs headless and `ci.sh` is the one command; the pipeline is not wired to a host yet | engineering | S |
| [~] E4 | per-client framebuffer memory: allocation is lazy; releasing while connected is declined with reasons | engineering | M |
| ~~E5~~ | ~~backpressure to a client that has stopped reading~~ **done 2026-09-25** | engineering | M |
| ~~E3~~ | ~~performance: the opaque-source copy path (1.85x), the A8-mask path (2.80x) and the damage region~~ **done 2026-09-25** | engineering | M-L |
| ~~D3~~ | ~~core-font fidelity~~ **done 2026-09-26** (the standard X fonts are the real ones, captured off the reference; a font file on a font path is still not read) | large | M-L |
| B4 | XInput2 | extension | L |
| B5 | DAMAGE / Composite / Present / SYNC (and real translucency) | extension | L |
| C8 | one host window per client toplevel | host window | L |
| E1 | the `eventcopy` generator fix in the protocol fork | engineering | S (in the fork) |
| ~~D1~~ | ~~session persistence and re-attach~~ **done 2026-09-25** (`--detach-on-close` and a control port; a server *restart* is still out, as the item says it is) | large | L-XL |
| [x] D2 | built-in remote transport: **VNC** - raw and ZRLE frames, the Cursor pseudo-encoding, password or not, clipboard both ways, and a browser path with no bridge | large | L-XL |
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

### [x] A4. `xdotool` segfaults against this server. **S-M** - done 2026-09-25

**Done, and the cause was not in this item's plan at all: there was no XKEYBOARD.** The crash is a
null pointer dereference of `XkbGetMap`'s answer, and Xlib's `XkbGetMap` returns null when the
extension is absent - so "no XKEYBOARD" and "a malformed keymap" produce the *same* fault, at
`ldr x3, [x0, #32]` with x0 zero, inside the inlined `_xdo_populate_charcode_map`. Measured three
ways: a gdb backtrace on the VM; a trace of the XKB entry points, which shows exactly two calls
before the fault (`XkbUseExtension`, then `XkbGetMap(which=0x7, device=0x100)`); and a `ctypes` call
to `XkbGetMap` itself, which answers `null` here and a pointer against Xvfb. The fix is B1, and with
it in, every subcommand is clean:

```
getdisplaygeometry        1300 800
getmouselocation          x:100 y:100 screen:0 window:45      (after `mousemove 100 100`)
xdotool key a             (nothing)
search --name xterm       (nothing; no crash)
getactivewindow           abort, with a reported error and exit status 0 - this server has no
                          _NET_ACTIVE_WINDOW, which is the documented deviation, not a fault
```

**Why the gdb backtrace alone was not enough, and what actually found it.** `xdo.c` calls
`XkbGetMap` and does not check it; issue #58 in xdotool's own tracker is the same crash against
another server. The plan this item left - "extend `XProxy` to log reply bodies and diff against
Xvfb" - was never needed: the map exists, so what was left was to compare *the reply* with Xvfb's,
which is what `tools/XkbProbe` does. Bisecting it a section at a time found the real cause, and it
is a trap for anyone else implementing XKB: **a `GetMap` reply has to carry XKB's four mandatory key
types.** With three - one-level, two-level and alphabetic, which is every type this server's keymap
uses - Xlib rejects the reply *whole* and returns null. The types section was byte-for-byte identical
to Xvfb's for those three, which is why nothing about it looked wrong.

**The rest of what was ruled out over the two sessions stays ruled out**: Xinerama (implemented in
B3, and the crash survived it), `QueryPointer`'s answer (a real deviation, fixed in A4old, and not
the cause).

**The two sessions of investigation, kept because each dead end cost one of them:**

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

### [x] A4old. `xdotool` segfaults against this server. **S-M** - superseded: the cause was B1, and A4 has the record

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
the *rate* are only settable through `XkbSetControls`/`XkbSetAutoRepeatRate` - **done 2026-09-25**,
with `GetControls` to read them back, so a client that wants a 200 ms delay asks for one and gets it
(see B1's "controls half") - and `XkbSetDetectableAutoRepeat` is still not there: that is where the
client generates the repeats itself and only needs to be told when a repeat would have started, and it
is the one of the five `PerClientFlags` that changes the *shape* of the events this server sends, so
it would have to come first if any of them is ever implemented. On the core rail, `GetKeyboardControl`
now reports the bitmap and the keyboard's repeat flag separately, which is what the measurement above
showed the reference does - `xset r off` clears the flag and leaves the table alone.

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

### [x] A5. The focus under `PointerRoot`: the premise was wrong, the details are not. **M** - done 2026-09-25

**Done.** `FocusProbe`'s default sequence now prints the same lines against Xvfb and here, and so do
twelve smaller sequences written to pin the corners. The three groups this item listed came down to
**two states that this server kept as one**, plus a chain-order rule:

- **The root window is not `PointerRoot`.** `SetInputFocus` with the root's own id is a state of its
  own: `GetInputFocus` answers that id (where this server answered `1`), the keys stay on the root
  window rather than following the pointer, and the events differ - measured, `"focus A" "warp 50 50"
  "focus pointer"` and the same with `"focus root"` produce completely different sets. A reference
  server *starts* with the root window focused (`xdpyinfo`'s "focus: window 0x1ff"), so this was
  visible from the first request a client made. `InputState` carries the state as a flag beside the
  window; `FocusProbe` gained the root's name (`R`) so a diff can see which of the two a server means.
- **The chain arrives outermost first**, on the way in as well as out. This server sent the incoming
  chain innermost first, which a case with the pointer inside a nested window shows and no other case
  does.
- **The `Pointer` bookkeeping**: the windows the pointer is inside are told when the focus moves into
  or out of the focus the pointer implies - a `FocusOut` with detail `Pointer` on the way out of a
  window, before the ordinary pair, and a `FocusIn` with the same detail on the way in, after it. The
  rule is in `EventDispatcher.pointerLeft`/`pointerStillIn`/`pointerArrived`, and **two of its clauses
  are measured rather than derived**, which is why they are commented there and not here: a change
  *to* `PointerRoot` reports the window the pointer is in even when that window is the one it is
  leaving, and a change to the *root window* does not. The item's own warning stands - "guessing at a
  bookkeeping rule is how the wrong model gets written down" - so the clauses are written as the
  measurements they are, with the sequences named.

**Measured 2026-09-25**, with `tools/FocusProbe` against Xvfb and here: the default sequence, and
twelve more (`"focus A" "warp 50 50" "focus root"`, `"focus A" "warp 50 50" "focus pointer"`,
`"warp 50 50" "focus C" "focus pointer"`, the pointer outside every window, `focus root` from a
window and from a nested one, `focus none` from a window and from PointerRoot, a pointer inside and
outside the window being left) - **all identical line for line**. `MenuProbe` is still identical too,
which is §7's gate for this area. `input/FocusReportingTest` (6 tests) pins the rules - **three of
them fail** with the state folded back and the chain order reverted. `mvn test` 248/248.

**Live**: EasyShell and termora, both connected to a `--single-window` server: EasyShell's context
menu survived the six out-and-back passes 6/6, and termora's menu survived 6/6 with the two captures
byte-identical. Neither declares a gravity and neither had an error in the server log beyond the three
already recorded (RandR's `GetOutputProperty` and JavaFX's two asks about window `0x0`).

**Two existing tests changed**, both of which had encoded the old folded model: `FocusEventTest`'s
menu case relied on the server's initial focus being PointerRoot (it is the root window), and its
`givingTheFocusBackToThePointerMovesItToThePointer` asserted the incoming chain innermost first.

**The corner that was left open, measured and fixed 2026-09-25.** `FocusProbe` had no step that
destroys a window, so the revert rules had never been exercised against the reference. It has one now
(`kill A|B|C`), and the first sequence tried found a real difference. With `B` focused inside `A`,
`kill A`:

```
this server                        Xvfb
FocusOut win=B detail=Ancestor     FocusOut win=B detail=Ancestor
FocusIn  win=A detail=Inferior     FocusOut win=A detail=Virtual
FocusOut win=A detail=Ancestor
focus = R                          focus = R
```

The focus reverts in **two steps** here - `B` to its parent `A`, and only then to the root once `A`
goes away too - so a window being destroyed by the very request is told it has gained the focus and
then that it has lost it. Xvfb reverts past the whole subtree in one step. The cause is the order in
`WindowHandlers.destroyWindowTree`: the children were destroyed (and the focus reverted) **before**
the parent was unmapped, so the parent was still viewable when the revert looked for an ancestor to
land on. Unmapping the window before destroying its subtree makes the whole of it stop being viewable
at once, and the revert then walks past all of it - `FocusOut A detail=Virtual` and nothing else.
Measured afterwards: five `kill` sequences (`focus B` then `kill A`, `focus A` then `kill A`, `focus
A` then `focus none` then `kill A`, `focus A` then `kill B`, `focus C` then `kill A`) and the default
sequence are **all identical to Xvfb**, where the first of them was two events out before. `mvn test`
260/260 - the destroy order is used by every test that tears a window down.

**The same corners through the other way out, measured 2026-09-25.** `FocusProbe` gained an
`unmap A|B|C` step beside `kill`, and five sequences (`focus B` then `unmap A`, `focus A` then
`unmap A`, `focus B` then `unmap B`, `focus A` then `unmap B`, `focus C` then `unmap A`) are **all
identical to Xvfb already** - the unmap path reverts *after* the window has stopped being viewable,
so it never had the ordering fault the destroy path had. That is what makes the two-event difference
above a destroy-path bug rather than a rule that was wrong everywhere.

**The original wording, kept for what it measured:**

### [ ] A5old. The focus under `PointerRoot`: the premise was wrong, the details are not. **M**

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

### [x] A15. A filled polygon's scanline crossings were taken half a pixel low. **S** - done 2026-09-25

**What it was**, and it is one character of code: `Rasterizer.fillPolygon` computed each edge's
intersection with the scanline at `y + 0.5` (the pixel's centre) where a reference server takes it at
`y`. The horizontal rule was right on both - a span runs from `ceil(xl)` to `ceil(xr) - 1` - so what
moved was *where the edge crosses*, by half a pixel of the edge's slope: a quarter of a pixel in x for
a slope of one, and a whole pixel for a slope of two. On a nearly straight edge it shows on the rows
where the crossing lands near an integer; on a concave shape it changes the area.

**How it was found, and it was not by reading the code.** E3 recorded a live pixel gate - `xlogo`
drawn on both servers, captured the same way, diffed over the logo's own area - and it left **377 of
46656 pixels differing (0.8%)** with full-scale deltas, checked to be the same with E3's fast path in
and out, so pre-existing and unowned. Chasing it meant answering "which primitive", because the first
guess (a wide diagonal *stroke*) was wrong: with `--log-requests` on, `xlogo` turns out to send **five
`FillPoly` and no lines or arcs at all**.

So the harness is `tools/PolyProbe` (new): it fills a fixed table of polygons into a window and reads
the pixels back with `GetImage`, printing them as text, so the two servers' scan conversion can be
diffed line by line. The polygons are chosen so that the crossings land on every kind of fraction -
slopes of 1 (a control, where every rule agrees), 2, 44/21, 44/17 and 44/45, a quadrilateral with no
axis-aligned edge, a sixteen-gon, and the five-pointed star from the suite - because a fractional part
below a half is the only place the two candidate rules differ at all.

**The first version of the probe proved nothing, and that is the trap worth keeping.** It drew no ink
- twice over, for two independent reasons:
- **`FillPoly` is the one drawing request whose bytes are not where you would guess.** Its shape and
  coordinate-mode bytes come *after* the drawable and the GC, not in the data byte every other request
  has, and it carries **no point count at all** - the server derives it from the request length. The
  first version put the shape at byte 1 and a count at byte 16; the server accepted it and drew
  nothing. The layout is in `VariableLengthDecoders.readFillPoly`, and reading it *first* is what
  would have saved the hour.
- **A window paints its background when it is mapped**, so anything drawn before the map is painted
  over.

Neither failure announces itself: the probe printed a clean grid of dots on both servers and the
diff was **zero differing lines**. A probe that draws nothing reports agreement, so the tool now
prints ink counts and the run is only read when they are non-zero - the same shape as E0's "reply
sequence check" trap, and the reason the first xlogo comparison here was re-run rather than believed.

**Measured 2026-09-25**, three ways:

- **`tools/PolyProbe` against Xvfb and here, line for line: 226 differing lines before the fix and
  zero after**, over the ten polygons. The ink counts per cell agree too - the star fills **34**
  pixels on both, where this server used to fill 28.
- **`xlogo` against Xvfb, over the logo's own 220x220: 377 of 46656 pixels before (0.8%), 0 of 48400
  after.** The whole window is now pixel-identical, not "close".
- `draw/RasterizerTest` grew `polygonCrossingsAreSampledAtTheScanlineAndNotAtItsCentre`, which pins
  the two cases from the probe's own triangle (slope 2, so its crossings land on an integer at every
  scanline): row 2's span ends at 45 and row 12's at 25. **Both assertions fail with the sampling put
  back at `y + 0.5`.** The suite's existing star assertion had to change with it, and the way it had
  been written is the reason this is worth recording: it asserted **28 and 41** - *this server's own
  answers* - while its comment already said "Xorg answers 34 and 47 for the same star". The numbers
  were known and the test was pinned to the wrong ones, so nothing failed when the code was wrong.
  `mvn test` 264/264.

**Live, and this is a rendering change so both clients were looked at**: EasyShell connected, drew its
window and its interface, and its context menu opened and survived the six out-and-back passes **6/6**;
termora connected, drew its window with the menu bar and its Chinese
labels, and its menu survived **6/6**, with **no ERROR lines** in the server log either time. An
A/B of EasyShell's own rendering against Xvfb was attempted and is *not* part of this record: the
second EasyShell cannot start while the first holds `~/.easyshell/db.mv.db` (the H2 lock E0 records),
so the two runs have to be strictly sequential - which `tools/compare.sh` does and a hand-run does not.

**Traps worth keeping.** The half pixel is invisible to every test written from the server's own
model, which is why the star assertion passed for a week while being wrong: a scan-conversion rule can
only be checked against another implementation. And the xlogo difference was attributed once before -
E3's note guessed "geometry rather than blend rounding" and was right, but the geometry it meant was
the *stroke*; a request log is what says which primitive a client actually uses.

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

### [x] A9. `IncludeInferiors` and the backing store. **M-L** - done 2026-09-25

**What it was.** `Picture.subwindowMode` was stored and never consulted, so every picture over a window
behaved as `ClipByChildren`: it read the window's own pixels and stopped where a child began. For a
frame whose content child covers it - every toolkit's toplevel - that meant **no pixels at all**, so a
client copying "what this window shows" through its parent got an empty picture. `backing_store`,
`save_unders` and `XWindow.backingPixel` are still stored and unused.

**What changed (2026-09-25).** One line, and the plan above was right about where: `Picture.refreshClip`
takes the window's region with its children when the mode says `IncludeInferiors`
(`window.coveredRegion()`, which already existed for the "what does this window leave behind" question)
and without them otherwise (`visibleRegion()`). **What is stacked above the window is excluded either
way** - those pixels are not the window's to own - and the window rectangle still bounds it, so
`IncludeInferiors` widens a window to its children rather than to the screen, and a *write* with the
mode is still clipped to the window's own rectangle (the item's trap).

**Measured 2026-09-25.** `resource/IncludeInferiorsTest` (4 tests, in process, on a parent with a
painted child): the default does not see the child; `IncludeInferiors` does, and still sees the parent's
own pixels; a window stacked above the parent is excluded either way; and the corners outside the
window answer nothing. **The one that fails without the change is the second** (it reads 0 where the
child's colour should be, which is exactly the symptom this item is about); the other three are the
guards that say what did not change. `mvn test` 237/237.

**Done 2026-09-25: the backing store, and the save-unders half measured to be nothing to do.**

**Measured first, with a new harness, `tools/backing-store-probe.py`** (Python rather than one of the
Java tools: it needs `XChangeWindowAttributes` with a value list and `XGetImage` on the root, and a
second opinion from *Xlib* is worth more than another hand-built request here). One red window, a blue
one mapped over its middle, then the blue one unmapped, reading the pixel and counting the `Expose`
events:

```
                     Xvfb        here (before)   here (after)
window asks nothing  1 expose    1 expose       1 expose
backing_store=WhenMapped  0 exposes  1 expose   0 exposes
save_under           1 expose    1 expose       1 expose
```

Two things fall out of that table. **The reference implements backing store**, so a window asking for
it is a real difference and not a hypothetical one - and its setup reply says so
(`xdpyinfo`: `backing-store WHEN MAPPED, save-unders NO`). And **the reference does not implement
save-unders**: it advertises `NO`, and its behaviour with a window asking for it is identical to the
behaviour with a window asking for nothing, so this server matching it *is* the implementation.

**What was built.** `XWindow` keeps a buffer for a window whose `backing_store` is `WhenMapped` or
`Always` - allocated on demand, so the memory is the client's decision and not something spent on
every window - and saves the window's visible pixels at the moment something covers it
(`WindowHandlers.captureUnder`, called before the covering paint) and puts them back on the reveal
(`repaintUnder`), which is what makes the reveal need no `Expose`. The region that needed writing
first is `XWindow.contentRegion`: the window's own rectangle minus its children, **ignoring what is
above it**, because at the moment of capture the covering window is already above and the pixels
worth keeping are no longer in the visible region. Three things throw the buffer away, and each is a
way the saved pixels stop being what the window would show: the client drew (**4**), the window moved
or was resized, and the attribute was turned off. `Screen` now advertises `backingStores = 1`.

**The memory question the item raised is answered rather than deferred**: nothing is allocated for a
window that does not ask. The item's warning - "256 clients each keeping a full window's pixels" -
applies to a server that keeps them for everybody, and that is not what this is.

**Measured 2026-09-25**, three ways:

- `tools/backing-store-probe.py` in its three modes, against Xvfb and here: the table above, matching
  in every cell.
- `resource/BackingStoreTest` (3 tests): a window that asked for a store is not exposed when it is
  uncovered and its pixels come back; a window that did not ask is exposed, as before; and a window
  that *drew* while it was covered is exposed too. **The first fails with the restore disabled**
  (`expected: <null> but was: <Expose...>`), and the other two are the guards that say what did not
  change.
- `mvn test` 251/251, `MenuProbe` and `FocusProbe` still identical to Xvfb, and live: EasyShell's
  context menu survived the six out-and-back passes 6/6 and termora's 6/6 with two byte-identical
  captures. Neither client asks for a backing store (measured with `xwininfo -all`: both are
  `NotUseful`), so the change is inert for them - which is the point of doing it through the
  attribute rather than for every window.

**Traps.** The capture has to happen *before* the covering window is painted, and the obvious way to
write it - `XWindow.visibleRegion()` - is wrong at exactly that moment: the covering window is
already mapped, so the region no longer contains the pixels about to be painted over, and the first
version silently saved nothing (the probe's `restored=0`). `saveUnder` and `backingStore` are not
interchangeable: the first saves what is *under* a window for the window below's benefit, the second
saves the window's own pixels. And the advertised capability is part of the contract: a server that
answers `backing-store NO` and then keeps the pixels anyway is lying to a client that reads it.

**Traps.** `GetImage` uses `plane_mask` and its own rules, not `subwindowMode` - so the item's Verify
(`xwd -id <frame>`) is not the check for this half, and the in-process test is. A picture's `sample`
takes a *point*, so a pixel is sampled at its centre (`x + 0.5`); sampling its corner reads the
neighbour.
---

### [x] A13. `AllowEvents` freeze/thaw semantics. **M** - done 2026-09-26: X's grab states, the held queue and the replay
**Corrected 2026-09-25: this item was closed on a measurement of the wrong experiment.** `tools/AllowEventsProbe`
had the grab modes **backwards**. X's `GrabModeSync` is **0** and `GrabModeAsync` is **1** (`X.h`:
`#define GrabModeSync 0` / `#define GrabModeAsync 1` - the opposite way round from `AllowEvents`, where
`SyncPointer` is 1), and the probe passed `1` while printing "pointer_mode = Sync". So every run it
ever made was of an **asynchronous** grab, which freezes nothing by definition: "the reference holds
nothing back" was true of the wrong experiment. The probe now defaults to `GRAB_SYNC`, takes
`asyncgrab` to ask for the other, and names the mode it is using in its output.

**Measured again with the fixed probe, and the reference does freeze.** Against Xvfb, in three
arrangements:

```
active GrabPointer(pointer_mode = Sync), then press, two motions, release
  press / motions / release   -> nothing delivered
  AllowEvents(SyncPointer)    -> ButtonPress delivered
  AllowEvents(AsyncPointer)   -> the two motions and the ButtonRelease delivered
passive GrabButton on the root, pointer_mode = Sync
  the activating press        -> LeaveNotify NotifyGrab and ButtonPress delivered *with* the grab
  the motions and the release -> held
  AllowEvents(SyncPointer)    -> both motions, the release, and EnterNotify NotifyUngrab
  a second press, then AllowEvents(ReplayPointer)
                              -> EnterNotify NotifyUngrab, then the press AND its release delivered
                                 with no NotifyGrab - the event reprocessed as if the grab had not
                                 fired, which is what a replay means
passive GrabKey on the root, keyboard_mode = Sync, two connections
  the key                     -> the grabber is told the KeyPress only; the KeyRelease is held
  AllowEvents(ReplayKeyboard) -> the focused CLIENT is told the KeyPress and the KeyRelease
```

So a `Sync` grab freezes the device, the events that follow are not delivered until `AllowEvents`,
`Sync*` releases the held event to the grabbing client, `Async*` flushes the rest, and `Replay*`
reprocesses them as if the grab had not fired - which is only observable with a second client, the
arrangement the keyboard case uses.

**Done 2026-09-26: modelled from the source, then measured.**

**The puzzle the item was opened on has an answer, and it is X's own**: the states are not a boolean.
`FREEZE_NEXT_EVENT` means "start again, and stop on the *next key or button*" - and a **motion is not
one of those**. So an active grab taken with `Sync` (which starts at `FROZEN_NO_EVENT`) releases
everything up to and including the first button and nothing after it, while a passive grab (which
freezes *on* its triggering press) has already used that event up and releases the rest. The rules came
off `include/inputstr.h` (`GRAB_STATE_*`, with `FROZEN` a *sentinel* that `FREEZE_NEXT_EVENT` sits
deliberately below) and `dix/events.c` (`AllowSome`, `CheckGrabForSyncs`,
`FreezeThisEventIfNeededForSyncGrab`, `ComputeFreezes`, `PlayReleasedEvents`, `EnqueueEvent`,
`ActivateGrabNoDelivery`), and every one of them was then confirmed against the reference server.

What `InputState` grew, following X: a per-device sync state (X's `GrabInfoRec.sync`), a queue of the
events a frozen device holds back, a replay slot, and the two transitions that fill them -
`grabActivated` (a grab's two modes decide which device freezes, and whether it does so on the *other*
device's behalf) and `freezeThisEvent` (the event that arrives while a device was told to freeze on the
next one is what it freezes on, and what a `Replay*` later reprocesses).

**Four things the source settled that guessing would have got wrong, all of them measured:**

- **The pointer has two positions while a device is frozen**, and a client can read both: `QueryPointer`
  answers where the pointer was when it stopped, and the events released afterwards carry where the
  device went (`EnqueueEvent` advances the sprite's *physical* position and not the one clients see).
  Measured with a purpose-built probe: frozen at (200,200) with motions to 220,220 and 400,400 waiting,
  `QueryPointer` says (200,200) and **every** released event carries 400,400 - the second motion's
  position, because the delivered events are stamped with where the device is now. That is the whole
  point of the mode for both of its real clients (a game re-centring the cursor, a remote-desktop
  client redelivering a click), so it is modelled rather than approximated.
- **A replay is not a second grab**: the grab is ended and the event reprocessed as if it had not
  fired, and the search for a passive grab starts **below** the window the replayed grab was held on,
  so the same arm cannot take it again. Measured: the replayed press reaches the window underneath with
  no second `NotifyGrab`, and the replayed key reaches the window that has the focus.
- **A key's repeat follows the device, not the delivery**: X cancels the repeat as a release is
  *enqueued*. Measured the hard way - with the bookkeeping left where it was (at delivery), a device
  frozen with a released key repeated into the queue at 25 events a second and reached **372 events**.
- **A passive grab freezes on the event that fired it**, which is why the press that activates one is
  delivered to the grabbing client and can still be replayed afterwards.

**The queue is bounded** (1024 events, the oldest dropped with a line in the log). The reference has no
bound here; this server refuses to grow without limit, for the same reason its output queue to a client
does (E5).

**Found on the way, and it was contaminating the measurements**: a disconnecting client's *passive*
grabs were never dropped - `releaseGrabs` cleared only active ones - so an arm outlived its client and
kept firing on the root for whoever pressed next. A reference server drops them with the client, and
until this was fixed two runs of the same probe disagreed with each other. Fixed in the same commit.

**Measured 2026-09-26**, three ways:

- `tools/AllowEventsProbe` against Xvfb and here: **all five arrangements - `active`, `passive`,
  `key replay`, and the two `asyncgrab` controls - are identical line for line.** (Window ids are
  mapped to the order they first appear, which `tools/probe-diff.py` now does.) Before the change every
  one of the five differed.
- `input/AllowEventsTest` (5 tests, two clients, XTEST, the display loop running): the freeze holds the
  press, the motions and the release, and `SyncPointer` releases exactly the one event it then stops on
  again; a frozen pointer does not move for `QueryPointer` while what it releases carries the new
  position; a replayed button press reaches the window underneath and the grab does not fire again on
  it; a replayed key reaches the window that has the focus; and the guard that an *async* grab holds
  nothing back. **Four of the five fail with `AllowEvents` put back as a no-op and the freeze disabled**
  - the fifth is the guard, and passes either way, which is the point of it.
- `mvn test` 303/303; `MenuProbe` against Xvfb and here still **identical** (§7's gate); and live, with
  both real clients on a `--single-window` server: **EasyShell's context menu survived the six
  out-and-back passes 6/6** and closed on a click outside it, **termora's 6/6** with the hovered item
  tracking the pointer. The request log has the same three rejected requests as before (RandR's
  `GetOutputProperty` and JavaFX's two asks about window `0x0`) and **not one held event** - neither
  client grabs with `Sync`, which is what makes this change inert for both of them. The only way to
  tell the difference for those two is the reference diff.

**Traps worth keeping, and the first one cost an hour.**

- **The button state outlives the client, exactly as the pointer does** (already a rule in
  `tools/README.md`). A probe run that leaves a button *down* makes the next run's press a duplicate
  the server drops, and the measurement then reads as "the press was never queued" - a whole wrong
  model of the queue was built on it before it was spotted, and the throwaway probe that chased the
  coordinates had to be taught to release the buttons first.
- **An event's coordinates are a decision, not a detail.** "The event's own position" and "where the
  device is now" differ for every held motion, and there is nothing on this server's side of the
  socket that can tell them apart - only the reference server can.
- `GrabModeSync` is **0** and `GrabModeAsync` is **1**, the opposite way round from `AllowEvents`,
  where `SyncPointer` is 1. The probe had them backwards once already (see the correction above), and
  the same mistake reads as a plausible measurement rather than as a bug.

**Still not modelled**: the protocol's time check on `AllowEvents` (a request older than the grab is
ignored) - this server keeps no per-grab timestamp to compare against, and the only clients that send
a time send `CurrentTime`; and Xorg's motion compression in the held queue, which merges consecutive
motions. Measured with the reference: two held motions are delivered as **two** events, both carrying
the last position, so nothing here needs the merge - it would change the count of events a client
sees, and the measurement says not to.

**Unchanged**: `AllowEvents` still validates its mode and does nothing else. Neither EasyShell nor
termora depends on a freeze - A1's `GrabProbe` measured both grabbing with `Async` - so this is a
fidelity gap rather than a failure a user meets.

---

## 3. B - Extensions a reference server has

### [~] B1. XKEYBOARD. **M-L** - read and *set* 2026-09-25 (`xkbcomp` loads a layout); choosing one *by name* needs a compiler in the server

**Done 2026-09-25.** `extension/XkbExtension` advertises `XKEYBOARD` and answers what a client asks
before it will trust the keyboard: the version handshake (`UseExtension`), the keymap (`GetMap`, built
by `input/XkbMap`), the modifier state (`GetState`), and the requests that have no answer here
(`SelectEvents`, `LatchLockState` - accepted, not refused, because a refusal is an X protocol error and
xdotool sends a latch request as part of typing a sequence). `PerClientFlags` answers "none supported".
It is the fix for **A4**: without it, `xdotool` dereferences Xlib's null `XkbGetMap` and dies in its own
startup.

**Measured 2026-09-25**, three ways:

- **`tools/XkbProbe`** (new) against Xvfb and here, field by field: the version, the size range of the
  keycodes, the four types, each keycode's sym map and keysyms, and the modifier map as
  `(keycode, mods)` pairs. The differences from Xvfb that remain are deliberate: three types against
  twenty four, a two-level keymap against a four-level one, and twelve modifier keys against sixteen -
  this server's own keymap, which is the point of building the reply from `Keymap` rather than
  replaying the reference's.
- **`xev` on both servers, the same keycodes driven by XTEST**: 26 lines of keysym output - Escape, a
  letter, Shift, Caps Lock, Shift+Caps Lock, Return and the keypad - **identical line for line**. That
  is the check that matters for a toolkit, because the keysym a toolkit shows comes from XKB's
  translation, not from the core map.
- **`extension/XkbTest`** (5 tests): the version handshake, **at least the four mandatory types**,
  'a' and Escape's keysyms read out of the reply against `Keymap`'s own, the modifier map naming
  Shift_L, the one-way requests answered with nothing rather than refused, and `PerClientFlags`
  replying "none supported". **The four-types test fails with the keypad type removed** (`got 3`),
  and it is the one that matters: a reply with three types is not a smaller map, it is a `null` map.

**Live**: EasyShell and termora both connected and ran - windows mapped, screens rendered, EasyShell's
context menu surviving the six out-and-back passes of the §7 gesture - with **no XKB error in the log**.
Measured what each of them actually asks: EasyShell sends `UseExtension` four times, `SelectEvents`
twice and `PerClientFlags` once, and **never calls `GetMap`** (JavaFX gets its keymap from the core
request, so this item does not change how it types); `xdotool` and `xev` are the clients that read the
map. `mvn test` 242/242.

**Traps, and the first one is the item.** A `GetMap` reply must carry **XKB's four mandatory types**
(`ONE_LEVEL`, `TWO_LEVEL`, `ALPHABETIC`, `KEYPAD`); with fewer, Xlib rejects the reply whole and
`XkbGetMap` returns null, which the calling client cannot tell from the extension being missing. Three
types - which is all this keymap uses - is exactly what the first version had, and its types section
was byte-for-byte identical to Xvfb's first three. The wire layout misleads twice more: the reply's
fixed part is **forty** bytes, not thirty-two, and each keycode's keysyms follow its own descriptor
**inline** rather than living in one pool at the end. Read the layout off a captured reference reply
(`XkbProbe 192.168.22.147 6098 7 dump <file>`) rather than from a header. And the keypad's digit is
what **Num Lock** selects, not Shift: giving the keypad type a Shift entry makes Shift+KC79 type `KP_7`
where the reference leaves it `KP_Home`.

**The writing half, 2026-09-25: a layout CAN be set, and by which tool was measured.** The item said
"`SetMap` is refused, so no layout can be selected - `setxkbmap` is still a no-op". The first half was
true and the second was the wrong tool: measured with `setxkbmap -layout de` against this server, the
request it dies on is **`XkbGetKbdByName` (minor 23)** - `X Error of failed request: BadRequest ...
Minor opcode 23 (XkbGetKbdByName)` - which asks the *server* to compile a keymap from a rules file.
That is a server-side `xkbcomp`, and it is not in this project's scope. The tool that sends `SetMap`
is `xkbcomp` itself, and **it now works**: `xkbcomp -w 0 /tmp/de.xkb <display>` exits 0, and keycode
52 comes back `y Y` from `xmodmap -pke` against a server whose default is `z Z` - German, for a
keymap compiled on the VM and loaded into this server. The XKB rail follows, because both rails read
one table: `XkbProbe` shows `symmap kc52 ... = 0x79 0x59`.

What was added for it:

- **`SetMap` applied, keysyms only.** Each keycode's keysyms go into `Keymap` through the same
  `assign` the core `ChangeKeyboardMapping` uses, and clients are told with the core
  `MappingNotify`. The types, actions, behaviours, explicit components, virtual-modifier map and
  real-modifier map are read and dropped: this server has four types of its own and one modifier
  table, and both are what this extension publishes. A client that sets a map with types this server
  cannot represent gets the characters right and everything else as it was.
- **`SetMap` decoded by hand**, because the generated class cannot read a partial one: every list in
  its builder is non-null and a part the request omits is left null, so a request that sets only the
  key symbols - which the protocol allows - fails with `NullPointerException: types is marked
  non-null but is null`. Measured: a one-keycode test request hit that while xkbcomp's all-parts
  request went through. The hand-written reader takes the header, skips the type descriptors and
  reads the symbols; the rest of the body is left unread, which is safe because the connection
  buffers the whole request. **A byte that reads as -8 is not a loop bound**: the first version read
  `nKeySyms` signed, so a 248-keycode map decoded as no symbols at all and the live `xkbcomp` run
  silently changed nothing.
- **`GetKbdByName` answered "not loaded"** instead of being refused: Xlib prints an X error and exits
  on a refusal, so `setxkbmap` died with a protocol error before it could say anything; the protocol
  has a reply for a server that cannot compile, and the client reports "Error loading new keyboard
  description" and stops. Measured: the same command, before and after.
- **`SetCompatMap`, `SetIndicatorMap`, `SetNames` and `SetGeometry` accepted** rather than refused,
  because a keymap compiler sends them on its way to `SetMap` and there is nothing here for them to
  set. `xkbcomp` needs all four to get as far as the map.

**Measured 2026-09-25**: `extension/XkbTest` grew `aClientCanSetTheKeymapThroughXkbAndBothRailsAgree`
(6 tests in the file now): a client's one-keycode `SetMap` changes what `GetMap` answers and what the
server's table holds, and the test **fails when the request is left unhandled** (the server refuses it
and the next request reads an error). `mvn test` 260/260. Live, as above.

**The controls half, 2026-09-25: `GetControls` and `SetControls`, and the repeat delay and rate are
reachable at last.** This is A11's leftover: the core protocol has a request for the per-key repeat
bitmap (`ChangeKeyboardControl`) and *none* for the delay or the rate - those are XKB's, through
`XkbSetAutoRepeatRate`, which is a `SetControls` with one bit set. So before this, a client that
wanted a 200 ms delay could not ask for one, and every client got the server's defaults.

**Measured first, with `XkbProbe` extended** (it decodes a `GetControls` reply field by field and can
send a `SetControls` of its own), against Xvfb and here:

- **The reply's layout was read off a reference reply rather than derived.** It is 92 bytes - the
  long one of the extension - and its length field says 15, which is the core rule (`32 + 15*4`). The
  repeat delay and interval are two byte fields at offsets **20** and **22**: measured by changing the
  rate through `xset r rate 200 50` and watching the bytes move, which is also what fixed the probe's
  own first version - it had `SetControls`' fields four bytes out, so the request looked well formed
  and changed nothing, which is exactly what a request for a control the server *does not* implement
  looks like. The enabled-controls word is at 56 and the per-key bitmap is the last thirty two bytes.
- **`changeControls`' RepeatKeys bit is what makes the request's delay and rate take**, and
  `affectEnabledControls`' same bit is what turns the keyboard's repeat on or off: measured, the
  reference's `enabledControls` goes `0x13a1` → `0x13a0` → `0x13a1` (RepeatKeys is bit 0) with the
  per-key bitmap untouched each time. `xset r off` does the same thing on the core rail, which is
  where the two halves meet: the bitmap and the control are separate, and turning repeat off must not
  wipe the table.
- **The `perKeyRepeat` array in the request is ignored, because the reference ignores it** - measured
  three ways (all zeros, all ones, and beside a rate change) and its bitmap never moved. So per-key
  repeat stays the core protocol's business, which is where this server has had it since A11.

What was built: `InputState` grew the control bit (`repeatKeysEnabled`), the delay and the rate as
settable fields, and one place that packs and unpacks the bitmap (`autoRepeatBitmap` /
`setAutoRepeatBitmap`), which `GetKeyboardControl` and XKB's reply now both read. **The default bitmap
changed with it, and this is the half worth knowing**: it used to be "every key", with "a modifier does
not repeat" applied at press time; it is now "every key the modifier map does not name", which is
what a stock server's own `auto_repeats` list holds, and the press-time rule is gone - so a client that
puts a modifier back into the bitmap gets it to repeat, as it would on a reference server. The
remaining difference from Xvfb's list is this server's own keymap showing through: it names Scroll
Lock and Mode switch as modifiers where the reference's does not, and the reference excludes KP_Enter
and one media key where this one excludes nothing beyond the modifier map.

**Measured 2026-09-25**, three ways:

- `tools/XkbProbe` against Xvfb and here, field by field: every field of the reply decodes the same
  except the three this server answers deliberately differently - the delay and rate are its own
  defaults, `enabledControls` is `0x1` where Xvfb's is `0x13a1` (a control this server does not
  implement is reported as *not enabled*, and its delay as zero, rather than publishing the
  reference's own numbers for behaviour that is not here), and the bitmap differs only where the two
  keymaps do.
- `extension/XkbControlsTest` (3 tests): the delay and rate read back as 660/40 and change to 250/30
  through `SetControls`, with a zero meaning "leave it alone"; the per-key bitmap is byte for byte the
  same on the core and the XKB rail and has the modifier keycodes clear while 'a' is set; and - the
  end-to-end one - a client turns repeat off through XKB, holds a key, and gets **one** press, then
  turns it back on and gets a run again. **All three fail with the two requests left unhandled** (the
  server refuses them and the next request reads the error).
- Live, with both forwarded applications on a `--single-window` server. **EasyShell**: its window
  mapped at 1300x700 and the interface rendered, and its context menu survived the six out-and-back
  passes **6/6**. **termora**: window and content mapped, the interface rendered, menu 6/6. Before
  those, `xset r rate 200 50` from the VM (the table above) and
  `xkbcomp -w 0 /tmp/de.xkb <display>` still exit 0 with keycode 52 coming back `y Y` on both rails.
  `MenuProbe`'s output against Xvfb and here is still **identical**, which is §7's gate for anything
  near the input path - the change is where the repeat decision is made, and that gate is what says it
  did not move anything else. The request log has no new `rejected` lines: the three there are the
  ones already recorded (RandR's `GetOutputProperty` and JavaFX's two asks about window `0x0`).
  `mvn test` 263/263.
- Live, and **the client that needs it is `xset`, not `xkbcomp`** - which is worth recording because
  the note this item was picked up from said the opposite. `xkbcomp`'s whole request list against
  this server, from a `--log-requests` run, is `UseExtension`, `SetMap`, `SetCompatMap`,
  `SetIndicatorMap`, `SetNames`, `SetGeometry`, plus the core `GetKeyboardMapping` and
  `GetModifierMapping`: **it never asks for `GetMap` or `GetControls`**. `xset` does, and in exactly
  the order that makes `GetControls` the prerequisite rather than a nicety - Xlib's own
  `XkbSetAutoRepeatRate` is a Get-modify-Set:

  ```
  xset r rate 200 50   ->  GetControls (minor 6)
                           SetControls (minor 7)
  before: repeatDelay=660 repeatInterval=40     after: repeatDelay=200 repeatInterval=20
  ```

  So the item's original "no client here asks for it" was right about `xkbcomp` and wrong about
  clients in general - and the delay and the rate could not be changed at all before this.

**What is still not here.** *Selecting a layout by name* - `setxkbmap`, and anything else that asks
the server to compile one - because that is a keymap compiler in the server and this is a display
server. The compatibility map, `GetNames` and the indicator names are absent (the four `Set...`
requests above are accepted and dropped, so a client that sets names and reads them back gets
nothing); every keyboard control other than repeat - slow keys, bounce keys, sticky keys, mouse keys,
AccessX, the two internal modifier maps - is accepted by `SetControls` and not applied, and reported
by `GetControls` as not enabled; `LatchLockState` is accepted and does nothing; and no XKB event is
ever sent, because nothing here changes a layout in a way a client would need to hear about.
`XkbMap` publishes four types of its own whatever the client sets, so a map that relies on types this
server has no equivalent for is applied as characters only.

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

### [x] C2. Raise and activate the host window when a client window appears. **S-M** - done 2026-09-25
**What it was.** The host window was made once and never brought forward, so a forwarded application
started while something else was frontmost stayed behind it: grey title bar, no keyboard, and - since
macOS ignores a background application's cursor changes - the platform pointer left visible next to
the sprite.

**What changed**, as the plan recorded here asked: the tick raises the frame when the client's **first
top level window** is mapped, and only after a frame has been presented. `ScreenSession` carries the
two facts (`raisePending`, `framePresented`), `XServer.tick` sets the first where the identity source
goes from nothing to a window and consumes it at the end of a tick, and the backend call is the
`raise()` that C3's urgency hint already used (`stage.toFront()` / `frame.toFront()`, deliberately not
a focus request).

Three things keep it from being a window that steals the front: it fires **once per session** (a
client's later windows, dialogs included, are its own business), it ignores **override-redirect**
windows (the identity source is already the largest non-override-redirect toplevel, so a menu mapped
per keystroke cannot trigger it), and it **waits for a frame**, because raising onto an empty frame is
telling the user their application arrived and showing them nothing. Everything a raise waits for is
the tick's own work, so the raise can happen on a tick that presents nothing new - a window that has
drawn once and then sits still never damages the framebuffer again, and a raise gated on damage would
never happen at all (which is how the first version of this failed its own test).

**Measured 2026-09-25**, `HostWindowRaiseTest` (3 tests, a recording backend): the first window brings
the frame forward, and a `present` call is in the record before the raise; a second, larger window
does not raise again; and a popup does not raise at all. **The first two fail with the raise disabled**
(the popup test passes either way - it asserts that nothing happens, which is also what a server with
no raise does, so it is a guard rather than a test of the fix). `mvn test` 254/254. C3's
`WindowStateTest` was updated for the interaction: its urgency test measured raises from zero and now
measures the *change* the hint makes, because a client window appearing is itself a raise now.

**The live check is inconclusive here, and this is the honest result.** With IDEA frontmost and
EasyShell launched from the VM, the host window came to the front - but it did so **with the raise
disabled as well**. Both oracles agree: `tools/host-windows.c`'s list puts the host window first in
both runs, and the frontmost *application* (`lsappinfo front`) becomes the server's own process in both
runs. A JavaFX stage shown by a stage that is already opening the window activates its application by
itself, so the difference this item is about cannot be produced in this test bed even by removing the
fix. What the tests cover is the ordering and the once-only rules; what is *not* covered by any
measurement here is the case the item was reported from.

**Traps.** `toFront()` on macOS activates the application, which is a visible side effect (the user's
own front window changes) - kept deliberately, because the report is about an application that was not
active. The second trap is the one that cost a rewrite: `raisePending` has to be consumed on a tick
that needs no damage, or a still window never gets its raise.

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
(or termora's terminal) is the thing to try.

**The reason given here for not trying it has since been corrected (2026-09-26, C10's re-measurement):
posted `CGEvent`s *do* reach the host window** - a click and a typed character both arrive, and a
script can even post straight to the server's pid when the window will not come forward. What a script
still cannot do is make it a *file* drag: Finder's drag carries a file promise, and a posted event
carries no files. So the honest state is "the gesture is scriptable in principle, the payload is
not" - and the thing to try is a real drag by hand, with a real client that takes drops.

---

### [x] C9. Tray icons in the macOS menu bar. **M** - done 2026-09-26: the server is the tray host, and the icons are shown in the menu bar

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

**Done 2026-09-26, and the item's own premise was wrong in a way that made it cheaper.**

**No JNI is needed.** The plan said "JavaFX/AWT has no menu-bar API; this needs JNI or a native
helper". JavaFX has none, but **AWT does**: `java.awt.SystemTray` has been implemented on macOS for
years and puts its `TrayIcon` in the menu bar. That was measured before anything was written - a
plain Java process added an icon and a screenshot showed it - and it is what the whole item rests on.

`tray/TrayBridge` is the tray host: it owns `_NET_SYSTEM_TRAY_S0` with a window of the server's own,
publishes the three properties a client reads before docking (`_NET_SYSTEM_TRAY_OPCODE`,
`_ORIENTATION`, `_VISUAL`), hears `SYSTEM_TRAY_REQUEST_DOCK`, reparents the icon window into a tray
window of its own, sends the XEmbed message that says so, and copies the icon's pixels into the menu
bar every 200 ms - there is no X event for "the icon was redrawn", so the pixels are read out of the
framebuffer the client draws into. A click on the menu-bar icon is delivered to the icon window as a
synthetic press and release, which is how every tray host does it. `--no-tray` turns it off, and a
server with no window (headless, VNC) never starts it: a viewer has its own desktop, and a menu bar
that is not on this machine is not something an icon can be shown in.

**The selection is taken at startup, not when a window appears**, and that is not an implementation
detail: an application asks whether a notification area exists while it is starting up and gives up
for good if the answer is no, so a tray owned only once something has been drawn is owned too late.

**The real client found a real bug in this server, and it is the interesting half of the item.**
Java's tray peer creates its icon window, sends the dock request and then **blocks for five seconds**
waiting to be told it has been embedded; when it gives up it throws `TrayIcon couldn't be displayed`.
What it waits for is a `ReparentNotify` on its *own* window - and `WindowHandlers.reparent` delivered
that event to the *new parent* only. Measured by removing the wait's cause: with the reparented window
told as well, the same client prints `added` where it had thrown. That is a protocol gap the tray
found and not a tray-specific hack: X11 says the window that was moved hears about it, and this
server had only ever told the parent.

**Two differences from a panel, both forced by what the screen is here, and both in the design
notes**: the icon is shown in the menu bar *and* stays where the client drew it (this server has one
surface, so a window off the screen has no pixels to read), and a client's own context menu opens on
the X screen where its window is rather than under the menu-bar icon (the menu is a window the client
places itself, and no tray host on this architecture can move it).

**Measured 2026-09-26**, three ways:

- `tray/TrayBridgeTest` (5 tests, a recording backend so nothing touches the real menu bar): the
  selection is owned and the three properties say what they should (the opcode property's value is
  the atom a dock request must carry, the orientation is horizontal, the visual is not zero); a docked
  icon reaches the "menu bar" as the *client's own pixels*, read out of the framebuffer; a click
  arrives at the icon window as a press and a release at the place it was clicked; an icon whose
  window is destroyed leaves the tray and the menu bar is told; and the guard that a backend with no
  menu bar has no tray at all. **Three of the five fail when the dock request is ignored** (the other
  two are guards).
- **Live, with a real Java client** (`SystemTray`, an AppImage-less `TrayClient` on the VM): it prints
  `systemTraySupported=true` and then `added` - it docks, and **a screenshot of the Mac's menu bar
  shows its icon**, a blue disc drawn by the client, next to the status items. Before the
  `ReparentNotify` fix the same client threw `TrayIcon couldn't be displayed`.
- `mvn test` 312/312.

**What the live check did not establish**: a *click* on the menu-bar icon reaching the client. The
click path is pinned in process (the test above), and the menu-bar half was attempted with
`tools/host-drag.c` (which is working: a click at the clock's coordinates opens Notification Centre),
but the tray client kept exiting between the round trips that finding the icon's coordinates and
pressing it take, so the run never had both ends alive at once. Worth doing by hand once: add a tray
icon from a client that stays up, click it, and watch the client's log.

**Traps.**

- **The dock message's words are not where they look.** `data[0]` is the timestamp, the **opcode is
  `data[1]`** and the window is `data[2]`. Reading the opcode out of `data[0]` makes every request
  look like "dock window 0x0" - a warning in the log and an application that reports it cannot show
  its icon, which reads as the tray ignoring it rather than as a message understood differently. Java's
  peer sends it the second way and is what the shape was measured against.
- **32 bit property data is stored in X11's order, which is big endian** - the client swaps what it
  has to. A server-side writer that stores little endian is read correctly by a hand-written probe
  and wrongly by Xlib.
- **A status item is not where you think it is.** Two screenshots with no icon in them "proved" the
  feature broken; the icon was there, at the left of the status area, outside both crops. What settled
  it was comparing a capture with an icon against one without - and the cheapest check of all is a
  second, unmistakable icon (a fixed red disc) beside it.

---

### [~] C10. Input methods: the host's own composing is verified live; the client-side one still needs an environment the VM has not got. **M**

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

**Re-measured 2026-09-26, and the host half now *is* verified live - the item's "could not be made to
happen here" was a measurement failure, not a feature missing.** The recipe, which is worth keeping
because two of its three steps are easy to get wrong:

1. **`tools/host-type.c` works.** The item recorded that a character posted with it "reaches nothing:
   the observer on the X side sees no key press at all". It does reach: with the host window in front
   and a client holding the X focus, `/tmp/type "你好"` produces, **per character**, a `KeyPressEvent`,
   a **`MappingNotifyEvent`** and the synthetic keycode's press and release - in the server's own log.
2. **Grepping the log for the wrong strings is how it looks like nothing happened.** The first run of
   this check "found nothing" because it was greped for `hostEvent`/`keyTyped`, neither of which the
   event dispatcher prints; what it prints is `event KeyPressEvent -> client[…]`. The measurement is
   the log line, not the absence of one.
3. **What the client sees is a keycode, and the character comes from `GetKeyboardMapping`.** A small
   client that reads the mapping after the typing answers it exactly: **keycode 255 carries U+597D**
   (好) - X's own spelling for a character no key has, `0x01000000 | codepoint`. That is the whole
   path: the host composed it, the server claimed a keycode and said so, and a client turns the
   keycode back into the character.

A second route was measured on the way and works too, which matters when the window is awkward to
focus: **`CGEventPostToPid`** with the unicode string attached delivers to a given process what
`CGEventPost(kCGHIDEventTap, …)` delivers to the frontmost window. `tools/host-type.c` posts to the
tap; posting to the server's pid is what a script should do when the window cannot be brought
forward.

**Still open, and it is the client-side half**: no X input method can run on the VM as it is (2), so
"an application typed into through an IME running *on the client*", which is the other reading of the
item's title, has not been exercised. The host's side - the one the author actually needs, since the
Mac's own input method is what types Chinese - is done and verified.

---

### [x] C11. A server whose window cannot be opened. **S** - done 2026-09-25: the hang, the silence and the end of the fallback chain

**Found while doing E2**, which is about running the suite on a machine with no display, and it is
worse than the CI problem that led to it. With the JavaFX backend and no display - measured with
`-Djava.awt.headless=true` on macOS - `QuantumToolkit.startup` parks on a latch whose count never
reaches zero, because the render thread failed first (`ArrayIndexOutOfBoundsException: Index 0 out of
bounds for length 0`, an empty screen list). Nothing in the toolkit's API reports that: no exception,
no return value, no timeout. The tick thread calling it never came back, so:

- no frame was ever shown again, and the last line in the log was
  `nothing to show on display :95: the window holds its last frame`;
- the request threads went on answering clients, so the server looked healthy from the outside;
- the only explanation was a stack trace from a render thread, printed once.

**What changed.** `JavaFxBackend.startupToolkit` starts the toolkit on a daemon thread and waits
**ten seconds** for the FX thread to run the startup's own runnable, which is the one honest signal
that JavaFX works; a wait that ends without it throws with a message that says what happened and what
to do instead (`--backend headless`). And `XServer.tick` catches a backend that refuses to start,
logs one line and stops the server, because a display server that cannot display has one honest thing
to say - `HostWindowFailureTest` (2 tests) pins both halves of that: a backend that cannot open a
window stops the server and lets its client go, and a server nobody has mapped anything on is
untouched by the refusal it never meets. **The first fails with the guard removed.**

**Measured 2026-09-25**, live: with `-Djava.awt.headless=true` and a client that maps a window, the
server used to hang for ever; it now logs

```
WARN  i.g.o.x.BackendFactory - JavaFX is not usable (java.lang.IllegalStateException: the JavaFX
      toolkit did not start within 10s: this machine cannot show a window, so nothing would be
      forwarded. Run with --backend headless to serve clients without one.), falling back to the
      swing backend
```

**The second half of the finding, closed 2026-09-25.** The chain used to end at the Swing backend
whatever the machine was, and a Swing window that cannot be opened fails on the event dispatch thread
- out of the caller's sight - so on a machine where AWT is headless too the server ended up serving
clients with a backend that would never show anything and no line saying so. `BackendFactory` now
asks `GraphicsEnvironment.isHeadless()` before either toolkit: JavaFX failing there, or Swing being
asked for on such a machine, gives the **headless backend** and one line that says what happened.
Measured with `-Djava.awt.headless=true`: the server logs

```
WARN BackendFactory - JavaFX is not usable (... did not start within 10s ...): nothing can be shown
     on this machine, so the server is running headless - clients are served and no window is opened
```

and then serves clients normally (a FocusProbe run against it completes), where before it went on with
a Swing backend that could fail invisibly. `mvn test` 260/260.

## 5. D - Large / product-level

### [x] D1. Session persistence and re-attach. **L-XL** - both halves done 2026-09-25; only the
across-a-server-restart step is not attempted, which the item itself rules out

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

**Done, both halves (2026-09-25).**

- **`--detach-on-close`**: the window closes, the session does not. The clients keep being served -
  their requests are answered, their keys repeat, their pixels land in the framebuffer - and nothing is
  presented, because there is nothing to present to. Off by default, so the behaviour this server has
  always had (close the window, the application was in it) is what a user gets unless they ask for the
  other one.
- **`--control-port N`** is how a window comes back: one command per connection over loopback, `attach`,
  `detach` or `status`, answered in one line. `detach` works on its own too - it is how the window gets
  out of the way without the session ending.
- **What the plan called "eventually per client" is not done, and is not needed yet**: `attach` opens a
  window for every detached session, and since there is one host window per session that is the same
  thing a user means. A command naming one session is the next step if a server ever shows several.

**Traps, all three measured rather than argued.**

- **"Detached" has to be a state of its own, not "the backend is null".** The tick opens a window
  whenever a session has none and its client has drawn something - that is how a client that only asks
  questions avoids a window - so a detached session that was only "a session without a backend" had a
  window opened for it again on the next frame. The tick's guard is the flag, and it is also where a
  window that is still up gets closed (after the lock is released, because closing one waits on the
  toolkit's thread).
- **C2's raise must not fire with no window**, and does not: detaching clears `raisePending`, so a
  client that maps a new top level while nobody is watching does not queue a raise that would fire at
  whatever window comes next.
- **The tick does not need an idle state, and that is a measurement.** Measured on a real server with
  an idle client: **1.4-2.1% of a core detached against 1.4-2.3% attached**. The per-frame work is the
  bookkeeping - which window is the identity, what the viewport is, taking the damage - and it runs
  either way; presentation was never where the cost was. So the item's "can idle to save CPU" is
  answered rather than implemented.

**Measured 2026-09-25**, three ways:

- `test/SessionDetachTest` (4 tests, a recording backend, real connections): closing the window with
  the flag on keeps the client served (a rectangle it draws comes back out of the framebuffer) and
  **no window is opened for it again**; attaching over the control port opens one and its first frame
  is **the whole viewport** rather than the damage; the control port's own `detach` and `status` do
  what they say; and the guard that a session which was never detached is not attached twice. **Two of
  the four fail with the `--detach-on-close` branch disabled** and a third pins the control port
  independently.
- **Live, on the Mac with a real window and EasyShell**: the window's close button was clicked
  (`tools/host-drag.c` with both ends on the same point is a click), the log said
  `window closed and --detach-on-close is on: 2 client(s) keep running with no window`, the host window
  disappeared from `tools/host-windows.c` and `status` read `windows=0 detached=1 clients=2` - while
  EasyShell's own windows were still mapped and answering. `printf 'attach\n' | nc -w 2 127.0.0.1 6200`
  opened a new window, and a screenshot of the Mac showed **EasyShell's interface exactly as it was**
  (the welcome page, the host list) - which is the whole-viewport frame doing its job, since the damage
  that drew it had been consumed while there was no window.
- `mvn test` 272/272.

**Found on the way, and fixed**: `HostWindowPositionTest.aWindowOpensWhereItWasLeftLastTime` called
`.awaitWindow()` and then `window.input()` without `.awaitInput()` - the other tests in the file do both,
this one did not, and the gap is the few microseconds between a backend being handed to a session and
being started. It passed for a week and failed once under a full suite's load, which is what a race
that narrow looks like. One line, and the file's own helper was already there.

---

### [x] D2. Built-in remote transport. **L-XL** - done 2026-09-26: RFB over TCP and over a WebSocket, raw and ZRLE frames, the Cursor pseudo-encoding, a password or open, and the clipboard both ways. `Tight`/`CoRRE` are optimisations left undone

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

**Done 2026-09-25: `--backend vnc`.** `display/VncBackend` is a `DisplayBackend` with no window on
this machine, and `display/RfbSession` is the protocol: RFB 3.8 (3.3 and 3.7 are answered in their own
terms), security `None` or the VNC scheme, **raw encoding only**, true colour only, the client's own
pixel format honoured (8, 16 or 32 bits, either byte order, any channel maxima - a 565 client gets
565), `DesktopSize` for C1's screen growth, and pointer and key events into the same `InputState` the
other backends feed. `--vnc-port` (5900 by default) and `--vnc-password-file`. It implies
`--single-window`: one screen is what a viewer watches.

- **It is request-driven, and that is the whole design.** Nothing is sent until a client asks, and the
  socket is written from that client's own thread, so a viewer that stops reading costs nothing and
  no other client waits for it - the protocol's backpressure and this server's rule (E5) are the same
  thing here. `present` does nothing but add the damage to each session's pending rectangle.
- **A detached server and a VNC server are the same thing**, which is why D1 composed with this for
  free: `isHeadless()` is true and `isClosed()` is never true, so the display tick opens no window and
  the server runs until it is stopped.

**The trap the item predicted, and it was real.** "The damage rectangles are what make it usable" -
except the server marked the *whole screen* as damaged after every drawing request. A viewer's first
version re-sent four megabytes for a keystroke, and 1300x800 of "damage" for a mouse move. The fix
belongs under E3 ("blend only the damage region") and is done there: `Rasterizer` records the region
it actually wrote, and `afterDrawing` marks that instead of the screen. Two of the three things that
went wrong on the way are worth naming: **`afterDrawing` had a second, older whole-screen mark left
under the new one** (so the precision looked like it did nothing for one round of testing), and
**`ImageCodec` built its own rasterizer**, so a `PutImage` was drawn with no clip at all - over
whatever covered the window it went into - and recorded its drawn region nowhere.

**Measured 2026-09-25**, three ways:

- `display/VncBackendTest` (7 tests, a real socket and a hand-written RFB *client*, because somebody
  else's software is the thing this backend is for): a pixel an X client drew arrives in an update;
  the whole screen comes back for a non-incremental request and **the damage, not the screen**, for an
  incremental one - and an incremental request with nothing to send is answered with nothing at all
  rather than by spinning; a 565 client's format is honoured; a pointer press and a key reach the X
  client as the events they are (the keycode via `Keymap.keycodeForKeysym`); the right VNC password
  gets the screen and a wrong one is refused before it; and the DES itself is pinned against a vector
  computed with **openssl**, because the scheme's bit-reversed key is the part every implementation
  gets wrong once and no library does for you.
- `draw/DamageTrackingTest` (3 tests) is the other half and belongs to E3: for eleven primitive
  families - block fill, stippled fill, thin and wide lines, polyline with round joins, polygon, arc
  fill, arc outline, points, rectangle outline, text, `PutImage`, `copyArea` - **every changed pixel is
  inside the damage the request marked**, and the damage is smaller than the surface. A pixel changed
  outside it would be a pixel left stale on somebody's screen, with no error and no log line; that is
  what the test is for.
- **Live, and with two real clients** (2026-09-25/26): EasyShell connected to a `--backend vnc`
  server and drew its interface, and **noVNC** - the standard web VNC client, run in Chrome against
  the same server over a websockify bridge - showed it: the page's own title bar read
  `Connected to xserver :96` and the canvas carried EasyShell's interface, welcome page, host list
  and all. **Input went the other way too**: a click in that canvas moved the X pointer (measured
  with `xdotool getmouselocation` on the VM), and a hand-written RFB client sending a pointer event
  to (1000,700) and then (50,40) moved it to exactly those coordinates.
- **macOS Screen Sharing found a real gap, and it is the reason to use a real client.** It announces
  **RFB 003.003**, not 3.8 - captured on the wire (`RFB 003.003\n`) - and the 3.3 branch of the
  handshake had **no test at all**: 3.3 has no security-type list and no `SecurityResult`, so a
  server that answers a 3.3 client the 3.8 way leaves it waiting for a byte that never comes.
  `VncBackendTest` now pins it (`aClientThatSpeaksThirtyThreeIsAnsweredInItsOwnTerms`), and with the
  branch disabled that test **times out exactly the way the real client sat silent**. Screen Sharing
  itself completed the handshake and the DES authentication against this server three times (the log
  lines name the connection and the screen it was given); what it did *not* do in this environment was
  display the picture, and a control experiment says why that is not this server: **a minimal,
  by-the-book 3.3 server written for the occasion gets the same silence from it** (handshake, then
  nothing at all, and it does not even read a frame pushed at it), and the reference `x11vnc` behaves
  like this server under the same probe - nothing unprompted after `ServerInit`, frames as soon as one
  is asked for.
- **The browser path, and no bridge in front of it** (2026-09-26): `--vnc-ws-port N` serves the same
  sessions carried inside WebSocket frames (`net/WebSocketListener`, and `RfbSession`'s transport is
  now an interface with a socket and a WebSocket under it - the session above cannot tell them apart).
  noVNC in Chrome, pointed at `ws://127.0.0.1:6080` with **no websockify**, showed EasyShell's
  interface with the page titled `Connected to xserver :96`, and a pointer event sent over that
  WebSocket moved the X pointer to exactly the coordinates it named. `net/WebSocketTest` (5 tests) pins
  the parts a browser checks: the RFC's own `Sec-WebSocket-Accept` vector, all three payload-length
  encodings, fragmentation arriving as one run of bytes, a ping answered with a pong, and an unmasked
  frame refused.
- **The clipboard, both ways, out of process** (2026-09-26): with `--backend vnc` running, a viewer
  that sent `ClientCutText` "from the viewer" had it read back out of the X `CLIPBOARD` selection by
  the kit's own `ClipboardProbe read`, and a `ClipboardProbe write` of "from the X session" arrived
  at that viewer as `ServerCutText`. A viewer's copy goes down the path an X client's copy takes -
  into the host's pasteboard and out again - so "somebody copied on the Mac" and "a viewer copied"
  cannot fight over the selection, and the Mac's clipboard gets the viewer's text the way every
  remote-desktop client makes it.
- `mvn test` 283/283.

**ZRLE, done 2026-09-26.** `display/ZrleEncoder` writes the encoding every VNC client understands
after raw: 64x64 tiles, each solid, a packed palette, plain RLE or raw, run through one zlib stream
per session. A client that lists ZRLE (16) gets it; one that does not gets raw frames, which the
protocol requires every client to decode.

- **The measurement**: a 320x240 frame of flat colours and a stripe - exactly what a forwarded
  terminal looks like - comes back under **an eighth** of raw in the test, and a full 1300x800 desktop
  of solid colours is **1092 bytes of tiles compressed to 26**, against 4.16 MB raw. The live check is
  noVNC over the WebSocket rendering EasyShell's interface with ZRLE in play.
- **Four traps, and the last one was found by the live check rather than by a test.** A CPIXEL is
  **three** bytes for the common 32bpp format, not four. The zlib stream belongs to the *connection*
  and is flushed, never finished. A run never crosses a tile. And **the deflate loop must not treat a
  zero-byte result as completion**: the first version stopped there and truncated the stream at
  exactly four output buffers - invisible in every test whose frame fitted in one buffer, and a
  client that closes the connection on a real desktop. There is a test now with a frame whose
  compressed output spans many buffers, and it fails against the old loop.
- **Not emitted**: palette RLE (129..255) and `Tight`/`CoRRE`. A client only has to decode what it is
  sent, so those are optimisations rather than gaps - and the palette/RLE/solid/raw set is what a
  screen of windows actually needs.

**The Cursor pseudo-encoding, done 2026-09-26.** A viewer that lists it (-239) is sent the pointer's
image, so it draws the X session's pointer - an I-beam over a text field, a resize handle on a border
- instead of an arrow of its own. Measured live: an X client's own 24x24 cursor arrived at a viewer as
`hotspot=(7,4), 24x24, 231 masked pixels` - a shape with a real hole in it, not a filled cell.

- **The one thing about the message that is unlike every other**: the rectangle's **x and y are the
  hotspot**, not the corner of a region. Read from the client's own source (noVNC's `_handleCursor`)
  rather than guessed, because a viewer that reads them as a corner draws the pointer in the wrong
  place and looks like a server that is one pixel out.
- **A viewer that connects to an idle screen is told nothing, and that is the design**: the frame
  holds its last contents and there is no sprite to describe (the same state that logs "nothing to
  show"). With an application on the screen the pointer arrives with the next tick - which is why the
  `DisplayBackend` grew `presentCursor`, told every frame rather than only when something drew: a
  viewer that has just connected has to be answered on a screen that is otherwise quiet.
- The mask is one bit per pixel, so a *semi*-transparent cursor edge travels as opaque; RFB's
  `AlphaCursor` would carry it, and no client here needs it.

**What is not done, and what each would take.**
- **The page itself is not served.** The WebSocket carries RFB for a browser, but noVNC's own files
  come from somewhere else (the live check used `python3 -m http.server` for them). Serving a page
  from this server would mean HTTP file serving in a display server, which is a different product
  decision than a transport.
- **`Tight`, `CoRRE` and palette RLE** are not implemented; ZRLE is what a client gets, and it is the
  encoding every VNC client understands after raw.
- **One port for the whole screen**, and therefore one screen: per-client mode would need a port per
  client, which is why `--backend vnc` implies `--single-window`.
- **`--scale`** is the HiDPI knob and works (the screen really is that many pixels), but the *colour
  depth* reduction the item mentions as a bandwidth knob is not implemented.

**Verify.** A real VNC client on another machine, and the browser path in a page with a canvas; the
same scripted gesture on both, compared with the frame dumps. **Half done**: the real client is
`display/VncBackendTest`'s RFB client plus three accepted connections from macOS Screen Sharing; the
browser path waits for the item above.

---

### [x] D3. Core-font fidelity. **M-L** - done 2026-09-26: the standard X fonts are served from tables captured off a reference server

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

**Done 2026-09-26, and the shape of the answer is not the one the plan above describes.**

`fixed`, `6x13`, `7x13`, `9x15`, `10x20` and `12x24` - and the XLFD names they answer to - are now
served from tables **captured off a reference server** (`tools/kit.sh GenBitmapFont`), so the metrics
a client reads through `QueryFont` and `QueryTextExtents` and the glyphs it draws are the reference's
own. Every other name falls back to the Java approximation, which is the old behaviour kept on
purpose: a client that opens a font nobody here has should get something it can draw with.

**Why the reference and not a PCF reader**, which is what the plan said: parsing the font *file* means
reimplementing a format and hoping it agrees with the server a client is comparing against, while
taking `QueryFont`'s answer plus each glyph's ink (drawn into a window and read back with `GetImage`)
cannot disagree with it. The cost is that the generator needs a display with the fonts, which is why
the generated table is checked in - like `CursorFont`, and for the same reason.

**Four things about the capture were measured rather than assumed, and each one looks like a working
capture of a different font.** All four are in the design notes and in the tool's own comments:

- **Text drawn into a one-bit pixmap comes back as the font's default character** - a solid block for
  every code, space included - on a reference server whose `xlsfonts -ll` says the font has all 256
  codes. The capture draws into a window and reads 32 bit pixels instead. Testing "is this pixel
  white" matters more than it sounds: the black the strip is painted with reads back as `#000001`.
- **A character's right bearing is measured from the origin**, not from the end of its advance, so a
  five pixel glyph has `rightBearing` 5 in a six pixel cell. Read the other way, every glyph is one
  pixel wide and the font draws as vertical bars.
- **`CHARINFO`s are padded to sixteen bytes in the reply** (the fixed part is 60 bytes, not 52), and
  **a font's codes do not always begin at zero**: 12x24 starts at one and answers with
  `min-char-or-byte2` 1 and 255 metrics. Either mistake draws a font that is quietly wrong - the
  second one drew every glyph one character early, which looks like a slightly odd typeface.
- **`GetImage` of a rectangle that leaves the window is a `BadMatch`**, not a clipped answer, so the
  capture works in batches; a 1852 pixel strip on a 1300 pixel screen is refused.

**Measured 2026-09-26**, three ways:

- `tools/FontProbe` (new in the kit: `QueryFont` field by field, `QueryTextExtents`, and the pixels of
  a string drawn with `ImageText8` and read back with `GetImage`) against Xvfb and here, for all six
  fonts: **identical line for line** - every character's bearings, width, ascent, descent and
  attributes, the properties (in the reference's own order, with the string valued ones resolved so
  that two servers' atom *numbers* are not compared), the extents, and the rendered pixels.
- `resource/BitmapFontTest` (4 tests, a real connection): the reference's bounds, ascent, descent,
  min/max character codes, property count and per-character metrics; the string metrics and edges
  (`overall-right` is the last glyph's right bearing plus its pen, and `overall-ascent` is the
  *string's* nine pixels and not the font's eleven); the drawn shape of 'H' and 'i'; and the guard
  that a name with no table still opens. **Three of the four fail when the tables are disabled** (the
  fourth is the guard, which passes either way, which is the point of it).
- `mvn test` 307/307.

**What is still not there**, and the README's limitation says so: a font **file** on a font path is
still not read - `SetFontPath`/`GetFontPath` remain bookkeeping - and a name with no table is a Java
font. The tables are for the fonts a client is most likely to ask for by name; the item's plan
("parse X font files for the fonts a user would put on a font path") would generalise this rather than
replace it.

---

### [ ] D4. Audio and other devices - out of scope by default.

Not an X server's job, but users will ask. The usual answer is a side channel (a PulseAudio/PipeWire
client or an RDP-style redirection), not the display protocol. Note it here so the question has an
answer, and keep it out of the plan unless the product is a full remote desktop.

---

## 6. E - Engineering and quality

### [~] E2. Continuous integration. **S** - the suite runs on a machine with no display 2026-09-25; the pipeline itself is the repository host's to configure
**What was wrong, measured.** The item's own note - "the JavaFX backend test opens a real window when
JavaFX is available - on a headless runner it must skip, which the test already does if written that
way; check, and if it does not, make it" - had two halves, and neither was true:

- `JavaFxBackendTest.skipWithoutJavaFx` asked whether JavaFX was **on the classpath** and not whether a
  window could be opened. JavaFX is a dependency, so the class is always there: on a runner with no
  display the test walked straight into `Platform.startup`, and the toolkit's own startup then either
  failed or never returned.
- **`HighDpiTest` was the worse one, and the one nobody was looking at.** It starts a server without
  `--backend headless`, so the server took the default backend and opened a real window *inside the
  test JVM* - every run, on every machine, and on a machine with no display the toolkit's
  `QuantumToolkit.startup` parks for ever. **The whole suite hung there**, not for a second but
  indefinitely: reproduced with `-Djava.awt.headless=true`, with a thread dump showing `main` parked
  in `QuantumToolkit.startup` and the render thread failing in Prism with
  `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0` (an empty screen list). CI would
  have hit a job that never ends rather than a red build.

**What changed.** `JavaFxBackendTest` now also asks `GraphicsEnvironment.isHeadless()`, which is the
platform's own answer - true on a Linux runner with no `DISPLAY`, false on a desktop with one - and
aborts with a message saying a window cannot be opened there. `HighDpiTest` starts its server with
`--backend headless`, like every other test in the suite that starts one: what it is about is the
screen's size and the property it publishes, and neither needs a window. A check over the whole test
tree says it was the only one.

**Measured 2026-09-25**: `mvn -o test -DargLine=-Djava.awt.headless=true` **hangs before** (with the
thread dump above) and **completes after**: `Tests run: 257, Failures: 0, Errors: 0, Skipped: 2` - the
two skips being exactly `JavaFxBackendTest`'s toolkit tests - and `BUILD SUCCESS`. Without the flag,
on a desktop, the same suite runs 257 with **0** skipped, so the guard skips only where skipping is
right.

**`ci.sh`** is what a pipeline runs: one command, `MVN_OPTS=-Djava.awt.headless=true ci.sh` to
reproduce a runner here, and it prints where the surefire report went. It finds Maven the way the
other scripts do (not on `PATH` on the author's machine), and it deliberately does **not** pass `-o`:
a runner's local repository starts empty. It also deliberately does not configure a service - this
repository's remote is Gitee, and a workflow YAML written blind for a host nobody has tried is worse
than no file. The two facts a pipeline needs are in the script's own comments.

**Left open.** The green run on a pull request (and the deliberately broken commit failing it) cannot
be produced here - there is no CI service in this checkout - so the item stays open until the script
is wired to one and seen to run.

**Found on the way, and not fixed: a server with no display serves into the void.** With
`-Djava.awt.headless=true` and the default backend, a client that maps a window makes the tick thread
call `JavaFxBackend.start`, whose toolkit startup never returns: the request threads go on answering
clients, the tick thread is parked, and the log's last words are a raw JavaFX stack trace and
`nothing to show on display :95: the window holds its last frame`. Nothing tells the user that nothing
will ever be shown. That is a backend-selection question rather than a CI one - the honest behaviour
is a line saying the toolkit could not start and either a refusal to start or a fall back to the
headless backend - and it wants its own item.

---

### [~] E4. Per-client framebuffer memory. **M** - the allocation is lazy 2026-09-25; nothing is released while a client is connected
**What it was.** In the default mode every connection gets a `Screen`, and every `Screen` allocated its
`Framebuffer` at the handshake (`width * height * 4` bytes; 4.2 MB at 1300x800, 16.7 MB at
`--scale 2`) - whether or not the connection ever drew anything. With 256 clients that is a gigabyte
of framebuffers, and the connections that paid it include the ones that only ask questions: the kit's
probes, `xdpyinfo`, and `xdotool`, which opens a connection per invocation.

**What changed.** `Framebuffer` allocates its pixels on the first write and not in its constructor, so
a screen that is never drawn into is a size and a colour. Reads do not allocate - an unallocated
screen answers its background, which is what it would hold - and `resize` copes with an array that
does not exist yet. The object keeps its identity, which is what the backends and every picture over
it hold on to; that is the same rule C1's resize follows, and the reason they can be swapped under a
caller at all.

**Measured 2026-09-25**, two ways:

- `IdleScreenMemoryTest` (3 tests, real connections): four clients that connect and ask nothing hold
  no pixels; one client that maps a window allocates **its own** screen and the two others stay
  empty; and twenty idle connections grow the used heap by ~6 MB where twenty framebuffers alone
  would be 81 MB. **All three fail with the allocation put back in the constructor** - including the
  heap one, which then sees the 84 MB.
- Live, against the server on the Mac, twenty `xdpyinfo` connections from the VM: resident memory
  went from 202 MB to **241 MB** with the lazy allocation and to **304 MB** without it - 101 MB
  before, 39 MB after, so about 62 MB of the difference is the framebuffers that are no longer
  touched. What is left is the connection machinery itself (a thread and its buffers each), which is
  E5's bounded queue and not this item.

`mvn test` 257/257. The real clients are unaffected in the way that matters: EasyShell draws, so its
screen is allocated as it always was, and the measurement above shows it (the run that launched it
added a screen's worth).

**What is *not* done, and why.** The other half of the item's plan - *free* a client's framebuffer
when its last window goes away - is deliberately not implemented. A framebuffer holds more than the
windows' content: it holds the root the client may have drawn on (a wallpaper client, `xsetroot`,
and any client that paints its desktop), and it is the store every picture over that screen points
at. Releasing it while the client is still connected would either hide the root's content the next
time a window appeared, or hand the rasteriser an array that is no longer the screen's - the
use-after-free the item warns about, inside the JVM's own heap. The memory it would save is bounded
by the clients that have drawn and then closed every window, which is not the case the item
describes as fatal; the case it does describe, many connections that hold a screen each, is the one
the lazy allocation takes away. **Sharing one framebuffer between screens of the same size** is the
plan's other option and is not available either: in the default mode every client's screen shows the
whole display at the same origin, so two clients' windows are at the same coordinates and a shared
store would show each client the other's windows.

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

### [x] E3. Performance: local blending, fewer copies. **M-L** - done 2026-09-25: the opaque-source path (1.85x), the A8-mask path (2.80x) and the damage region (which was the whole screen)
**Measured with a benchmark of the compositor's own pixel loop**, on one scene and with everything else
equal: two hundred full-screen (1300x800) `FillRectangles` through `Compositor.draw`, which is the one
loop every request reduces to. Op 1 is `Src` and op 3 is `Over`, the two an opaque fill uses.

```
                       before       after
Src   200 full fills   1795 ms      984 ms     (8.98 -> 4.92 ms/frame)
Over  200 full fills   1770 ms      953 ms     (8.85 -> 4.77 ms/frame)
```

**What changed.** One branch in `Compositor.blend`: an opaque source with a full mask under `Src` or
`Over` is the source, so the pixel loop stops doing the double-precision arithmetic for it. That is
not an approximation of what the general path answers - it is what it answers, with `fa = 1` and
`fb = 0`, and the arithmetic on a value that came out of eight bit channels is exact - so the two
agree pixel for pixel by construction. It is the case most of a desktop's drawing takes: an opaque
background paint, a `PutImage` of an opaque image, a `CopyArea`.

**The masked path, done 2026-09-25.** The other half of the same idea, and the one the item warned
about: `Src`/`Over` with an **A8 mask** - which is what every toolkit's text and icons take - was the
general double-precision blend, and "a fast path that changes rounding must not be added quietly".

It is not quiet, and that is what makes it safe to add. `Compositor` now splits into `blend` - the
one the pixel loop calls, fast paths included - and `blendGeneral`, the double-precision form, and
**`draw/BlendFastPathTest` compares the two over the space the fast paths claim**: all 256 mask alphas
against both ends of every channel, the values either side of a rounding boundary (1, 2, 127, 128,
253, 254), both operators, and twenty thousand random pixels including the component-alpha case. The
property is that they never disagree, and the reason they cannot is worth writing down: both fast
paths are the same rational number in integers, `(src*m + dst*(255-m)) / 255`, and a rounding tie
there would need `2A = 255 * odd`, which has no solution. The test is what says so rather than the
argument. Two cases are deliberately *not* taken - a translucent source, and `Over` onto a
translucent destination, which is not a pixel this server composites text onto - and there are tests
that they still come out of the general path.

```
masked Over, 1040000 pixels    fast 2 ms    general 5 ms    (2.80x)
```

**Measured on a real client, because this is the path its text takes.** EasyShell rendered twice
through the same capture - once with the masked fast path in and once with it short-circuited - and
the two are **identical: 0 of 910000 pixels differ** over its window. That is the check the identity
test cannot make: the test compares two functions, this compares a JVM's worth of glyph compositing.

**And the damage itself, which D2 needed and which is this item's last bullet (2026-09-25).** "Blend
only the damage region" turned out to be about *which* region: every drawing request marked the
**whole screen**, so the presentation of a keystroke was a full frame - invisible for a local window,
four megabytes for a VNC viewer. `Rasterizer` now records the region it actually wrote
(`drawnBounds`) and `DrawHandlers.afterDrawing` marks that, and `draw/DamageTrackingTest` holds it to
the only standard that matters: for eleven primitive families, **every pixel that changed is inside
the damage**, checked by comparing the surface against a pristine copy. The two misses found on the
way are recorded in D2 (the older whole-screen mark left under the new one, and `ImageCodec` building
its own rasterizer, which lost both the clip and the record). (b) The JavaFX `WritableImage` round trip turns out
not to exist: `JavaFxBackend.present` copies nothing - the `PixelBuffer` wraps the framebuffer's own
`int[]` and only the union of the damage is handed to the FX thread - so the third bullet of the
plan was answered by looking rather than by changing anything.

**The live pixel gate, measured 2026-09-25.** `xlogo` (static, so the two captures are comparable -
`xclock` is not: its hands move with the time, which cost one run) drawn on both servers at the same
geometry, captured with the same `xwd -root` on the VM, and diffed over the logo's own area:

- **fast path against slow path, same scene: 0 of 46656 pixels differ** - the change is
  pixel-identical, which is what the design says it must be.
- against Xvfb the same run differs in **377 pixels (0.8%)**, and **the slow path differs in exactly
  the same 377** - so it is a pre-existing difference and not this change's. (Worth an item of its
  own if it is ever chased: the deltas are full-scale, so it is geometry rather than blend rounding,
  and the suite's `RasterizerTest` compares against Xorg pixel for pixel.) **Chased and fixed
  2026-09-25 - see A15**: the geometry was the polygon filler's scanline rule, not the blend, and the
  same `xlogo` comparison is now **0 of 48400 pixels** over the logo's own window.

The suite is 268/268 with both fast paths in, and `mvn test` is the only place the blend's identity
property is checked - there is no client that can be made to exercise every mask alpha.

---

### [ ] E1. The `eventcopy` generator fix lives in the protocol fork. **S** (in the fork)

**Blocked on the fork, measured 2026-09-26.** `~/IdeaProjects/other/x11` **is not a git checkout**
(`git status` says "not a repository"), so a fix there is a change nobody can commit or review, and
the artifacts this project builds against live in the local Maven repository - so regenerating and
publishing them also affects the fork's other dependants. Two ways forward, both the fork owner's to
choose: put the fork under version control first, or replace the five `<eventcopy>` entries in
`xproto.xml` with full `<event>` entries (which the generator honours) and re-publish. The workaround
(`ClientOutput.EVENT_NUMBERS`) stays until then, and it is correct as it stands.

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

**Swept 2026-09-25.** The three documents were read against the items that are done, which is what
this item is for, and the sweep is worth recording because it found exactly the drift it warns about:

- **`§7`'s "Deliberate deviations" still listed `IncludeInferiors` as behaving like
  `ClipByChildren`**, with "(A9 is the item)" against it - a week after A9 stopped being true. That
  list is the one a change has to re-measure before it is proposed, so a fixed entry there is worse
  than a missing one: it tells the next reader that something is deliberate which is simply done. The
  bullet is removed, and A9 keeps the record.
- **The design notes' "Deliberately not done" had two more of the same**: "SHAPE's bounding shape
  does not clip drawing" (A2) and "Screen auto-size is not implemented" (C1). Both were corrected in
  this session rather than left for the next one.
- The README's "Known limitations" was checked bullet by bullet and is current: every entry there is
  still true, and the ones this session changed (backing store, XKEYBOARD, the fallback chain) were
  updated with their items.

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
- **No backing store for a window that does not ask for one**: such a window is restored by painting
  the lower window's background and asking it to draw (`Expose`), and a client that ignores the expose
  shows its background there. A window that sets `backing_store` to `WhenMapped`/`Always` is now kept
  and gets its pixels back with no `Expose` (A9, done), and the attribute is advertised as
  `WhenMapped` in the setup reply, which is what a reference server advertises. `save_under` stays
  unadvertised and unimplemented deliberately - the reference does not implement it either, measured.
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
