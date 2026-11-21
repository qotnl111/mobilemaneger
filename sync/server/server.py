#!/usr/bin/env python3
"""게임 관리 동기화 서버. 파이썬 표준 라이브러리만 쓴다.

폰 앱과 PC 프로그램이 게임별 플레이 시간을 올리고, 확인 화면과 앱이 합계를 받아 간다.
환경변수: GM_KEY (필수, 요청 헤더 x-gm-key), GM_PORT (기본 8700), GM_DB (기본 ./gm.db)
"""
import hmac
import json
import os
import re
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

KEY = os.environ.get("GM_KEY", "")
PORT = int(os.environ.get("GM_PORT", "8700"))
DB = os.environ.get("GM_DB", os.path.join(os.path.dirname(os.path.abspath(__file__)), "gm.db"))
HERE = os.path.dirname(os.path.abspath(__file__))
LOCK = threading.Lock()
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def db():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    return c


def init():
    with LOCK, db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS play (
            day TEXT NOT NULL, game TEXT NOT NULL, device TEXT NOT NULL,
            ms INTEGER NOT NULL, last_end INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL,
            PRIMARY KEY (day, game, device))""")
        try:
            c.execute("ALTER TABLE play ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        except sqlite3.OperationalError:
            pass
        c.execute("""CREATE TABLE IF NOT EXISTS mark (
            day TEXT NOT NULL, game TEXT NOT NULL, done INTEGER NOT NULL, updated INTEGER NOT NULL,
            PRIMARY KEY (day, game))""")


def norm(name):
    """Phone package names and PC process names differ, so games are matched by a plain name."""
    return re.sub(r"\s+", "", str(name)).lower()[:80]


class H(BaseHTTPRequestHandler):
    server_version = "gm-sync"

    def log_message(self, fmt, *a):
        pass

    def send(self, code, obj, ctype="application/json; charset=utf-8"):
        body = obj if isinstance(obj, bytes) else json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authed(self):
        got = self.headers.get("x-gm-key", "")
        return bool(KEY) and hmac.compare_digest(got.encode(), KEY.encode())

    def do_GET(self):
        u = urlparse(self.path)
        if u.path in ("/", "/index.html"):
            with open(os.path.join(HERE, "dashboard.html"), "rb") as f:
                return self.send(200, f.read(), "text/html; charset=utf-8")
        if u.path == "/health":
            return self.send(200, {"ok": True})
        if not self.authed():
            return self.send(401, {"error": "key"})
        q = parse_qs(u.query)
        if u.path == "/api/day":
            day = (q.get("day") or [""])[0]
            if not DAY_RE.match(day):
                return self.send(400, {"error": "day"})
            with LOCK, db() as c:
                rows = c.execute("SELECT game, device, ms, last_end, title FROM play WHERE day=?", (day,)).fetchall()
                marks = c.execute("SELECT game, done FROM mark WHERE day=?", (day,)).fetchall()
            games = {}
            for r in rows:
                g = games.setdefault(r["game"], {"devices": {}, "lastEnd": 0, "title": ""})
                g["title"] = g["title"] or r["title"]
                g["devices"][r["device"]] = r["ms"]
                g["lastEnd"] = max(g["lastEnd"], r["last_end"])
            return self.send(200, {"day": day, "games": games, "marks": {m["game"]: bool(m["done"]) for m in marks}})
        if u.path == "/api/week":
            days = [d for d in (q.get("days") or [""])[0].split(",") if DAY_RE.match(d)][:14]
            out = {}
            with LOCK, db() as c:
                for d in days:
                    for r in c.execute("SELECT game, SUM(ms) AS ms FROM play WHERE day=? GROUP BY game", (d,)):
                        out.setdefault(d, {})[r["game"]] = r["ms"]
            return self.send(200, out)
        return self.send(404, {"error": "not found"})

    def do_POST(self):
        if not self.authed():
            return self.send(401, {"error": "key"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n > 200000:
                return self.send(413, {"error": "big"})
            data = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self.send(400, {"error": "json"})
        now = int(__import__("time").time() * 1000)
        if self.path == "/api/play":
            dev = str(data.get("device", ""))[:20]
            if not dev:
                return self.send(400, {"error": "device"})
            n_ok = 0
            with LOCK, db() as c:
                for e in data.get("entries", [])[:200]:
                    day, game = str(e.get("day", "")), norm(e.get("game", ""))
                    try:
                        ms = int(e.get("ms", 0))
                        last = int(e.get("lastEnd", 0))
                    except (TypeError, ValueError):
                        continue
                    if not DAY_RE.match(day) or not game or ms < 0:
                        continue
                    # a device only ever grows its own total for a day
                    c.execute("""INSERT INTO play (day, game, device, ms, last_end, updated, title) VALUES (?,?,?,?,?,?,?)
                        ON CONFLICT(day, game, device) DO UPDATE SET
                        ms=MAX(ms, excluded.ms), last_end=MAX(last_end, excluded.last_end), updated=excluded.updated,
                        title=excluded.title""",
                              (day, game, dev, ms, last, now, str(e.get("game", ""))[:60]))
                    n_ok += 1
            return self.send(200, {"saved": n_ok})
        if self.path == "/api/mark":
            day, game = str(data.get("day", "")), norm(data.get("game", ""))
            if not DAY_RE.match(day) or not game:
                return self.send(400, {"error": "arg"})
            with LOCK, db() as c:
                c.execute("""INSERT INTO mark (day, game, done, updated) VALUES (?,?,?,?)
                    ON CONFLICT(day, game) DO UPDATE SET done=excluded.done, updated=excluded.updated""",
                          (day, game, 1 if data.get("done") else 0, now))
            return self.send(200, {"ok": True})
        return self.send(404, {"error": "not found"})


def main():
    if not KEY or len(KEY) < 16:
        raise SystemExit("GM_KEY must be set (16+ characters)")
    init()
    print("gm-sync listening on 0.0.0.0:%d" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()


if __name__ == "__main__":
    main()
