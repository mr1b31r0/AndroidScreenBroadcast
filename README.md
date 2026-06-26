# AndroidScreenBroadcast

**Broadcast your Android screen over Tor in real time — no cables, no accounts, no cloud.**

AndroidScreenBroadcast is an open-source Android app that captures your screen and streams it via WebSocket through the Tor network to any receiver running on the same hidden service. Pair it with a Linux receiver to watch your phone screen on a desktop, live, with no internet exposure and no third-party relay.

> Created with CSK · [NoHatHacker.com](https://nohatHacker.com)

---

## Why Tor?

Most screen-mirroring tools send your screen through a cloud server. AndroidScreenBroadcast streams directly to a `.onion` address — a Tor hidden service — so:

- **No cloud relay** — traffic never leaves the Tor circuit
- **No IP exposure** — neither side reveals its real IP address
- **No account needed** — works fully offline from the public internet
- **Encrypted end-to-end** — Tor provides layered encryption by design

This makes it ideal for demos, security research, penetration testing walkthroughs, or any situation where you want to mirror a phone screen without routing it through AWS.

---

## How it works

```
Android phone                           Linux desktop (receiver)
┌────────────────────┐                 ┌──────────────────────────┐
│  AndroidScreen     │   WebSocket     │  Python receiver         │
│  Broadcast         │ ── over Tor ──► │  (tor hidden service)    │
│                    │                 │                          │
│  MediaProjection   │  JPEG frames    │  tkinter window          │
│  → JPEG @ ~12fps   │ ─────────────► │  shows live screen       │
└────────────────────┘                 └──────────────────────────┘
       │                                          │
       └── Orbot (SOCKS5 :9050) ─────────────────┘
```

1. The **Linux receiver** creates an ephemeral Tor hidden service and displays a QR code with the `ws://*.onion` address.
2. You open **AndroidScreenBroadcast** on your phone and scan the QR code.
3. The app starts screen capture (MediaProjection API), encodes frames as JPEG, and streams them as binary WebSocket messages through Orbot over Tor.
4. The receiver window fills with live frames. When you close the window, it sends a `TERMINATE` message and the phone app exits cleanly.

---

## Requirements

### Android app
- Android 10+ (API 29)
- [Orbot](https://guardianproject.info/apps/org.torproject.android/) installed and running (provides the Tor SOCKS5 proxy on `127.0.0.1:9050`)
- Screen capture permission (prompted on first use)

### Linux receiver (companion tool)
- Python 3.10+
- `tor` running with `ControlPort 9051` and `CookieAuthentication 1` enabled
- `pip install websockets stem Pillow qrcode[pil]`

---

## Installation

### Android
Clone and build with Android Studio (API 35 SDK), or sideload the APK from the Releases page.

```bash
git clone https://github.com/mr1b31r0/AndroidScreenBroadcast.git
cd AndroidScreenBroadcast
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Linux receiver
```bash
cd receiver
python3 receiver.py
```

A window opens with a QR code. Scan it with the Android app to start streaming.

---

## Configuration

The stream uses half the device's native resolution and JPEG quality 55 — enough for live demo or tutorial recording without flooding a Tor circuit. You can tune these in `CasterService.kt`:

```kotlin
screenW = metrics.widthPixels  / 2   // reduce for slower circuits
screenH = metrics.heightPixels / 2
frame.compress(Bitmap.CompressFormat.JPEG, 55, out)  // 0–100
private const val FRAME_INTERVAL_MS = 80L            // ~12 fps
```

---

## Use cases

- **Security tool demos** — show a penetration testing walkthrough from your phone without revealing your IP
- **Privacy-conscious presentations** — share your mobile screen without a Google/Apple account or cloud mirror
- **Red team / CTF recordings** — screen-record a mobile exploit demo from a second machine
- **Remote tech support over Tor** — mirror a phone to a desktop across the Tor network

---

## Architecture notes

### SOCKS5 + `.onion` hostname resolution
OkHttp is configured with a SOCKS5 proxy pointing to Orbot (`127.0.0.1:9050`). Using `InetSocketAddress.createUnresolved()` is critical — it prevents Android from trying to resolve the `.onion` address locally (which would fail), and instead passes the hostname through the SOCKS5 tunnel where Tor resolves it.

```kotlin
val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 9050))
```

### MediaProjection foreground service
Android requires a foreground service with `foregroundServiceType="mediaProjection"` for screen capture. The app requests this permission once via the standard system dialog and keeps the service alive with a persistent notification while streaming.

### WebSocket binary framing
Each JPEG frame is sent as a single binary WebSocket message (`ByteString`). The receiver reassembles and renders each message as a PIL image. Text message `TERMINATE` from the receiver signals the app to stop and clean up.

---

## Project structure

```
app/src/main/kotlin/com/athena/democaster/
├── MainActivity.kt      # QR scanner + entry point
├── CasterActivity.kt    # MediaProjection permission + service launcher
└── CasterService.kt     # Screen capture + WebSocket streaming

receiver/
└── receiver.py          # Tor hidden service + tkinter viewer
```

---

## Contributing

Pull requests welcome. If you find this useful for a security research project or CTF writeup, drop a link in the issues — always good to see it in the wild.

---

## Related resources

- [NoHatHacker.com](https://nohatHacker.com) — cybersecurity tutorials, tools, and red team techniques
- [Orbot](https://guardianproject.info/apps/org.torproject.android/) — Tor proxy for Android
- [stem](https://stem.torproject.org/) — Python library for controlling Tor
- [OkHttp](https://square.github.io/okhttp/) — HTTP/WebSocket client for Android

---

## License

MIT — do whatever you want with it, attribute appreciated.

> Created with CSK · [NoHatHacker.com](https://nohatHacker.com)
