#!/bin/sh
# Run one of the verification tools with the right classpath.
#
#   tools/kit.sh Drive 127.0.0.1 6095 "M 100 100" "P 1" "R 1"
#   tools/kit.sh WinTree 127.0.0.1 6095
#
# The classpath is twenty-odd jars long, and pasting it into a command line is what produced
# "command too long" from the shell on the first attempt at driving a client by hand. One script
# that reads it from `target/tools-cp.txt` (which `mvn dependency:build-classpath` writes) removes
# the paste and the quoting from every invocation.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MVN="${MVN:-/Users/oyzh/Documents/Tools/apache-maven-3.9.11/bin/mvn}"

if [ $# -lt 1 ]; then
    echo "usage: tools/kit.sh <Tool> [args ...]" >&2
    echo "tools: Drive Keys Shot Wire FocusProbe MenuProbe PointProbe WinTree Focus Pixel Xwd" >&2
    echo "       Blocks Colors Shift Rect Crop AuthProbe SelectionProbe CursorProbe" >&2
    echo "       ClipboardProbe WindowStateProbe XProxy XkbProbe RandrProbe XineramaProbe" >&2
    echo "       ScreenSaverProbe GravityProbe GrabProbe AllowEventsProbe PolyProbe" >&2
    exit 2
fi

if [ ! -f "$HERE/target/tools-cp.txt" ]; then
    (cd "$HERE" && "$MVN" -o -q dependency:build-classpath -Dmdep.outputFile=target/tools-cp.txt)
fi
if [ ! -d "$HERE/target/test-classes" ]; then
    (cd "$HERE" && "$MVN" -o -q test-compile)
fi

TOOL="$1"
shift
exec java -cp "$HERE/target/test-classes:$HERE/target/classes:$(cat "$HERE/target/tools-cp.txt")" \
    "io.github.oyzh1994.xserver.tools.$TOOL" "$@"
