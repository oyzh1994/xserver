# xserver

A modern X11 display server written in Java, rendered into a JavaFX window.

It speaks the X11 protocol to real clients — Xlib, XCB, Xt, Motif, GTK's core-font path — and
paints their windows into a framebuffer that is blitted to the screen. It is not a compatibility
shim around an existing server: the window tree, the resource model, event delivery, grabs and the
rasteriser are all implemented here.

## Running it

Build with Maven (3.9 or newer) and run:

```sh
mvn compile
mvn exec:java -Dexec.args="--display :1 --geometry 1280x800"
```

Or run `io.github.oyzh1994.xserver.Main` straight from the IDE, which is what the tests do. The only
runtime dependency beyond the JDK is the JavaFX and protocol jars Maven resolves.

Then point any X client at it:

```sh
DISPLAY=:1 xdpyinfo
DISPLAY=:1 xterm -fn fixed
DISPLAY=:1 twm & DISPLAY=:1 xclock & DISPLAY=:1 xcalc &
```

Options:

| Option | Meaning |
| --- | --- |
| `--display :N` | display number; listens on TCP 6000+N and `/tmp/.X11-unix/XN` |
| `--geometry WxH[xD]` | root window size and depth (default 1280x800x24) |
| `--backend NAME` | `javafx` (default), `swing`, `headless`, or `vnc` |
| `--auth [FILE]` | require MIT-MAGIC-COOKIE-1 from `~/.Xauthority` |
| `--no-tcp` / `--no-unix` | restrict the transports |
| `--log-requests` | log every request at debug level |
| `--tick-rate N` | display refresh rate (default 60) |
| `--single-window` | render every client into one shared window (default: one window per client) |
| `--no-clipboard` | do not bridge the host's clipboard to the X `CLIPBOARD` selection |
| `--max-screen WxH` | the largest the screen may be grown to when a window is dragged past it (default 8192x8192) |
| `--scale N\|auto` | screen pixels per logical pixel: `auto` is the host's own scale, 2 on a Retina panel (default 1) |
| `--close-grace-ms N` | time a client gets to close itself when its window is closed (default 2000) |
| `--detach-on-close` | closing the window leaves the clients running with no window instead of ending the session |
| `--control-port N` | listen on `127.0.0.1:N` for `attach`, `detach` and `status` (0: any free port) |
| `--vnc-port N` | the port `--backend vnc` serves on (default 5900; `vnc` implies `--single-window`) |
| `--vnc-password-file F` | require the password in `F` (one line) from VNC clients |

The JavaFX backend falls back to Swing automatically when JavaFX cannot start, and to `headless` -
with a line saying so - where the platform reports no display at all, since neither toolkit can open a
window there. `headless` itself runs the server with no window whatever, which is what tests use and
what `xwd` needs.

`vnc` presents no window either, and serves the screen to VNC clients instead:

```sh
# serve the whole screen on 5900, to anyone who connects
mvn exec:java -Dexec.args="--backend vnc --vnc-port 5900 --geometry 1300x800"

# or ask clients for a password (one line in the file; macOS's Screen Sharing requires one)
echo 'my-password' > ~/.xserver-vnc-pass
mvn exec:java -Dexec.args="--backend vnc --vnc-password-file ~/.xserver-vnc-pass"
```

Any VNC client works (`vnc://host:5900`), including the ones on phones; macOS's Screen Sharing
**requires** a password, so use `--vnc-password-file` if that is the client. What is sent is raw
frames of the damage since the last update, which is fine on a LAN and wasteful on a slow link; the
compressed encodings are on the list in [docs/backlog.md](docs/backlog.md) (D2).

## Architecture

Why the code looks the way it does - the decisions behind the per-client screens, the display tick,
the pointer sprite and the host pointer, the default colours, and what is deliberately not done - is
in [docs/design-notes.md](docs/design-notes.md).

```
io.github.oyzh1994.xserver
├── net/          sockets: accepting clients, and encoding replies/events/errors
├── client/       one object per connection: handshake, request loop, dispatch, resource ids
├── protocol/     decoders for the requests the generated ones get wrong
├── display/      Screen, VisualInfo, and the JavaFX/Swing/headless/VNC backends
├── draw/         Framebuffer, Rasterizer (X11 raster ops), ImageCodec (PutImage/GetImage)
├── resource/     windows, pixmaps, GCs, fonts, cursors, colormaps, atoms, properties
├── event/        event masks, propagation, crossing events, raw event assembly
├── input/        pointer/keyboard state, keymap, cursor sprite
├── clipboard/    the bridge between the host's clipboard and the X selections
├── handler/      the request implementations, grouped by area
└── extension/    extension framework plus BIG-REQUESTS, XC-MISC, SHAPE, XTEST, XFIXES, XINERAMA,
                  RANDR, XKEYBOARD, MIT-SCREEN-SAVER and DPMS
```

Three decisions shape everything else:

**Requests are decoded by generated code, not by hand.** The `x11-protocol-*` artifacts from
[Maven Central](https://central.sonatype.com/artifact/com.github.moaxcp.x11.x11-protocol/x11-protocol-core)
contain a reader and writer for every request, reply, event and error, produced from the XCB
protocol descriptions. Decoding gives a typed object, so dispatch is a `switch` over request types
rather than a table of byte offsets, and the wire format cannot drift from the server's idea of it.
The exceptions are documented in `VariableLengthDecoders` (see below).

**One screen per client connection, and one window per screen.** A connection is given a screen
of its own - its own root window and framebuffer - so each forwarded application appears as a
window sized to what it draws, and no client can draw over another's pixels. The window follows the
client's top level windows: it is the union of their rectangles, so a dialog next to the parent
stays inside it and a small application does not arrive in a screen-sized window with black around
it. `--single-window` restores the older shape, one shared screen with every client on it. A
screen's pixels are allocated when something first draws into it, so a connection that only asks
questions - `xdpyinfo`, `xdotool`, anything that connects to look - costs nothing until it draws.

**One framebuffer per screen, and windows draw straight into it.** A window's contents are not
kept in its own buffer: drawing is clipped to the part of the window that is actually visible, and
obscured content is not preserved. That is X's model, and it is what makes `Expose` events mean
what clients expect. Coordinates are relative to the parent, so every draw request is translated by
the window's absolute position on the way in.

**Obscuring is tracked.** A window's visible region is its rectangle minus everything stacked above
it - the siblings above it at every level of the tree, and its own children, which draw inside it -
and drawing is clipped to that region, in the core rasteriser and through RENDER alike. So a window
does not paint over the dialog on top of it, which is what makes a popup stay on screen instead of
being wiped by the next repaint of the window underneath. When a window is unmapped, destroyed,
moved or restacked, the area it stops covering is handed back: the windows underneath are painted
with their own background and sent `Expose`, because nothing of theirs was kept anywhere.
`VisibilityNotify` reports unobscured, partially obscured and fully obscured as that changes.

**A client that stops reading is dropped, not waited for.** Every connection has a bounded queue of
outgoing messages and a writer thread of its own, so the only thread that ever waits for a client is
that client's own - and a queue that fills up means the client has stopped reading altogether, which
is what gets it disconnected. Before this, messages were written to the socket on the thread that held
the server lock, so one hung client froze every other client's screen.

**Replies are serialised to memory before they reach the socket.** The generated writer for a reply
computes its length field from the wrong base, and short replies are emitted without their trailing
padding; both would desynchronise a real client. `ClientOutput` rewrites the length from the bytes
actually produced and pads what needs padding, in one place.

## What is implemented

Core protocol: windows (create, map, configure, restack, reparent, circulate), the full drawing set
(points, lines, segments, rectangles, arcs, polygons, both text requests, `PutImage`/`GetImage`,
`CopyArea`/`CopyPlane`, `ClearArea`), GCs, pixmaps, fonts, cursors, colormaps, atoms, properties,
selections, keyboard and pointer mapping, focus, grabs (active ones, the implicit grab a press starts,
and passive `GrabButton`/`GrabKey` - a global shortcut fires, with the modifier, button and window
matching the protocol's rules), the pointer's motion history
(`GetMotionEvents`, 256 events per screen), and `SendEvent`.

The keyboard's repeats are the server's own work, as they are on a real one: a held key starts
repeating 660 ms after it goes down and then every 40 ms until it comes up, a modifier never repeats,
and `ChangeKeyboardControl`'s `AutoRepeatMode` is honoured for one key or for the keyboard as a
whole. The delay and the rate themselves are XKB's (`SetControls`, behind `XkbSetAutoRepeatRate` -
the core protocol has no request for either) and are read back through `GetControls`; the per-key
bitmap is on both rails, and it is the same table in both. See `docs/backlog.md` B1 for what of
XKEYBOARD is still missing.

Events: structure notifications, exposes, property and colormap notifications, crossing and focus
events with the full detail lattice and the grab modes, pointer and keyboard events with propagation
and `doNotPropagateMask`, and `ClientMessage` in both directions.

Extensions: `BIG-REQUESTS`, `XC-MISC`, `SHAPE` (rectangles, masks and the five combine
operations), `XTEST` (synthetic input), `RANDR` (one CRTC, output and mode over the whole screen, with
the screen-change notification - `xrandr` prints it and `xrandr --listmonitors` names it, both byte for
byte as a reference server does), `XINERAMA` (the one screen, which is the answer a toolkit's
startup asks for), `XKEYBOARD` (the keymap, the state, and the version handshake - built from the
same table `GetKeyboardMapping` answers from, so a client that reads XKB and one that reads the core
mapping cannot disagree about what a keycode means; `xdotool` needs it and dies in its own startup
without it, and `xev` translates keycodes through it identically to a reference server),
`MIT-SCREEN-SAVER` and `DPMS` (the idle state: the settings a client sets are kept
and read back, `QueryInfo`'s countdowns count against the timeout that was set, a subscriber is told
when the state changes, and `xset q` shows both - though nothing here blanks the display, which is the
host window's business, and DPMS answers `Capable` = no for the same reason), `XFIXES` (cursor images, regions,
`SetWindowClipRegion`, and the selection and cursor subscriptions — a client that asks to be told
when the clipboard changes, or when the pointer moves onto a different cursor, is told, where
before it heard nothing), and `RENDER` — pictures, formats, `Composite` with the full operator table,
`FillRectangles`, `Trapezoids`/`Triangles`/`TriStrip`/`TriFan`, the solid and gradient fills, and
the glyph path (`CreateGlyphSet`, `AddGlyphs`, `CompositeGlyphs8/16/32`) that Xft and cairo draw
their text with.

`--scale 2` draws the screen at twice the pixels for the same physical size, which is what makes a
forwarded application sharp on a Retina panel: the host window becomes half the logical size and one
framebuffer pixel lands on one device pixel. `--scale auto` is the host's own scale factor, so it is
`--scale 2` on such a display. **It is off by default, and the client is the reason**: a client that
does not draw at the resolution it is told about lays itself out for a screen half the size it has
been given. Measured with a forwarded JavaFX application: 1280x800 of screen arrived as a 640x428
window, sharp and at half size. A client that reads `Xft.dpi` - GTK, Qt, an Xft `xterm` - follows by
itself, because a scaled screen publishes it in the root window's resource manager; a JavaFX or AWT
client has to be started with `-Dsun.java2d.uiScale=2` (or its toolkit's equivalent), which no server
can do for it. The server logs a line saying so when it is running unscaled on a scaled display.

The screen grows with the window it shows: dragging the frame's edge past what the screen can show
makes the screen bigger - the framebuffer, the root and the size clients are told all follow, up to
`--max-screen` - so a forwarded application can be given a window bigger than `--geometry`. The screen
never shrinks. A client that watches its root is sent a `ConfigureNotify` for the new size, and one
that listens through RandR is sent `ScreenChangeNotify` - which is what a modern toolkit, browser or
terminal actually waits for.

The host window answers the two things an application asks its window manager for: a client that
goes full screen (`_NET_WM_STATE_FULLSCREEN`) gets the platform's full screen, and a client that sets
its urgency hint (`WM_HINTS`) brings the frame to the front once, without taking the keyboard.
Maximising is left to the client, which resizes its own window for it.

The host window comes back where it was left: where the user arranged it is remembered per client (by
`WM_CLASS`) in `~/.xserver/host-windows.properties`, and the next session opens there - clamped to the
part of the display windows may occupy, so a position remembered on a monitor that is no longer
attached does not put the window out of reach. The window's *size* is not remembered: it follows the
client's own windows, and a client that cares how big it is asks for that itself.

The host's clipboard is bridged to the X `CLIPBOARD` selection, both ways: a client that pastes
gets the host's text (served by the server itself, so no client has to be running), and a client that
copies puts its text on the host's clipboard. Text only, `CLIPBOARD` only - a pasteboard has no
primary selection, and there is no X target for the images and files a pasteboard can also hold.
`--no-clipboard` turns it off; a server with no window (`--backend headless`) never has it.

The host window follows the client it shows: its title and icon are the ones that client publishes
on its top-level window (`_NET_WM_NAME` or `WM_NAME`, and `_NET_WM_ICON`), and dragging its edge
resizes the client's window, which is told with a `ConfigureNotify`. A resize is capped at the
screen, since the framebuffer is not resized with it.

It also comes to the front by itself when an application arrives: the first top-level window a
client maps raises the frame, once per client and only after a frame has been shown, so starting a
forwarded application while something else is frontmost does not leave its window grey and
keyboardless behind it. A menu or a tooltip - anything `override_redirect` - is not a reason to take
the front away from whatever the user is doing.

Verified against real clients: `xdpyinfo`, `xwininfo`, `xprop`, `xlsclients`, `xwd`, `xeyes`,
`xlogo`, `xclock`, `xcalc`, `xmessage`, `xterm` with core fonts and with Xft (`-fa`, which needs
RENDER), and `twm` as a window manager.

## Bugs worked around in the protocol library

These are real defects in `x11-protocol-*:0.22.0`, each confirmed against Xvfb or the protocol
specification, and each worked around rather than patched upstream:

- **Variable length lists are decoded from the wrong arithmetic.** Readers size lists with
  `withInitialCapacity(length - javaStart)`, mixing four byte units with a byte offset. For most
  real requests the argument is negative and Eclipse Collections throws; for the rest it silently
  reads too few items, truncating `PutImage`, `PolyText8` and `QueryColors` data. Sixteen requests
  are decoded in `VariableLengthDecoders` instead.
- **`QueryTextExtents` miscounts by one** for an odd length string, because the padding byte is
  counted as half a character.
- **`GetModifierMapping` replies with the wrong `keycodes-per-modifier`**, writing the total keycode
  count rather than the per-row count. xterm segfaults reading such a reply.
- **`XReply.getLength()`** computes the reply length from the total size instead of the size after
  the header, so every reply's length field is wrong.
- **Replies shorter than 32 bytes** are written without their padding, which a client reads as part
  of the next reply.
- **`ConfigureRequestEvent`'s builder hides `value_mask`** behind private methods, so window
  managers can never see which fields a client asked to change. That event is assembled as raw
  bytes instead.

## The screen is opaque

One invariant is worth stating because breaking it produces a bug that hides from every
server-side test: **the framebuffer only ever stores opaque pixels**. X has no alpha on the screen -
everything is composited onto an opaque root - but the raster operations can produce any 32 bit
value, and `GXclear` yields zero while `GXinvert` turns white into transparent black. A display
backend that honours alpha, as JavaFX does when it uploads a premultiplied pixel buffer, draws
those pixels as holes.

The trap is that `GetImage` reads back colour only, so a transparent-black pixel is still a correct
black: `xwd`, the tests and every protocol-level check agree the screen is right while the window
shows nothing. `Framebuffer` therefore forces every stored pixel opaque, and `XGC` initialises its
foreground to opaque black rather than zero - a client that draws with a graphics context's default
foreground is extremely common.

If a window is ever blank again, `-Dxserver.dumpFrame=<prefix>` writes the JavaFX image and the
framebuffer behind it to PNG files once a second. Comparing the two says immediately whether the
server failed to draw or the display failed to show it.

## A sharp picture on a HiDPI display

A client cannot draw more detail than the screen it is told about, and the host window can only
show what the framebuffer holds. On a display that draws two device pixels per logical one - a
Retina panel, or a 4K screen at 200% - a screen of the size the window *looks* is therefore shown
enlarged, and everything in it is soft. `--scale N` fixes that by making the screen N times the
pixels across for the same physical size:

```
xserver :1 --geometry 1200x800 --scale 2
```

`--geometry` stays the size the window looks; the screen, the framebuffer and the client's windows
are twice that in pixels, the client is told about a screen of twice the resolution, and the host
window shows that larger picture at the smaller logical size - one framebuffer pixel per device
pixel. A client that sizes its interface by the reported resolution then draws at the display's real
resolution, and the result is as sharp as it would be running natively.

Off by default, because a client that ignores the resolution gets a screen twice the size and would
otherwise just be laid out the way it was before. Clients that do ignore it can sometimes be told:
Compose Desktop / AWT applications take `-Dsun.java2d.uiScale=2`, which is what made termora sharp
in the measurement above. Either way the picture is only as detailed as the client draws it - this
option gives it somewhere to draw, it does not enlarge anything.

## RENDER formats are matched by shape, not by identity

A client does not look up a picture format by its id. It looks it up by the *shape* of the pixel
layout - `XRenderFindFormat(type, depth, redMask, greenMask, blueMask, alphaMask, ...)` - and the
masks it compares against are **field widths, not pixel masks**. `RGB24` is `red 0xff, green 0xff,
blue 0xff` with the shifts carried separately, and `ARGB32` is the same with `alpha 0xff`.

Reporting the full pixel mask (`0xff0000`) instead, or letting it be truncated to the 16 bits the
wire format gives it, says every channel is zero bits wide. Nothing errors: the lookup simply fails,
and Xft's per-display `solidFormat` - which it resolves once, as `ARGB32` - comes back null, so every
`XftDrawString` returns without sending a request at all. The window keeps its background and its
cursor and simply never draws text.

The same distinction has to be honoured when a picture is *read*: a drawable's pixels are held as
`0xAARRGGBB`, which is the right reading for a format with colour channels and the wrong one for a
format that is nothing but coverage. An A8 mask's pixel value is its alpha, so reading it as a colour
channel with an opaque alpha makes every pixel of the mask fully covered - and everything composited
through it is drawn as a solid rectangle in the source's colour. That is what an application's icons
and filled paths look like when it happens: blank blocks where the artwork should be. Text is
unaffected, because the glyph path decodes through the format already; only pictures did not.

Two smaller traps in the same area: the `CompositeGlyphs` command stream is in the connection's byte
order (only the glyphset-switch item is always most-significant-byte first), and a glyph is drawn at
`origin - info.x/y`, not `+`.

## Known limitations

The list below is what a user notices; the working list of everything still missing, item by
item with a plan and a way to verify each one, is [docs/backlog.md](docs/backlog.md).

- **No Composite, DAMAGE or XInput2.** `xeyes` prints a warning about the missing
  `XInputExtension` and falls back, and a compositing window manager has nothing to build on.
  **RandR is there**, in the shape this server has: one CRTC, one output and one mode covering the
  whole screen, a real physical size (so a client can compute a DPI), and a `ScreenChangeNotify` to
  the clients that asked for one when the screen grows - which is what C1's screen growth had no way
  to announce before. A request to *change* the screen, or anything about providers and leases, is
  refused with `BadImplementation`: the screen belongs to the host window.
- **XKEYBOARD answers what a client asks before it will trust the keyboard, and no more.** The
  version handshake, the keymap (`GetMap`), the modifier state (`GetState`) and the keyboard's
  controls (`GetControls`/`SetControls`) are there, the requests that have no answer here
  (`SelectEvents`, `LatchLockState`) are accepted rather than refused, and `PerClientFlags` answers
  "none supported" instead of erroring - which is what JavaFX sends as an application starts. A client
  can also **set** the keymap (`SetMap`), applying its keycodes' keysyms to the one table both this
  extension and `GetKeyboardMapping` read, which is how `xkbcomp <file> <display>` loads a chosen
  layout. **The repeat delay and rate are settable through XKB and no other way** - the core protocol
  has no request for either - so `SetControls` with `RepeatKeys` changes them, and a client that wants
  a 200 ms delay gets one. What is *not* there: choosing a layout **by name** (`setxkbmap` asks for
  `GetKbdByName`, which wants a keymap compiler inside the server - it says "not loaded" instead), the
  map's types, actions and behaviours (a client gets its characters right and everything else as it
  was), the compatibility map, the indicator names, `GetNames`, every keyboard control other than
  repeat (slow keys, mouse keys, AccessX and the rest are accepted and not applied, and reported as
  not enabled), the "detectable auto-repeat" a client can ask for, and locking or latching a modifier
  from a client, which would lock the machine's own keyboard for everyone. One caveat worth knowing: a
  `GetMap` reply has to carry XKB's four mandatory key types or Xlib rejects the whole reply, so a
  server that offers *fewer* is not gracefully degraded, it is a `null` keymap the client will fault
  on.
- **SHAPE's bounding shape is honoured for drawing, obscuring and hit testing**: a shaped window is
  painted only inside its shape, its children are clipped by it, and the corners it does not cover are
  given back to the screen underneath. What is still missing is `CreateGlyphCursor` against an
  arbitrary glyph set - the cursor font's own shapes are embedded, another font's outlines are not -
  and `ShapeNotify` details beyond the extents.
- **A picture over a window sees the window's own pixels, not its children's**, unless the client
  asks otherwise: `SubwindowMode` is honoured, so `IncludeInferiors` gives a client what the window
  *shows* - the children painted on top of it - which is what copying the screen through a parent
  window needs. What is stacked above the window is excluded either way.
- **A revealed window is restored by asking it to draw, unless it asked for a backing store.** With
  the default `backing_store`, the area under a window that goes away is repainted with the lower
  window's background and an `Expose`, and a client that ignores the expose shows its background
  there. A window that sets `backing_store` to `WhenMapped` or `Always` has its pixels kept while it
  is covered and gets them back with no `Expose` at all, which is what a reference server does too.
  `save_under` is **not** implemented - measured, and the reference does not implement it either.
- **The VNC backend sends raw frames, and nothing else yet.** `--backend vnc` serves the whole
  screen (and so implies `--single-window`) to any VNC client as uncompressed damage rectangles, with
  RFB's `None` security or a password from a file, and **the clipboard goes both ways** - a viewer's
  copy lands in the X `CLIPBOARD` selection and on the Mac, and a copy in the session reaches the
  viewer. Not there: the compressed encodings (`ZRLE`, `Tight`), the cursor's *shape* (a viewer draws
  its own pointer), and the browser path - a WebSocket and a canvas are a project of their own. A
  viewer on the same machine or a LAN is what raw frames are for.
- **Fonts are Java fonts keyed by X font name**, not rasterised X font files, so glyph shapes and
  metrics differ from a stock X server. Clients are told the metrics the server will actually use.
- **Pointer acceleration is accepted and ignored**: the platform's own acceleration is what the user
  feels, and nothing here adds to it. (`GetMotionEvents` does keep a history now - 256 motions per
  screen - so a client that asks what the pointer did is answered.)
- **A focus change reports FocusIn and FocusOut, but nothing moves the focus on its own.** Every
  window on the way in and out is told, with the relationship lattice in the detail, `None` is a state
  of its own, and a window that stops being viewable gives the focus up the way the last
  `SetInputFocus` asked for - all of it measured against a reference server. A keyboard grab is the
  one thing that does move it: taking one reports the focus arriving on the grab window with
  `NotifyGrab`, ending one reports it going back with `NotifyUngrab`, and a change asked for while a
  grab is held is reported `NotifyWhileGrabbed`. With the focus on PointerRoot the keys follow the
  pointer and no focus events are generated as it moves, which is what a reference server does too:
  measured, and recorded in `docs/backlog.md` (A5), where an earlier claim to the contrary is
  corrected. **The root window is a third state, not PointerRoot**: `SetInputFocus` with the root's own
  id keeps the keys on the root window and `GetInputFocus` answers that id, where PointerRoot sends
  them to whatever the pointer is in - and the two report different events, both measured. The focus
  this server starts with is the root *window*, which is what a reference server starts with.
- **A window that appears under a stationary pointer is entered by the move that follows**, not by
  the map. Xorg reports it at the map; doing so here tears EasyShell's context menus down as they are
  mapped. See the design notes for the measurement.
- **A menu that is left and re-entered used to be dismissed** by EasyShell, where it stays up against
  a real X server. The crossings matched the reference event for event, which is what made it a
  puzzle: the cause was not in them but under them - the client was never told it had the keyboard,
  because `SetInputFocus` generated no focus events. With those delivered the scripted gesture leaves
  the menu up 6 runs out of 6 where it closed 3 out of 3, and the design notes record what that took.
- **The screen saver's settings and state are kept, and nothing blanks.** `MIT-SCREEN-SAVER` and
  `DPMS` are advertised and answer honestly, so `xset s`, `xset dpms` and a client that checks before
  blanking all get an answer rather than "extension not present" - but the display belongs to the host
  window and the system's own screen saver is what blanks it, so the server reports and does not act.
- **`winGravity` is applied, `bitGravity` is not.** A child moves the way its own gravity says when
  its parent is resized, matching a reference server's numbers for all eleven values (and, also like
  the reference, the child is not sent a `ConfigureNotify`). `bitGravity` - what happens to a window's
  own contents when it is resized - has nothing to act on with no per-window backing store: the window
  is painted with its background over the new area and asked to redraw, which is what `NorthWest`
  amounts to anyway.
- **A resize grows the screen, up to `--max-screen`.** The host window is resizable and resizes the
  client's window; a drag past what the screen currently shows makes the screen bigger first, so a
  forwarded application can be given a window larger than `--geometry`. Past `--max-screen` the drag
  stops, and the screen never shrinks again for the session.
- **A file dragged from the desktop onto a forwarded application is offered to it as an XDND drag**,
  which a toolkit application (GTK, Qt, Swing) accepts; an application from before drag and drop -
  `xterm`, `xclock` - has no way to receive one and always will not. Dragging *out* of a forwarded
  application is not implemented: a JavaFX window cannot start a platform drag.
- **Text the host's input method composes reaches a forwarded application as a key press**, which is
  the only thing an X client can be sent: the server claims a keycode for the character and tells the
  clients the mapping changed. What is *not* bridged is the input method's own window - the candidate
  list is the platform's, and a client-side input method (fcitx, ibus) needs `--single-window` for the
  reason below.
- **Each client gets a screen of its own, which anything that is not an application notices.** An X
  input method, a window manager or a clipboard manager draws windows positioned over somebody else's
  window - an input method's candidate list being the clearest case - and in the default mode that
  window lands on the client's own screen, so it is shown in a host window of its own instead of over
  the application. Run such a client with `--single-window`, where every client shares one screen.
- **A screen grows but does not shrink.** Dragging a forwarded window past the edge of the screen
  makes the screen bigger so the client can be told about the larger window; nothing brings it back
  down when the window goes small again, so the extra framebuffer stays allocated for the session.
- **The clipboard bridge is text only, and only `CLIPBOARD`.** Images and files on the host
  pasteboard are not offered to clients (there is no X target for them that a forwarded application
  would have asked for), `PRIMARY` is not bridged, and a client that copies a very large amount of
  text has it delivered through `INCR`. Copying inside a forwarded application replaces the Mac's
  clipboard - the host-to-X direction would otherwise have nothing to notice, since macOS does not
  report that a pasteboard was read.
- **A popup that extends past the client's window is clipped by the host window.** The window is
  sized to the client's own windows - a menu is transient and does not resize the frame it opens in,
  which it used to, growing the window and shrinking it back every time one was opened. Stretching the
  window to fill the screen is what makes room for a menu near its bottom edge.

## Tests

```sh
mvn test
```

- `RasterizerTest` — the raster ops, line and arc geometry, dashes, fills and clipping, checked
  pixel for pixel against Xorg.
- `VariableLengthDecodersTest` — round trips every request the custom decoders handle, by encoding
  with the generated writer and decoding with the server's reader.
- `JavaFxBackendTest` — proves pixels drawn into the framebuffer reach the JavaFX image, which a
  window on a hidden display cannot show.

## Not affiliated with X.Org

The protocol implementation is independent; the X11 specification and the XCB protocol
descriptions were the reference.
