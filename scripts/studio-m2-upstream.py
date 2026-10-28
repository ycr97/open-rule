#!/usr/bin/env python3
"""Loopback-only controlled B2 provider. Logs counters, never facts, bodies or auth."""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"scenario": "NORMAL", "postCount": 0}
LOCK = threading.Lock()
NORMAL = b'{"data":{"blacklisted":false,"ageDays":365,"refundRate":0.08}}'

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path != "/stats":
            return self.reply(404, b'{}')
        with LOCK:
            body = json.dumps(STATE).encode()
        self.reply(200, body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > 1048576:
            return self.reply(413, b'{}')
        raw = self.rfile.read(length)
        if self.path == "/control":
            try:
                scenario = json.loads(raw)["scenario"]
            except (ValueError, KeyError, TypeError):
                return self.reply(400, b'{}')
            if scenario not in {"NORMAL", "PARTIAL", "TIMEOUT", "ERROR", "TYPE", "INVALID"}:
                return self.reply(400, b'{}')
            with LOCK:
                STATE["scenario"] = scenario
            return self.reply(200, b'{"updated":true}')
        if self.path != "/v1/buyer/batch":
            return self.reply(404, b'{}')
        with LOCK:
            STATE["postCount"] += 1
            scenario = STATE["scenario"]
        if scenario == "TIMEOUT":
            time.sleep(5)
        body = {"PARTIAL": b'{"data":{"blacklisted":false,"ageDays":365}}',
                "TYPE": b'{"data":{"blacklisted":false,"ageDays":365,"refundRate":"wrong-type"}}',
                "INVALID": b'{"data":'}.get(scenario, NORMAL)
        self.reply(503 if scenario == "ERROR" else 200, body)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=19091)
    args = parser.parse_args()
    print(f"B2 controlled upstream http://127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
