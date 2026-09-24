# Verification tools

The kit the backlog (`docs/backlog.md` §1) refers to: small programs that drive a server, capture what
it drew, and compare that against a reference server. They are **not** part of the server, and nothing
in `mvn test` depends on them - but every item in the backlog ends in a **Verify** step that one of
these is meant to carry out, so keep them working.

## What is here

| what | where | why |
|------|-------|-----|
| the tools | `src/test/java/io/github/oyzh1994/xserver/tools/` | Java, in the test source root so `mvn -o test-compile` compiles them with everything else: a tool that stops compiling is caught instead of rotting |
| the two-machine harness | `tools/vm.sh`, `tools/bed.sh` | start a server here and a client on the VM against it (or against the VM's own Xvfb, for a comparison) |
| the cursor font generator | `tools/gen-cursor-font.py` | regenerates `resource/CursorFont.java` from `cursor.pcf` with PIL - run it on the VM, where the X11 fonts live, and copy the result back |

Run a tool with the test classpath:

```sh
mvn -o test-compile
java -cp target/test-classes io.github.oyzh1994.xserver.tools.WinTree 127.0.0.1 6095
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

The tools, one line each (the backlog's §1 table says what each is *for*):

- `Drive` - XTEST driver: `M x y`, `P n`, `R n`, `K kc`, `KD/KU kc`, `W ms`.
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
- `XProxy` - a byte-level X11 proxy that logs a client's stream against a *real* server.

**One open question about `RawXClient`**: its hand-written `ShapeRectangles` request is refused by
this server as a bad request, so `ShapeClippingTest` sets shapes in process instead. The layout
matches the library's generated writer (minor 1, then operation, destination kind, ordering, pad,
window, offsets), so the next step is to see which request the server decodes - a debug line in
`ShapeExtension.handleRequest`, or `XProxy` in front of the server. Backlog item A2 records it.

## What is missing

The originals lived in `/tmp` and were deleted (by hand, to clear screenshots) on 2026-09-24. What was
restored above is everything whose source survived in a session log; these are **lost** and have to be
re-created from the description if they are wanted (the compiled `.class` files in the old
`/tmp/driveclasses` may still run in the meantime):

- `Keys` - types a string through XTEST, using the keymap from `GetKeyboardMapping`. (A typed string
  is usually what a gesture needs; `Drive K kc` covers single keys.)
- `Shot`, `ShotWin` - `GetImage` of a window or the root into a PNG. `xwd -root` on the VM does the
  same for a `--single-window` server, which is why nothing here replaces it yet.
- `Probe`, `MenuProbe`, `PointProbe` - the raw-protocol probes used for the grab/crossing and menu
  measurements described in `docs/design-notes.md`.
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
