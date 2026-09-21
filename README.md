# xserver

A modern X11 display server written in Java, rendered into a JavaFX window.

It speaks the X11 protocol to real clients — Xlib, XCB, Xt, Motif, GTK's core-font path — and
paints their windows into a framebuffer that is blitted to the screen. It is not a compatibility
shim around an existing server: the window tree, the resource model, event delivery, grabs and the
rasteriser are all implemented here.

## Running it

```sh
./mvnw compile
java -cp "target/classes:$(cat /tmp/cp.txt)" io.github.oyzh1994.xserver.Main --display :1
```

Or through Maven:

```sh
./mvnw exec:java -Dexec.args="--display :1 --geometry 1280x800"
```

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

The JavaFX backend falls back to Swing automatically when JavaFX cannot start, and `headless` runs
the server with no window at all — useful for tests and for driving clients with `xwd`.

## Architecture

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

**One framebuffer per screen, and windows draw straight into it.** A window's contents are not
kept in its own buffer: drawing is clipped to the part of the window that is actually visible, and
obscured content is not preserved. That is X's model, and it is what makes `Expose` events mean
what clients expect. Coordinates are relative to the parent, so every draw request is translated by
the window's absolute position on the way in.

**Replies are serialised to memory before they reach the socket.** The generated writer for a reply
computes its length field from the wrong base, and short replies are emitted without their trailing
padding; both would desynchronise a real client. `ClientOutput` rewrites the length from the bytes
actually produced and pads what needs padding, in one place.

## What is implemented

Core protocol: windows (create, map, configure, restack, reparent, circulate), the full drawing set
(points, lines, segments, rectangles, arcs, polygons, both text requests, `PutImage`/`GetImage`,
`CopyArea`/`CopyPlane`, `ClearArea`), GCs, pixmaps, fonts, cursors, colormaps, atoms, properties,
selections, keyboard and pointer mapping, focus, active and passive grabs, and `SendEvent`.

Events: structure notifications, exposes, property and colormap notifications, crossing events with
the full detail lattice, pointer and keyboard events with propagation and `doNotPropagateMask`, and
`ClientMessage` in both directions.

Extensions: `BIG-REQUESTS`, `XC-MISC`, `SHAPE` (rectangles, masks and the five combine operations),
`XTEST` (synthetic input), `XFIXES` (cursor images, regions, `SetWindowClipRegion`).

Verified against real clients: `xdpyinfo`, `xwininfo`, `xprop`, `xlsclients`, `xwd`, `xeyes`,
`xlogo`, `xclock`, `xcalc`, `xmessage`, `xterm` (core fonts), and `twm` as a window manager.

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

## Known limitations

- **No RENDER extension.** Clients that need it (GTK 3+, Qt 5+, cairo, xterm with `-fa`) fail to
  start or fall back to core drawing. This is the single biggest gap for modern toolkits.
- **No Composite, DAMAGE, RandR or XInput2.** `xeyes` prints a warning about the missing
  `XInputExtension` and falls back.
- **A bounding shape does not clip drawing.** SHAPE's input shape is honoured for hit testing; the
  bounding shape only affects `QueryExtents` and `GetRectangles`, so a shaped window's corners are
  still painted.
- **Content revealed by unmapping a window is not repainted.** Expose events are generated for a
  window that becomes visible, but not for the windows underneath it.
- **Writing to a client that has stopped reading blocks the server**, because events are sent while
  holding the server lock. A real server buffers per client and drops the connection.
- **Fonts are Java fonts keyed by X font name**, not rasterised X font files, so glyph shapes and
  metrics differ from a stock X server. Clients are told the metrics the server will actually use.
- **`GetMotionEvents` returns nothing** and pointer acceleration is accepted and ignored.

## Tests

```sh
./mvnw test
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
