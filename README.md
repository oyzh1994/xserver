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
| `--backend NAME` | `javafx` (default), `swing`, or `headless` |
| `--auth [FILE]` | require MIT-MAGIC-COOKIE-1 from `~/.Xauthority` |
| `--no-tcp` / `--no-unix` | restrict the transports |
| `--log-requests` | log every request at debug level |
| `--tick-rate N` | display refresh rate (default 60) |
| `--single-window` | render every client into one shared window (default: one window per client) |
| `--close-grace-ms N` | time a client gets to close itself when its window is closed (default 2000) |

The JavaFX backend falls back to Swing automatically when JavaFX cannot start, and `headless` runs
the server with no window at all — useful for tests and for driving clients with `xwd`.

## Architecture

Why the code looks the way it does - the decisions behind the per-client screens, the display tick,
the pointer sprite and the host pointer, the default colours, and what is deliberately not done - is
in [docs/design-notes.md](docs/design-notes.md).

```
io.github.oyzh1994.xserver
├── net/          sockets: accepting clients, and encoding replies/events/errors
├── client/       one object per connection: handshake, request loop, dispatch, resource ids
├── protocol/     decoders for the requests the generated ones get wrong
├── display/      Screen, VisualInfo, and the JavaFX/Swing/headless backends
├── draw/         Framebuffer, Rasterizer (X11 raster ops), ImageCodec (PutImage/GetImage)
├── resource/     windows, pixmaps, GCs, fonts, cursors, colormaps, atoms, properties
├── event/        event masks, propagation, crossing events, raw event assembly
├── input/        pointer/keyboard state, keymap, cursor sprite
├── handler/      the request implementations, grouped by area
└── extension/    extension framework plus BIG-REQUESTS, XC-MISC, SHAPE, XTEST, XFIXES
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
it. `--single-window` restores the older shape, one shared screen with every client on it.

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

**Replies are serialised to memory before they reach the socket.** The generated writer for a reply
computes its length field from the wrong base, and short replies are emitted without their trailing
padding; both would desynchronise a real client. `ClientOutput` rewrites the length from the bytes
actually produced and pads what needs padding, in one place.

## What is implemented

Core protocol: windows (create, map, configure, restack, reparent, circulate), the full drawing set
(points, lines, segments, rectangles, arcs, polygons, both text requests, `PutImage`/`GetImage`,
`CopyArea`/`CopyPlane`, `ClearArea`), GCs, pixmaps, fonts, cursors, colormaps, atoms, properties,
selections, keyboard and pointer mapping, focus, grabs (active ones, and the implicit grab a press
starts; a passive grab is recorded but never matched), and `SendEvent`.

Events: structure notifications, exposes, property and colormap notifications, crossing and focus
events with the full detail lattice and the grab modes, pointer and keyboard events with propagation
and `doNotPropagateMask`, and `ClientMessage` in both directions.

Extensions: `BIG-REQUESTS`, `XC-MISC`, `SHAPE` (rectangles, masks and the five combine
operations), `XTEST` (synthetic input), `XFIXES` (cursor images, regions,
`SetWindowClipRegion`), and `RENDER` — pictures, formats, `Composite` with the full operator table,
`FillRectangles`, `Trapezoids`/`Triangles`/`TriStrip`/`TriFan`, the solid and gradient fills, and
the glyph path (`CreateGlyphSet`, `AddGlyphs`, `CompositeGlyphs8/16/32`) that Xft and cairo draw
their text with.

The host window follows the client it shows: its title and icon are the ones that client publishes
on its top-level window (`_NET_WM_NAME` or `WM_NAME`, and `_NET_WM_ICON`), and dragging its edge
resizes the client's window, which is told with a `ConfigureNotify`. A resize is capped at the
screen, since the framebuffer is not resized with it.

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

Two smaller traps in the same area: the `CompositeGlyphs` command stream is in the connection's byte
order (only the glyphset-switch item is always most-significant-byte first), and a glyph is drawn at
`origin - info.x/y`, not `+`.

## Known limitations

- **No Composite, DAMAGE, RandR or XInput2.** `xeyes` prints a warning about the missing
  `XInputExtension` and falls back, and a compositing window manager has nothing to build on.
- **A bounding shape does not clip drawing.** SHAPE's input shape is honoured for hit testing; the
  bounding shape only affects `QueryExtents` and `GetRectangles`, so a shaped window's corners are
  still painted. The visible region that obscuring computes does not consult it either.
- **A revealed window is restored by asking it to draw.** Nothing of a window's content is kept, so
  the area under a window that goes away is repainted with the lower window's background and an
  `Expose` - a client that ignores the expose shows its background there.
- **Writing to a client that has stopped reading blocks the server**, because events are sent while
  holding the server lock. A real server buffers per client and drops the connection.
- **Fonts are Java fonts keyed by X font name**, not rasterised X font files, so glyph shapes and
  metrics differ from a stock X server. Clients are told the metrics the server will actually use.
- **`GetMotionEvents` returns nothing** and pointer acceleration is accepted and ignored.
- **Passive grabs are recorded but never matched.** `GrabButton` and `GrabKey` are stored and listed
  by nobody: a client that arms one (a window manager, a toolkit's popup shortcut) never hears about
  the button or key it asked for. Active grabs, including the implicit grab a press starts, are
  honoured - see the crossing-event rules in `docs/design-notes.md`.
- **A focus change reports FocusIn and FocusOut, but nothing moves the focus on its own.** Every
  window on the way in and out is told, with the relationship lattice in the detail, `None` is a state
  of its own, and a window that stops being viewable gives the focus up the way the last
  `SetInputFocus` asked for - all of it measured against a reference server. What is still missing is
  the other half of X11's focus model: with the focus on PointerRoot, a server is supposed to move it
  from window to window as the pointer moves and report each move. Here the keys follow the pointer
  (which is what PointerRoot means for delivery) but no events are generated for it, and a focus
  change made by a keyboard grab is reported as `NotifyNormal` rather than `NotifyGrab`/`NotifyUngrab`.
- **A window that appears under a stationary pointer is entered by the move that follows**, not by
  the map. Xorg reports it at the map; doing so here tears EasyShell's context menus down as they are
  mapped. See the design notes for the measurement.
- **A menu that is left and re-entered used to be dismissed** by EasyShell, where it stays up against
  a real X server. The crossings matched the reference event for event, which is what made it a
  puzzle: the cause was not in them but under them - the client was never told it had the keyboard,
  because `SetInputFocus` generated no focus events. With those delivered the scripted gesture leaves
  the menu up 6 runs out of 6 where it closed 3 out of 3, and the design notes record what that took.
- **A resize cannot grow past the screen.** The host window is resizable and resizes the client's
  window, but the framebuffer behind it is fixed at `--geometry`: the window is told the largest area
  it may be given, so the platform stops the drag at the screen rather than leaving it larger than
  anything that can fill it. Growing the screen with the window is the deferred work the design notes
  describe.
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
