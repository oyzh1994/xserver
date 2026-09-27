# The whole-server pass: what to test, with what, and what "pass" means

Written to be worked through a line at a time by a session that is checking *everything*, rather than
by a session that is fixing one item. `docs/backlog.md` is the history and the measurements; this is
the tour. Each line is **what to do → the tool → the judgement**, and the two sections at the end are
the ones that stop a pass from producing false alarms: [what is deliberately different](#7-what-is-deliberately-different-do-not-report-these)
and [how a run lies](#8-how-a-run-lies-measured-traps).

Tick as you go. A line that fails is worth a `docs/backlog.md` entry of its own; a line that is
*ambiguous* means the judgement column was too loose and should be tightened here.

---

## 0. The baseline, before anything else

| # | do | tool | pass when |
|---|----|------|-----------|
| 0.1 | `mvn -o test` on this machine | Maven | **393 tests, 0 failures, 0 errors, 0 skipped** (a desktop run should skip nothing) |
| 0.2 | `MVN_OPTS=-Djava.awt.headless=true ci.sh` | `ci.sh` | 393 tests, **2 skipped** (exactly `JavaFxBackendTest`'s two toolkit cases) and `BUILD SUCCESS`; it prints the surefire report path |
| 0.3 | `tools/xvfb.sh start` | the reference display | Xvfb `:98` and x11vnc up, `6098` and `5900` listening |
| 0.4 | start this server on `:61`, `--single-window --no-clipboard --no-tray` | `java -cp target/classes:$(cat target/tools-cp.txt) io.github.oyzh1994.xserver.Main --display 61 --geometry 1300x800 --single-window --no-clipboard --no-tray` | it says `display :61 ready` |
| 0.5 | `tools/sweep.sh` | the probe sweep | **15 identical, and the 7 differences are exactly the documented ones** (§7's list in `tools/README.md`) - a *sixth* difference is a finding |
| 0.6 | stop everything: `pkill -f 'io.github.oyzh1994.xserver.Main'`, `tools/xvfb.sh stop`, kill the VM's clients | - | nothing left listening on 6010 or 6062-6099, no `Xvfb`/`x11vnc`/AppImage on the VM (a leftover makes the *next* run fail as if the code were broken) |

`tools/kit.sh <Tool> <args>` is the launcher with the classpath filled in; run `mvn -o test-compile`
first if `tools/` has changed. Probes take `host port` - `127.0.0.1 6061` for the server above,
`192.168.22.147 6098` for the reference.

## 1. The protocol's core, and drawing

| # | do | tool | pass when |
|---|----|------|-----------|
| 1.1 | window tree, attributes, geometry, events | `WinTree`, `Xwd`, `RawXClient`-based tests | `WinTree` prints the same tree against both servers for the same client |
| 1.2 | the rasteriser: lines, arcs, polygons, dashes, fills, clipping | `PolyProbe`, `RasterizerTest` | `PolyProbe` identical to the reference; the test suite's pixel comparisons pass |
| 1.3 | `PutImage`/`GetImage` at every depth, including bit and byte orders | `ImageCodecTest`, `Blocks`/`Colors` on a capture | identical pixels; a depth-8 mask that arrives quantised to `0x00/0x55/0xaa/0xff` is coverage read as colour - a bug, not a client |
| 1.4 | RENDER: formats, pictures, composites, glyphs, masks | `DamageProbe`, `AlphaMaskTest`, `ArgbAlphaTest` | the suite's RENDER cases pass; a masked composite writes pixels (the debug line `-> wrote N pixels` is the instrument) |
| 1.5 | **every window keeps its pixels** (B5's store) and is composited from them | `CompositeProbe` | identical to the reference; a window revealed by a move keeps its contents with no client repaint |
| 1.6 | DAMAGE: report levels, `Subtract`'s repair region | `DamageProbe` | identical to the reference |
| 1.7 | Composite: redirect, `NameWindowPixmap`, overlay | `CompositeProbe` | identical; a redirected window's pixmap holds the new pixels while the screen holds the old |
| 1.8 | Present: a client's finished frame, completion event | `PresentProbe` | identical; the completion is **40 bytes**, not 32 |
| 1.9 | SYNC: counters, fences, `Await` | `SyncProbe` | the documented difference is the system counters (none here, eight there); everything else agrees line for line |
| 1.10 | SHAPE: bounding shape clips drawing, obscuring and hit testing | `PolyProbe`, the suite's SHAPE cases | identical |
| 1.11 | fonts: the standard six, a font file on a font path, font sets | `FontProbe` | identical to the reference for `fixed`,`6x13`,`7x13`,`9x15`,`10x20`,`12x24`, `8x13` (from a file) - metrics, properties, extents and pixels |
| 1.12 | cursors: shapes, glyph cursors, hiding | `CursorProbe`, `GlyphCursorProbe`, `ChangeCursorProbe` | glyph cursors identical for fourteen characters; the cursor **sprite is drawn over the frame, never in the framebuffer** |
| 1.13 | a full render comparison of a real application | `tools/compare.sh` | only the documented differences: root colour (`#E8E8E8` against black), the border this server draws, and blend rounding |

## 2. Input

| # | do | tool | pass when |
|---|----|------|-----------|
| 2.1 | pointer motion, buttons, keys through XTEST | `Drive` (`M x y`, `P n`, `R n`, `K kc`, `W ms`), `Keys` | the client reacts identically to the reference |
| 2.2 | focus: `SetInputFocus`, reverts, a focused window going away, `PointerRoot` | `Focus`, `FocusProbe` | `FocusProbe` identical to the reference, including the revert cases (`kill A`, `unmap A`) |
| 2.3 | crossings: enter/leave, details, modes, the flags byte | `MenuProbe`, `FocusProbe` | identical to the reference - **and the menu gesture must be run fresh** (§8) |
| 2.4 | grabs: pointer, keyboard, passive grabs, owner-events | `GrabProbe`, `GrabCrossingTest` | identical; passive grabs are matched, and a disconnecting client's arms are dropped with it |
| 2.5 | `AllowEvents`: freeze/thaw, the held queue, replay | `AllowEventsProbe` | all five arrangements identical to the reference |
| 2.6 | gravity: `winGravity` and `bitGravity` | `GravityProbe`, `BitGravityProbe` | all eleven values identical |
| 2.7 | auto-repeat: the delay, the rate, off/on, per-key | `XkbProbe setrate/setrepeat`, `xset r rate 200 50` on the VM | the delay and rate read back; repeat off gives **one** press and no run |
| 2.8 | XInput2: version, device list, client pointer, selection, query/warp, device grabs | `XiProbe`, `xinput list` | identical except the documented device-list difference (two masters here, six devices there) |
| 2.9 | XInput2 events: key/button/motion, the 80-byte fixed part, the masks | `XiProbe select`, `XInputTest` | a device event is **120 bytes for a key, 136 for a two-axis motion** - a 68-byte event is not rejected by GDK, it corrupts the application |
| 2.10 | the cursor a client sets, and `HideCursor`/`ShowCursor` | `ChangeCursorProbe`, `CursorProbe` | identical; `ChangeCursorByName` stays refused (measured: a reference does nothing for it either) |
| 2.11 | typing into a real application, in the host's input method | `tools/host-type.c`, the VM's `ibus` | the character arrives; a composed character uses the synthetic keycode and a `MappingNotify` |
| 2.12 | the two apps' own gestures | EasyShell, termora by hand | EasyShell's context menu survives the out-and-back; typing lands; termora the same |

## 3. The extensions

| # | do | tool | pass when |
|---|----|------|-----------|
| 3.1 | RandR: screens, CRTC, outputs, modes, `--prop`, `--listmonitors` | `RandrProbe`, `xrandr` on the VM | the answers a client needs are identical; a *change* request is refused with `BadImplementation` |
| 3.2 | Xinerama: `QueryVersion`, `QueryScreens`, `IsActive` | `XineramaProbe` | the three a client asks agree; the 1.1 extras are deliberately left |
| 3.3 | XKEYBOARD: handshake, map, state, controls, `SetMap`, by-name layout | `XkbProbe`, `setxkbmap`, `xkbcomp`, `xmodmap -pke`, `xev` | `setxkbmap -layout <name>` exits 0 and `xmodmap -pke` is byte-identical to the reference for all seven layouts; `xkbcomp <file> <display>` still loads a map |
| 3.4 | MIT-SCREEN-SAVER and DPMS | `ScreenSaverProbe`, `xset q` | identical except the documented DPMS section (this server implements it, the reference build does not) |
| 3.5 | GLX: handshake, configs, contexts, strings | `GlxProbe`, `glxinfo`, `glxgears` on the VM | `glxinfo` says `direct rendering: Yes`; the extension list is deliberately **empty**; an indirect context is refused |
| 3.6 | XFixes: selections, cursor notifications, regions | `SelectionProbe`, `CursorProbe`, `XFixesNotifyTest` | identical |
| 3.7 | the shape of a refusal | any probe | a request this server does not implement is refused with a *named* error, never accepted-and-dropped and never a decode crash |

## 4. The host window - where this server is not a plain server

| # | do | tool | pass when |
|---|----|------|-----------|
| 4.1 | the window carries the client's title and icon | `host-windows.c`, a real client | the title follows `_NET_WM_NAME`/`WM_NAME`; the icon arrives from `_NET_WM_ICON` |
| 4.2 | resize: the client's window follows the host's drag | `host-drag.c` on an edge | the client's window is resized, and the screen grows first if needed |
| 4.3 | the screen grows, up to `--max-screen`, and never shrinks | `host-drag.c`, `xrandr` | `ScreenChangeNotify` reaches a client that asked; past the limit the drag stops |
| 4.4 | the window's position is remembered per client | `host-windows.c`, restart the server | it reopens where it was left (the *first* position of a session is adopted, not recorded) |
| 4.5 | fullscreen and urgency from the client | `WindowStateProbe` | fullscreen gives the display and gives it back; urgency raises the frame exactly once |
| 4.6 | the clipboard, both ways | `ClipboardProbe` | text pasted on the host pastes in the client and the reverse |
| 4.7 | tray icons | a client that docks one (C9), the menu bar | the icon appears in the macOS menu bar and goes away with its client |
| 4.8 | a file dragged from the Finder into a forwarded application | `tools/HostDragSource.java` or the hand, plus a shared folder and `--drop-mount` | the application receives a path **it can open** and uses the file: the drag must start *inside* the mapped directory (§7) |
| 4.9 | close, detach, re-attach | `host-drag.c` (a click on the close button), `nc` to the control port | a client that closes takes the session; `--detach-on-close` keeps it and `attach` brings it back |
| 4.10 | the fallback chain when no window can be opened | `--backend` combinations, `C11`'s cases | it says what it is doing instead of hanging, and never claims a window it did not open |

## 5. Transports

| # | do | tool | pass when |
|---|----|------|-----------|
| 5.1 | TCP | a client on the VM at `192.168.22.1:61` | it connects and works |
| 5.2 | a unix socket and `--no-tcp`/`--no-unix`/`--local` | a client on this machine | the transports restrict exactly as asked |
| 5.3 | `--auth` and the no-credentials case | `AuthProbe` | a client with no credentials is refused; with the cookie it connects |
| 5.4 | VNC: raw and ZRLE frames, the cursor pseudo-encoding, a password, the clipboard | `--backend vnc`, a VNC viewer, `ZrleEncoderTest` | the screen appears, the pointer is drawn, the clipboard crosses - the recipe is below |
| 5.5 | VNC over WebSocket, in a browser | `--vnc-ws-port`, noVNC | the screen appears with no bridge process |

**The VNC recipe, as one command.** This is the mode where the server opens no window at all and the
whole screen is served over RFB - so it runs on a machine with no display, and what a VNC client's
mouse and keyboard do is drive the X pointer and keyboard (`RfbSession` -> `input.pointerMoved` /
`keyChanged` / `buttonChanged`).

```sh
# A password file is one line; macOS's Screen Sharing insists on a password.
echo 'my-password' > ~/.xserver-vnc-pass
mvn exec:java -Dexec.args="--backend vnc --vnc-port 0 --geometry 1300x800 \
    --vnc-password-file ~/.xserver-vnc-pass --vnc-ws-port 6080"
# the log then says:  VNC on port <N>: connect with vnc://<this machine>:<N>
#                     VNC over WebSocket on port 6080: point noVNC at ws://<this machine>:6080/
```

Then: **macOS** Finder -> Cmd-K -> `vnc://<host>:<N>` (the built-in Screen Sharing); **a phone** any VNC
app; **a browser** noVNC's `vnc.html` at `ws://<host>:6080` - nothing to install, no bridge process.
`--vnc-port 0` takes any free port and prints it, which is the honest way to run it beside anything
else. Before bothering with a viewer, the objective check is one line - a real VNC server answers with
its protocol version:

```sh
python3 -c "import socket; print(socket.create_connection(('127.0.0.1', <N>)).recv(12))"   # RFB 003.008
```

**Either/or with the host window**: `--backend vnc` opens no window (that is what lets it run without a
display), so this and the JavaFX window are two modes rather than two features at once - **and it
implies `--single-window`**, every client sharing the one screen, which is what a VNC-served X display
has always looked like. X clients connect exactly as they otherwise do (`DISPLAY=<host>:<N>`); VNC is
only how a person watches and drives it. **Encodings**: raw, and ZRLE when the client lists it; `Tight`
and `CoRRE` are not implemented, and raw is mandatory for every client, so any viewer works - it is
bandwidth on a slow link that suffers, not compatibility.
| 5.6 | session persistence | `--detach-on-close --control-port N` | clients survive the window, `attach`/`detach`/`status` answer |

## 6. Engineering

| # | do | tool | pass when |
|---|----|------|-----------|
| 6.1 | framebuffer memory is allocated lazily | `IdleScreenMemoryTest` | a connection that only asks questions allocates nothing |
| 6.2 | a client that stops reading is not waited on | `E5`'s cases | the server stays responsive to everyone else |
| 6.3 | the performance paths hold | `E3`'s cases | the opaque-source and A8-mask paths and the damage region are still there (the numbers are in the entry) |
| 6.4 | the suite runs where there is no display | `ci.sh` with `-Djava.awt.headless=true` | 393 tests, 2 skips, success |
| 6.5 | the documents agree with the code | §7 and §8 below | nothing here contradicts the three documents - which is what E6 is |

---

## 7. What is deliberately different (do not report these)

(**Capabilities this server simply does not have** - extensions a client could ask for and the holes
inside the ones it does have - are a separate, measured list: `docs/backlog.md` §9. A pass should not
report those as bugs either, but for the other reason: nobody has built them yet.)

Each of these was measured against a reference and decided; `docs/design-notes.md`'s "Deliberately
not done" and `docs/backlog.md`'s §7 have the measurement.

- **No window-manager semantics**: no decorations, no `_NET_*` beyond C3, no activation protocol.
- **The crossing pair a grab implies** (`NotifyGrab`/`NotifyUngrab`) is not delivered - measured 3/3:
  EasyShell tears its menus down within a millisecond of receiving it.
- **A window appearing under a stationary pointer** is reported as entered at the map (the *pair*
  above is still withheld).
- **The blend rounds; Xorg's fixed-point composition truncates** - a one-unit channel difference.
- **Every window keeps its pixels**, so `backing_store` decides nothing (it is still advertised as
  `WhenMapped`); `save_under` is unadvertised and unimplemented, as the reference is.
- **The pointer cannot leave the host window's viewport**; a warp beyond it is clamped.
- **GLX is advertised by default and is a subset**: no GLX extensions, four visual configs; an
  indirect context is refused. `--no-glx` turns it off.
- **XKB**: a variant is served as its base layout; the compatibility map, indicator names, a
  `SetNames` round-trip, every control other than repeat, `LatchLockState` and XKB events are absent;
  `GetDeviceInfo` is refused (no client in this bed sends it).
- **XI2**: touch, tablets, barriers, the raw event family and the property *writes* are refused; both
  property *reads* are answered "nothing".
- **MIT-SHM is not done** (deliberate, with reasons).
- **`ChangeCursorByName` is refused** - a reference does nothing at all for a name.
- **VNC carries raw and ZRLE only**; `Tight`/`CoRRE` are not implemented.
- **A server restart is out** (D1 detaches the *window*, not the process).
- **The `eventcopy` rewrite in `ClientOutput` is the design**, not a workaround to remove (E1, closed).
- **A drag out of an application is not finished** (C5: the trigger was reverted; the owner has
  parked it).

## 8. How a run lies (measured traps)

1. **A menu or crossing measurement needs a fresh server, a fresh client, and the pointer parked
   first.** Anything less measures the previous run's state - it produced a wrong "this change broke
   the menus" for an afternoon, and the same build is 3/3 with the three controlled.
2. **The pointer and the button state outlive every client.** A probe that ends with a button down
   makes the next run's press a duplicate the server drops.
3. **A leftover server, bed or ssh tunnel on a display the suite binds** (`6010`, `6062-6099`) fails a
   test as `BindException`, which reads exactly like a code regression. `ci.sh` refuses to start when
   one is there.
4. **The reference must be clean**: a client killed with windows mapped leaves them on Xvfb, and the
   next comparison differs in its first crossing for that reason alone. `tools/xvfb.sh stop`/`start`.
5. **The two `bad window 0x0` refusals** in every log are the *client's* (GTK asks at startup, a
   reference refuses them too). Not a finding.
6. **`--glx` changes what a toolkit does with its own drawing** (it takes a GL path and abandons it).
   Comparisons of two builds should say which side had it; it is on unless `--no-glx`.
7. **A client-side A/B needs the same driver and marked logs** - `xdotool` on the VM rather than XTEST
   from here, a line count taken before the interaction, and request profiles compared per
   (extension, minor). `XProxy` does the framing; `tools/tap.py` is the byte-transparent fallback
   (and logs chunks a reader must frame themselves).
8. **EasyShell refuses to start twice** (`/root/.easyshell/db.mv.db` is locked) - kill it first.
9. **The five event type bytes are corrected in `ClientOutput`** (E1): with the rewrite removed *and*
   the published artifact, a `KeyRelease` arrives as a press. If an event type looks wrong, that is
   the pair to check, not the client.
10. **`mvn test`'s incremental compile does not recompile a test when only a main class's signature
    changed** - it shows up as `NoSuchMethodError` in a test that looks untouched, so a signature
    change wants `mvn clean test` once.
