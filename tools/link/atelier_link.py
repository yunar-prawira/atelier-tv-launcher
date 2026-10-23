#!/usr/bin/env python3
"""
Atelier link — the VM side of the TV's remote channel.

Why not a server on the TV: Android/TCL SELinux refuses an app `bind()` on anything but loopback
("socket failed: EPERM"), so the set cannot host a listener. It CAN open outbound sockets, so the
TV becomes the client and this process holds the rendezvous.

Flow (one round trip, every poll):
    TV  --POST /sync {state, result}-->  this server
    TV  <--200 {"cmd": "..."}---------

Commands are queued by the operator through the localhost-only admin port, so nothing but a
tailnet-authenticated TV can talk to /sync, and only this box can enqueue work.

Run:  python3 atelier_link.py            (foreground, for debugging)
      see run_link.sh                    (nohup background)
"""
import json
import os
import secrets
import sqlite3
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_FILE = os.path.join(HERE, "link.token")
STATE_FILE = os.path.join(HERE, "state.json")
QUEUE_FILE = os.path.join(HERE, "queue.json")
LINK_PORT = 7391
ADMIN_PORT = 7392
POLL_TIMEOUT = 30.0


def token():
    if not os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "w") as f:
            f.write(secrets.token_urlsafe(24))
        os.chmod(TOKEN_FILE, 0o600)
    return open(TOKEN_FILE).read().strip()


def read_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)


class Link(BaseHTTPRequestHandler):
    server_version = "atelier-link/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        with open(os.path.join(HERE, "link.log"), "a") as f:
            f.write("%s %s\n" % (time.strftime("%F %T"), fmt % args))

    def _auth(self):
        got = self.headers.get("Authorization", "")
        if got.startswith("Bearer "):
            got = got[7:]
        return secrets.compare_digest(got, token())

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _json(self, code, obj):
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if not self._auth():
            self._json(401, {"ok": False, "error": "token required"})
            return

        if path == "/sync":
            try:
                payload = json.loads(self._body() or b"{}")
            except Exception:
                payload = {}
            payload["ts"] = time.time()
            if payload.get("state"):
                write_json(STATE_FILE, payload)
            q = read_json(QUEUE_FILE, [])
            cmd = q.pop(0) if q else None
            write_json(QUEUE_FILE, q)
            self._json(200, {"ok": True, "cmd": cmd})
            return

        self._json(404, {"ok": False, "error": "unknown endpoint"})

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/health":
            st = read_json(STATE_FILE, {})
            age = int(time.time() - st.get("ts", 0)) if st.get("ts") else None
            self._json(200, {"ok": True, "state_age_s": age,
                             "version": (st.get("state") or {}).get("version")})
            return
        self._json(401, {"ok": False, "error": "use /sync"})


class Admin(BaseHTTPRequestHandler):
    """Localhost only: what the operator's tooling talks to."""

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/state":
            st = read_json(STATE_FILE, {})
            self._json(200, {"ok": True, "age_s": int(time.time() - st["ts"]) if st.get("ts") else None,
                             "payload": st})
            return
        if u.path == "/queue":
            self._json(200, {"ok": True, "queue": read_json(QUEUE_FILE, [])})
            return
        self._json(404, {"ok": False})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if u.path == "/enqueue":
            try:
                cmd = json.loads(raw or b"{}").get("cmd")
            except Exception:
                cmd = None
            if not cmd:
                self._json(400, {"ok": False, "error": "cmd required"})
                return
            q = read_json(QUEUE_FILE, [])
            q.append(cmd)
            write_json(QUEUE_FILE, q)
            self._json(200, {"ok": True, "queued": cmd, "depth": len(q)})
            return
        self._json(404, {"ok": False})


def tailnet_ip():
    """The TV can only reach us over the tailnet, so bind there and nowhere else."""
    import socket
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            pass
    except Exception:
        pass
    try:
        out = subprocess_run(["tailscale", "ip", "-4"])
        if out:
            return out.splitlines()[0].strip()
    except Exception:
        pass
    return "127.0.0.1"


def subprocess_run(cmd):
    import subprocess
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return ""


def serve(handler, port, host):
    srv = ThreadingHTTPServer((host, port), handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    print(json.dumps({"link_port": LINK_PORT, "admin_port": ADMIN_PORT,
                      "token_len": len(token())}))
    ip = tailnet_ip()
    print(json.dumps({"tailnet_ip": ip}))
    t = threading.Thread(target=serve, args=(Admin, ADMIN_PORT, "127.0.0.1"), daemon=True)
    t.start()
    serve(Link, LINK_PORT, ip)
