---
name: imessage-notification
description: "How to notify the user's phone from this machine: osascript to Messages, the handle that works, and what failed before"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 01353543-0895-4938-81fe-8b2a65312ab8
  modified: 2026-09-25T14:14:27.882Z
---

The user asks to be told on their phone when a long unattended task finishes. The route that works is
**`osascript` driving Messages.app**:

```sh
osascript -e 'with timeout of 20 seconds
tell application "Messages"
  set targetService to 1st service whose service type = iMessage
  set targetBuddy to buddy "oyzh.1994@qq.com" of targetService
  send "..." to targetBuddy
end tell
end timeout'
```

- The handle used before and recorded in this project's transcripts is **`oyzh.1994@qq.com`** (a QQ
  address, which reaches the phone). A phone number was also tried as `+8613...`.
- **Wrap it in `with timeout`**: a send that hits a blocked automation permission *hangs* rather than
  failing, and an unbounded `osascript` stops the session.
- A call that only reads (`tell application "Messages" to get name`) is a cheap check that the
  automation permission is in place; it answered on 2026-09-25 (Messages.app was already running).
- What did **not** work, tried in an earlier session and worth not repeating: Mail.app to the same QQ
  address, `terminal-notifier` (a local notification, not the phone), `shortcuts run`, SMS through
  Messages, and push services (no token). Do not spend a session on those again.

## Two messages arrived as mojibake on 2026-10-04 - the text must not come in through the environment

Reported by the user ("你最近发的2条imessage都中文乱码了"). The two messages in question were sent with
the body passed in an environment variable and read with `system attribute "XS_MSG"`:

```sh
XS_MSG="$(cat /tmp/xsn/imsg7.txt)" osascript -e '... send (system attribute "XS_MSG") to targetBuddy ...'
```

**That is the bug.** `system attribute` hands the value to AppleScript in the system's own encoding
rather than as UTF-8, so every multi-byte character is mangled - measured:

```
in : 中文测试            e4 b8 ad e6 96 87 e6 b5 8b e8 af 95
out:                     e6 b6 93 ef bf bd c2 96 c2 87 e5 a8 b4 c2 8b e7 92 87 c2 95
```

The earlier messages were fine because their text was written *inside* the `-e` argument, and `argv` is
UTF-8. So: **never hand the text over through the environment or through a shell variable.** Put it in a
file and read it inside the script with an explicit encoding, which is byte-exact (verified with `xxd`
on the same sentence):

```applescript
set msg to read (POSIX file "/tmp/xsn/imsg8.txt") as «class utf8»
```

`/tmp/xsn/imessage.sh` is the sender that does this (`imessage.sh <file> [handle]`, default handle
`15927012949`, which resolves to the contact **副卡**). Two things about it that cost a round trip each:

- **`with timeout` must be closed.** Splitting the script across several `-e` arguments is fine
  (`osascript` joins them with newlines), but an opened `with timeout of N seconds` without its
  `end timeout` fails with `syntax error: ... found end of script` at the very end of the script,
  which reads as a problem with the text rather than with the block.
- the `«class utf8»` guillemets survive `-e` unchanged (they are UTF-8 in `argv`), so they are safe.

The handle the user asks for now is the number **15927012949**; `oyzh.1994@qq.com` still works as well.
