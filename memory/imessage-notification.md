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
