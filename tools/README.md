# Verification tools

The kit the backlog (`docs/backlog.md` §1) refers to: small programs that drive a server, capture what
it drew, and compare that against a reference server. They are **not** part of the server, and nothing
in `mvn test` depends on them - but every item in the backlog ends in a **Verify** step that one of
these is meant to carry out, so keep them working.

## What is here

| what | where | why |
|------|-------|-----|
| the tools | `src/test/java/io/github/oyzh1994/xserver/tools/` | Java, in the test source root so `mvn -o test-compile` compiles them with everything else: a tool that stops compiling is caught instead of rotting |
| the launcher | `tools/kit.sh` | `tools/kit.sh <Tool> <args>` - the tools with the classpath filled in, so no tool has to be pasted in with twenty jars |
| the two-machine harness | `tools/vm.sh`, `tools/bed.sh` | start a server here and a client on the VM against it (or against the VM's own Xvfb, for a comparison) |
| the reference display | `tools/xvfb.sh` | `start`/`stop`/`status` for the VM's `Xvfb :98` plus `x11vnc`: the other side of every comparison, and the three flags it needs are easy to get wrong (below) |
| one-command comparison | `tools/compare.sh` | the six steps a rendering comparison takes - both beds, the same gesture on each, the same capture tool, the `Xwd` diff and the `Blocks` grid - as one command |
| the cursor font generator | `tools/gen-cursor-font.py` | regenerates `resource/CursorFont.java` from `cursor.pcf` with PIL - run it on the VM, where the X11 fonts live, and copy the result back |
| the host-window tools | `tools/host-windows.c`, `tools/host-drag.c`, `tools/host-type.c` | C, built with `clang -framework ApplicationServices`: the first lists the on-screen windows with their bounds (needs no permission), the second makes a left-button drag between two points - which is how a real window is moved or resized by a script, and the only way C7's "remember where the window was" and C1's "the screen grows with the window" could be verified against a real window |

Run a tool with the test classpath - `tools/kit.sh` is that line with the classpath filled in, which
is worth using because pasting the twenty jars by hand produces `command too long` from the shell:

```sh
mvn -o test-compile
tools/kit.sh WinTree 127.0.0.1 6095
tools/kit.sh Drive 127.0.0.1 6095 "M 150 200" "W 300" "P 3" "R 3"
```

Start a bed (server + client), in one of the two modes that matter:

```sh
tools/bed.sh single      # --single-window: a probe/XTest driver can reach the client's windows
tools/bed.sh perclient   # the mode a user runs: one host window per client
tools/bed.sh xvfb        # no server here: the client runs on the VM's Xvfb :98 (the reference)
XSERVER_OPTIONS="-Dxserver.dumpFrame=/tmp/d -Dxserver.dumpFrames=3000" tools/bed.sh single
```

`tools/vm.sh` runs a command on the VM over ssh. The password is read from `$XSSH_PASSWORD` or
`~/.xserver-vm-pass` and is **deliberately not in this repository** - write it once into that file.
`expect` hangs on the password prompt in this environment, which is why the script goes through
`SSH_ASKPASS` instead; long-running remote commands need `setsid nohup … < /dev/null &` or ssh kills
them when the session ends.

Two tools that are not Java, and need building (macOS only):

```sh
clang -o /tmp/winlist tools/host-windows.c -framework ApplicationServices
clang -o /tmp/drag    tools/host-drag.c    -framework ApplicationServices
/tmp/winlist                       # owner | title | x,y WxH, one window per line
/tmp/drag 650 209 950 329          # a left-button drag, as a hand makes it
clang -o /tmp/type tools/host-type.c -framework ApplicationServices
/tmp/type "你好"                    # types text, each character as a Unicode key event
```

`winlist` needs no permission; `drag` posts events into the session and so needs the *Accessibility*
permission for whatever is at the top of the shell's parent chain (the same grant that screen
recording needs, see the testbed notes). Dragging a window by its title bar moves it; dragging its
bottom-right corner resizes it, which is what C1's verification does.

**Killing a client on the VM: put the pattern in brackets.** `pkill -9 -f EasyShell` matches the
command line of the *remote shell running it* - which contains the word - and kills it, so ssh
returns 255 and nothing you asked for happens, with no output to say why. Write
`pkill -9 -f '[E]asyShell'`: it still matches the running application and no longer matches the
command that is doing the killing. (The same trap applies to any `pkill -f` whose pattern appears in
its own command line, on either machine.)

The tools, one line each (the backlog's §1 table says what each is *for*):

- `Drive` - XTEST driver: `M x y`, `P n`, `R n`, `K kc`, `KD/KU kc`, `W ms`.
- `Keys` - types a string through XTEST (`Keys <host> <port> "hello"`, `--delay ms` for a slower
  typist): resolves each character against the keymap read with `GetKeyboardMapping`, presses it
  shifted or unshifted as that mapping says, and reports any character no key carries rather than
  guessing (a Chinese character is one, unless the server has claimed a keycode for it - C10).
- `Shot` - `GetImage` of the root or a window straight to a PNG, with no `xwd` and no X tooling on
  the machine that runs it, so it works in the per-client mode where `xwd` on the VM sees nothing.
- `Wire` - not a tool: the bare-protocol base the probes share, which hands back **every** message in
  the order the server sent it. `RawXClient` cannot be used for a probe, because it finds a reply by
  reading until one turns up and so eats the events a probe is counting.
- `FocusProbe` - drives a three-window tree through a sequence of `SetInputFocus`, pointer warps and
  grabs, printing every focus and crossing event with its detail and mode. This is the `Probe` the
  design notes' focus measurements were made with, and the harness for backlog items A5 and A6.
- `MenuProbe` - the same idea for the menu gesture: maps a popup under a held button, grabs the
  pointer, leaves and re-enters, and prints the crossings - marking any `EnterNotify` that arrives in
  a step where no motion was sent (`! enter without a move`), which is the event shape measured to
  tear a menu down (see the design notes).
- `PointProbe` - walks `QueryPointer` from the root down and prints the chain, which is how "is the
  answer the child of the queried window or the deepest window?" is read off (A4).
- `GravityProbe` - creates a parent and a child with chosen gravities, resizes the parent, and prints
  where the child ended up: the harness for A12, whose whole eleven-value table it produced.
- `GrabProbe` - arms a passive `GrabButton`/`GrabKey` (on the root, or a given window) and then uses
  the pointer or the keyboard, printing every event **both** connections receive. Two connections are
  the point: with the window under the pointer belonging to the *other* client, "the grab took the
  press" can be told from "the press went where it landed" - see A1, and the two arrangements that
  look right and measure nothing.
- `WinTree` - the window tree: geometry, class, map state, override-redirect, titles, transient-for.
- `Focus` - `SetInputFocus` to a window, i.e. the window manager's half of a focus test.
- `Pixel` - one pixel of a PNG as hex.
- `Xwd` - XWD to PNG, or a diff of two XWDs (counts, worst delta, histogram).
- `Blocks` - a grid of per-block difference counts, to find where to look.
- `Colors` - every distinct colour in a rectangle, most common first.
- `Shift` - difference cost per ±4 pixel offset: a shift or a real difference?
- `Rect` - an ASCII map of a rectangle, so a region can be judged without a viewer.
- `Crop` - a magnified crop around a point.
- `AuthProbe` - sends a connection setup with chosen credentials and says what came back: a setup (with
  the vendor and screen count) or the refusal's reason. No arguments means "offer nothing", which is
  what backlog item A7 is about.
- `SelectionProbe` - subscribes to a selection through XFixes and prints every notification it gets,
  while other connections take the selection, destroy its owner window and disconnect: the three
  subtypes, one line each. `SelectionProbe <host> <port> [name]`, the name defaulting to `CLIPBOARD`
  (a client watching something else is checked by naming it - EasyShell watches `_NET_WM_CM_S0`).
- `CursorProbe` - the same for `SelectCursorInput`: two windows carrying different glyph cursors, the
  pointer warped between them, every `CursorNotify` printed. What it is for is the thing about the
  event that is easy to get wrong: the serial names the *cursor*, so it goes back down when the
  pointer returns to one it has shown before.
- `ClipboardProbe` - one end of the clipboard bridge, for checking a live server against the
  host's own pasteboard: `read` prints what a client would paste, `write <text>` takes the selection
  and serves it, `owner` prints who holds it. `write` stays connected - an X selection is lazy, so the
  data has to still be there when the host asks for it.
- `WindowStateProbe` - asks a server for a window state the way a toolkit does: a `ClientMessage` to
  the root, and then a read of the window's own `_NET_WM_STATE` to see what came back. The actions are
  a comma separated list because they share one window - `fullscreen on,off` is the request and its
  withdrawal on one connection.
- `XProxy` - a byte-level X11 proxy that logs a client's stream against a *real* server.

The two XFixes probes are how A8's rules were taken from a reference server. Their output against Xvfb and
against this server is identical once the things a server assigns for itself are normalised away
(opcodes, atom ids, cursor serials, and the absolute clock, which is a different epoch on each
server) - compare the two runs by hand, or with a small normaliser, before believing any difference.

**One open question about `RawXClient`**: its hand-written `ShapeRectangles` request is refused by
this server as a bad request, so `ShapeClippingTest` sets shapes in process instead. The layout
matches the library's generated writer (minor 1, then operation, destination kind, ordering, pad,
window, offsets), so the next step is to see which request the server decodes - a debug line in
`ShapeExtension.handleRequest`, or `XProxy` in front of the server. Backlog item A2 records it.

## What is missing

The originals lived in `/tmp` and were deleted (by hand, to clear screenshots) on 2026-09-24. What was
restored above is everything whose source survived in a session log, and the rest - `Keys`, `Shot`,
and the three probes - was re-created from the descriptions on 2026-09-25 (backlog item E0). Still
missing, and only worth re-creating if something asks for it:

- `SetupDump`, `SetupHex`, `QT` - handshake dumps, for when the setup reply itself is in question.
- assorted image helpers (`Diff`, `DiffStats`, `Compare`, `Find`, `Magenta`, `Count`, `Ascii`, …) -
  `Xwd diff`, `Blocks`, `Colors` and `Rect` cover what they did.

`XProxy` is a **reconstruction** (its header says so): the framing and the message descriptions are
the original's, the opcode/event name table is shorter, and it does not log reply *bodies* - extending
it to do that is step one of backlog item A4.

## Rules that go with the tools

These cost hours each, the first time:

1. **Park the pointer in the same place and the application in the same state before capturing two
   sides.** One capture with the pointer over a toolbar and one without lights up hover states and
   reads as a large, entirely imaginary rendering difference.
2. **Run `Shift` before believing a rendering difference.** A whole-window scan over ±4 pixels says
   whether the two renders disagree in place or are the same picture moved, and those are very
   different bugs.
3. **Capture XWD with the same tool on both sides** (`xwd` on the VM works against Xvfb and against a
   `--single-window` server here), and read it with `Xwd` - Python's PIL refuses the XWD that the VM's
   `xwd` writes.
4. **Two differences are by design** and will always show up: this server's root colour is `#E8E8E8`
   where Xvfb's is black, and it draws a window border where a WM-less real server would not.
5. **The pointer outlives every client, so a probe has to park it before it maps anything.** The
   pointer is not part of a connection: a run that starts wherever the last one left it gets a
   crossing at the map on one run and not on the next, and a diff between two servers then shows the
   *previous* run's difference. `FocusProbe` and `MenuProbe` warp to a point no window of theirs
   reaches before they map, for exactly this reason - and it was found the hard way.
6. **Xvfb needs three flags to be usable as the reference**: `-listen tcp` (a stock build opens no
   TCP at all, so nothing here can reach `:98`), `-ac` (it refuses the kit's hand-written clients,
   which offer no credentials - the failure reads as "the connection setup was refused", i.e. as if
   the display were not running), and *not* `-nolisten unix` (the VM's own clients reach it through
   the unix socket). `tools/xvfb.sh start` does all three.

**A trap in the numbering, worth ten minutes:** the core grab opcodes are contiguous and easy to
shift by one - `GrabPointer` 26, `UngrabPointer` 27, `GrabButton` 28, `UngrabButton` 29,
`ChangeActivePointerGrab` 30, `GrabKeyboard` 31, `UngrabKeyboard` 32, `GrabKey` 33, `UngrabKey` 34.
Sending `GrabKey` where `GrabKeyboard` was meant is silent (a passive grab is recorded, nothing
happens), and sending `UngrabKey`'s opcode with `UngrabKeyboard`'s shorter body is answered with
`BadRequest` - which the probe prints as `ERROR 1 opcode=34`. And a probe that sends `GrabPointer` or
`GrabKeyboard` **must read the reply**, or every event afterwards is attributed to the step before.

**Display numbers, and the trap that costs a confusing test failure.** The suite binds 10 and
66-99: `MotionHistoryTest` takes 90, `GrabCrossingTest` takes **95**, `FocusEventTest` 98,
`KeyboardGrabFocusTest` 83, and others in between. Two consequences:

* a server started by hand for probes should take a display the suite does not - `:91` is what the
  kit's own runs use;
* **`tools/bed.sh`'s default `single`/`perclient` display is 95, which is one the suite wants**, so a
  bed left running while `mvn test` runs makes `GrabCrossingTest` fail with `BindException: Address
  already in use` - a failure that looks like a broken test and is a leftover server. Stop the bed
  (`pkill -f "xserver.Main --display 95"`) before running the suite.
