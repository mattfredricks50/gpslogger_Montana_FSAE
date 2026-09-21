"""Fake AirBear (Web Dash mode) for testing the phone's .ecu logging without the car.

Serves Server-Sent Events at http://<this-pc>:<port>/events in the same shape as the
AirBear firmware: a "reading" event with Speeduino JSON every 1/30 s and a ping every second.
Values sweep so the log has something to look at.

    python tools/fake_airbear.py            # port 80 (may need admin), like the real AirBear
    python tools/fake_airbear.py 8080       # then set the app's AirBear address to <pc-ip>:8080

Put the phone on the same Wi-Fi as this PC.
"""
import json
import math
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def reading(t):
    rpm = int(4000 + 3500 * math.sin(t * 0.8))
    tps_raw = int(100 + 90 * math.sin(t * 0.8))  # Speeduino 0-200 (0.5% steps)
    return {
        "secl": int(t) % 256, "inj1_status": 1, "dfco_active": 0, "running": 1,
        "dwell": 3.2, "MAP": int(60 + 40 * math.sin(t * 0.8)), "IAT": 25, "CLT": 88,
        "Battery_Voltage": 13.8, "AFR1": round(13.0 + 1.5 * math.sin(t * 0.3), 1),
        "correction_o2": 100, "correction_iat": 100, "correction_wue": 100,
        "rpm": rpm, "correction_ae": 100, "correction_total": 100, "VE": 80,
        "afr_target": 13.0, "PW1": 350.0, "tps_DOT": 0, "advance": 28, "TPS": tps_raw,
        "baro": 85, "rpmDOT": 0,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path != "/events":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        start = time.monotonic()
        next_ping = start
        try:
            self.wfile.write(b"retry: 10000\nid: 0\ndata: hello!\n\n")
            while True:
                now = time.monotonic()
                ms = int((now - start) * 1000)
                msg = f"id: {ms}\nevent: reading\ndata: {json.dumps(reading(now - start))}\n\n"
                if now >= next_ping:
                    msg += f"id: {ms}\ndata: ping\n\n"
                    next_ping += 1
                self.wfile.write(msg.encode())
                self.wfile.flush()
                time.sleep(1 / 30)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, fmt, *args):
        print(self.address_string(), fmt % args)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    print(f"Fake AirBear on port {port}: /events")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
