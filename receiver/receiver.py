#!/usr/bin/env python3
"""
Athena Demo Receiver — screen broadcast receiver for demo recordings.

On first run:
    sudo apt-get install -y tor python3-stem python3-websockets python3-qrcode \
                            python3-pil python3-tk
    # Add to /etc/tor/torrc:
    #   ControlPort 9051
    #   CookieAuthentication 1
    # Then: sudo systemctl restart tor

Usage: python3 receiver.py [--port PORT]
"""

import argparse
import asyncio
import io
import os
import signal
import sys
import threading
import time

# ── dependency check ──────────────────────────────────────────────────────────
MISSING = []
try:
    import websockets
except ImportError:
    MISSING.append("python3-websockets")
try:
    from PIL import Image, ImageTk
except ImportError:
    MISSING.append("python3-pil / python3-pillow")
try:
    import qrcode
except ImportError:
    MISSING.append("python3-qrcode")
try:
    from stem.control import Controller
except ImportError:
    MISSING.append("python3-stem")

if MISSING:
    print("Missing dependencies:", ", ".join(MISSING))
    print("Install with:")
    print("  sudo apt-get install -y tor python3-stem python3-websockets "
          "python3-qrcode python3-pil python3-tk")
    sys.exit(1)

import tkinter as tk

# ── constants ─────────────────────────────────────────────────────────────────
TOR_CONTROL_PORT = 9051
WINDOW_W, WINDOW_H = 960, 540
FPS_CAP = 30

# ── state shared between asyncio thread and tkinter thread ────────────────────
_ws_ref       = None    # the live WebSocket connection
_onion_addr   = None    # set once Tor bootstraps
_latest_frame = None    # bytes of the last JPEG received
_frame_lock   = threading.Lock()
_terminate_ev = threading.Event()


# ── Tor setup ─────────────────────────────────────────────────────────────────

def start_hidden_service(local_port: int) -> str:
    """
    Create an ephemeral Tor hidden service mapping :80 → localhost:local_port.
    Returns the .onion hostname.  Blocks until Tor bootstraps (up to 120 s).
    """
    try:
        ctrl = Controller.from_port(port=TOR_CONTROL_PORT)
    except Exception:
        print("[tor] Cannot connect to Tor control port.")
        print("      Make sure /etc/tor/torrc contains:")
        print("        ControlPort 9051")
        print("        CookieAuthentication 1")
        print("      Then: sudo systemctl restart tor")
        sys.exit(1)

    ctrl.authenticate()

    print("[tor] Waiting for Tor to bootstrap …", end="", flush=True)
    for _ in range(120):
        status = ctrl.get_info("status/bootstrap-phase", "")
        if "PROGRESS=100" in status:
            print(" ready.")
            break
        print(".", end="", flush=True)
        time.sleep(1)
    else:
        print("\n[tor] Bootstrap timed out.")
        sys.exit(1)

    svc = ctrl.create_ephemeral_hidden_service({80: local_port}, await_publication=True)
    onion = f"{svc.service_id}.onion"
    print(f"[tor] Hidden service ready: {onion}")
    # Keep ctrl alive so the ephemeral service stays up — store on svc object
    svc._ctrl = ctrl
    return onion, svc


# ── WebSocket server ──────────────────────────────────────────────────────────

async def handle_connection(websocket):
    global _ws_ref, _latest_frame
    _ws_ref = websocket
    addr = websocket.remote_address
    print(f"[ws] Phone connected from {addr}")
    try:
        async for message in websocket:
            if isinstance(message, str):
                # Control message from phone (shouldn't normally arrive)
                print(f"[ws] text: {message}")
            else:
                # Binary JPEG frame
                with _frame_lock:
                    _latest_frame = message
    except Exception as e:
        print(f"[ws] connection closed: {e}")
    finally:
        _ws_ref = None
        print("[ws] Phone disconnected")


async def run_server(local_port: int):
    async with websockets.serve(handle_connection, "127.0.0.1", local_port):
        print(f"[ws] Listening on 127.0.0.1:{local_port}")
        await asyncio.get_event_loop().run_in_executor(None, _terminate_ev.wait)


def asyncio_thread(local_port: int):
    asyncio.run(run_server(local_port))


# ── send terminate to phone ───────────────────────────────────────────────────

def send_terminate():
    ws = _ws_ref
    if ws is None:
        return
    async def _send():
        try:
            await ws.send("TERMINATE")
            await ws.close()
        except Exception:
            pass
    asyncio.run(_send())


# ── tkinter UI ────────────────────────────────────────────────────────────────

class ReceiverWindow:
    def __init__(self, root: tk.Tk, onion: str):
        self.root = root
        self.root.title("Athena Demo Receiver")
        self.root.geometry(f"{WINDOW_W}x{WINDOW_H}")
        self.root.configure(bg="black")
        self.root.resizable(False, False)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self.canvas = tk.Canvas(root, width=WINDOW_W, height=WINDOW_H,
                                bg="black", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)

        self._photo = None
        self._showing_qr = True

        # Generate and show QR code
        ws_url = f"ws://{onion}"
        print(f"[ui] QR code URL: {ws_url}")
        qr_img = self._make_qr(ws_url)
        self._show_image(qr_img)

        # Status label
        self._status = tk.Label(root, text=f"Waiting for phone…  {ws_url}",
                                fg="#aaaaaa", bg="black", font=("Monospace", 11))
        self._status.place(x=0, y=WINDOW_H - 28, width=WINDOW_W)

        self._schedule_update()

    def _make_qr(self, data: str) -> Image.Image:
        qr = qrcode.QRCode(box_size=8, border=2)
        qr.add_data(data)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        # Centre on black background
        bg = Image.new("RGB", (WINDOW_W, WINDOW_H), (0, 0, 0))
        x = (WINDOW_W - img.width) // 2
        y = (WINDOW_H - img.height) // 2
        bg.paste(img, (x, y))
        return bg

    def _show_image(self, img: Image.Image):
        photo = ImageTk.PhotoImage(img)
        self._photo = photo  # hold reference
        self.canvas.create_image(0, 0, anchor="nw", image=photo)

    def _schedule_update(self):
        self.root.after(1000 // FPS_CAP, self._update_frame)

    def _update_frame(self):
        global _latest_frame
        with _frame_lock:
            data = _latest_frame
            _latest_frame = None

        if data is not None:
            try:
                img = Image.open(io.BytesIO(data)).convert("RGB")
                img = img.resize((WINDOW_W, WINDOW_H), Image.LANCZOS)
                self._show_image(img)
                if self._showing_qr:
                    self._showing_qr = False
                    self._status.config(text="● LIVE", fg="#00ff44")
            except Exception as e:
                print(f"[ui] frame decode error: {e}")

        self._schedule_update()

    def _on_close(self):
        print("[ui] Closing — sending TERMINATE to phone")
        self._status.config(text="Closing…", fg="orange")
        self.root.update()
        send_terminate()
        _terminate_ev.set()
        self.root.after(400, self.root.destroy)


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Athena demo screen receiver")
    parser.add_argument("--port", type=int, default=8765,
                        help="Local WebSocket port (default 8765)")
    args = parser.parse_args()

    print(f"[*] Starting Tor hidden service for port {args.port} …")
    onion, _svc = start_hidden_service(args.port)

    # Start WebSocket server in background thread
    t = threading.Thread(target=asyncio_thread, args=(args.port,), daemon=True)
    t.start()

    # Run UI on main thread
    root = tk.Tk()
    app = ReceiverWindow(root, onion)

    def on_sigint(*_):
        app._on_close()

    signal.signal(signal.SIGINT, on_sigint)
    root.mainloop()


if __name__ == "__main__":
    main()
