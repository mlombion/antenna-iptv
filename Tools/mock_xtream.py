#!/usr/bin/env python3
"""Local stand-in for an Xtream Codes server, for developing Antenna without a real subscription.

Run:   python3 Tools/mock_xtream.py            (listens on http://127.0.0.1:8765)
Login: server http://127.0.0.1:8765 · username antenna-test · password test1234

.ts streams are a real-time MPEG-TS replay of Apple's public sample video (with a burned-in clock, handy
for checking rewind); .m3u8 streams redirect to public HLS test streams. Both need an internet connection.
Channel 999 always fails, to exercise the error screen.
"""
import base64
import os
import json
import re
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

USER, PASS = "antenna-test", "test1234"
PORT = 8765
# Like many real subscriptions: a second simultaneous stream is refused until the first one closes.
MAX_CONNECTIONS = int(os.environ.get("MOCK_MAX_CONNECTIONS", "0")) or None
_active = [0]

TEST_STREAMS = [
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
]

LINEUP = {
    "FR| GENERAL": ["FR| TF1 FHD", "FR| FRANCE 2 FHD", "FR| FRANCE 3 HD", "FR| CANAL+ FHD", "FR| FRANCE 5 HD",
                    "FR| M6 FHD", "FR| ARTE FHD", "FR| C8 HD", "FR| W9 HD", "FR| TMC HD"],
    "FR| NEWS": ["FR| BFM TV FHD", "FR| FRANCEINFO HD", "FR| CNEWS HD", "FR| LCI HD", "FR| FRANCE 24 HD", "FR| EURONEWS HD"],
    "FR| SPORTS": ["FR| BEIN SPORTS 1 FHD", "FR| BEIN SPORTS 2 FHD", "FR| CANAL+ SPORT FHD", "FR| RMC SPORT 1 4K",
                   "FR| EUROSPORT 1 FHD", "FR| L'EQUIPE HD", "FR| DAZN 1 FHD"],
    "FR| CINEMA": ["FR| CANAL+ CINEMA FHD", "FR| OCS MAX FHD", "FR| CINE+ PREMIER FHD", "FR| TCM CINEMA HD"],
    "AFRIQUE": ["AF| TV5MONDE AFRIQUE HD", "AF| CANAL+ AFRIQUE HD", "AF| RTS 1 HD", "AF| 2STV HD", "AF| A+ IVOIRE HD"],
    "KIDS": ["FR| GULLI HD", "FR| DISNEY CHANNEL HD", "FR| CARTOON NETWORK HD"],
    "XXX ADULTS": ["XXX| HIDDEN BY DEFAULT"],
}
TITLES = {
    "FR| GENERAL": ["Le Journal", "Koh-Lanta", "Questions pour un champion", "Envoyé spécial", "Top Chef", "Zone interdite"],
    "FR| NEWS": ["Le Journal", "Édition spéciale", "Le débat du soir", "Info en continu", "Grand reportage"],
    "FR| SPORTS": ["Ligue 1: PSG – OM", "Premier League: Arsenal – Chelsea", "NBA: Lakers – Celtics", "Formule 1 : Grand Prix du Japon",
                   "Champions League Magazine"],
    "FR| CINEMA": ["Inception", "Dune: Part Two", "Intouchables", "Heat", "La Haine"],
    "AFRIQUE": ["Journal Afrique", "Lutte: Grand combat", "Le Grand Talk", "Music Afrika"],
    "KIDS": ["Bluey", "Miraculous", "Pokémon", "Gumball"],
    "XXX ADULTS": ["—"],
}

CATEGORIES, STREAMS = [], []
sid = 100
for ci, (cat, names) in enumerate(LINEUP.items(), start=1):
    CATEGORIES.append({"category_id": str(ci), "category_name": cat, "parent_id": 0})
    for name in names:
        sid += 1
        STREAMS.append({"num": len(STREAMS) + 1, "name": name, "stream_type": "live", "stream_id": sid,
                        "stream_icon": "", "epg_channel_id": "" if "TMC" in name else f"ch{sid}.fr",
                        "added": "1700000000", "category_id": str(ci), "tv_archive": 0, "_cat": cat})
STREAMS.append({"num": 999, "name": "FR| BROKEN CHANNEL", "stream_type": "live", "stream_id": 999, "stream_icon": "",
                "epg_channel_id": "", "category_id": "1", "tv_archive": 0, "_cat": "FR| GENERAL"})


SAMPLE = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/gear2/"
_segments, _lock = [], threading.RLock()


def sample_segments(count=12):
    """First `count` segments of the sample as (bytes, seconds), downloaded once."""
    with _lock:
        if not _segments:
            playlist = urllib.request.urlopen(SAMPLE + "prog_index.m3u8").read().decode()
            parts = re.findall(r"#EXTINF:([\d.]+),.*?\n#EXT-X-BYTERANGE:(\d+)@(\d+)", playlist)[:count]
            end = max(int(n) + int(o) for _, n, o in parts)
            req = urllib.request.Request(SAMPLE + "main.ts", headers={"Range": f"bytes=0-{end - 1}"})
            blob = urllib.request.urlopen(req).read()
            for dur, n, o in parts:
                _segments.append((blob[int(o):int(o) + int(n)], float(dur)))
        return list(_segments)


def stream_live_ts(handler):
    """Sends the sample as an endless live stream, paced at real time like a broadcast."""
    with _lock:
        if MAX_CONNECTIONS and _active[0] >= MAX_CONNECTIONS:
            handler.send_response(403); handler.end_headers()
            print("mock: refused stream, connection limit reached")
            return
        _active[0] += 1
    try:
        _stream_live_ts(handler)
    finally:
        with _lock:
            _active[0] -= 1


def _stream_live_ts(handler):
    handler.send_response(200)
    handler.send_header("Content-Type", "video/mp2t")
    handler.end_headers()
    try:
        while True:
            for data, dur in sample_segments():
                # MOCK_BURSTY=1 sends each ~10 s segment in one go, like providers that deliver in bursts.
                pieces = 1 if os.environ.get("MOCK_BURSTY") else 20
                step = len(data) // pieces + 1
                for i in range(0, len(data), step):
                    handler.wfile.write(data[i:i + step])
                    handler.wfile.flush()
                    time.sleep(dur / pieces)
    except (BrokenPipeError, ConnectionResetError):
        pass


def b64(s):
    return base64.b64encode(s.encode()).decode()


def epg_for(stream_id, limit):
    stream = next((s for s in STREAMS if s["stream_id"] == stream_id), None)
    if not stream or not stream["epg_channel_id"]:
        return []
    titles = TITLES[stream["_cat"]]
    durations = [30, 45, 60, 90, 120] if "SPORT" not in stream["_cat"] else [105, 120, 90]
    now = int(time.time())
    t = now - (now % 1800) - (stream_id % 4) * 900
    out = []
    for i in range(limit):
        d = durations[(stream_id + i) % len(durations)] * 60
        title = titles[(stream_id + i) % len(titles)]
        out.append({"id": str(i), "title": b64(title), "description": b64(f"{title}, live on {stream['name'][4:]}."),
                    "start_timestamp": str(t), "stop_timestamp": str(t + d), "channel_id": stream["epg_channel_id"]})
        t += d
    return out


def xmltv():
    """The whole guide for every channel, as providers serve it at xmltv.php."""
    from xml.sax.saxutils import escape
    out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
    for s in STREAMS:
        if s["epg_channel_id"]:
            out.append(f'<channel id="{s["epg_channel_id"]}"><display-name>{escape(s["name"])}</display-name></channel>')
    for s in STREAMS:
        for e in epg_for(s["stream_id"], 12):
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(int(t)))
            title = base64.b64decode(e["title"]).decode()
            desc = base64.b64decode(e["description"]).decode()
            out.append(f'<programme start="{fmt(e["start_timestamp"])}" stop="{fmt(e["stop_timestamp"])}" channel="{e["channel_id"]}">'
                       f'<title lang="fr">{escape(title)}</title><desc lang="fr">{escape(desc)}</desc></programme>')
    out.append("</tv>")
    return "\n".join(out).encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("mock:", self.command, self.path.split("?")[0], fmt % args if "%" in fmt else "")

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/player_api.php":
            if q.get("username") != USER or q.get("password") != PASS:
                return self.send_json({"user_info": {"auth": 0}})
            action = q.get("action")
            if action is None:
                return self.send_json({
                    "user_info": {"username": USER, "auth": 1, "status": "Active", "exp_date": "1804550400",
                                  "is_trial": "0", "active_cons": "0", "max_connections": "2",
                                  "allowed_output_formats": ["m3u8", "ts"]},
                    "server_info": {"url": "127.0.0.1", "port": str(PORT), "timezone": "Europe/Paris"}})
            if action == "get_live_categories":
                return self.send_json(CATEGORIES)
            if action == "get_live_streams":
                return self.send_json([{k: v for k, v in s.items() if k != "_cat"} for s in STREAMS])
            if action == "get_short_epg":
                return self.send_json({"epg_listings": epg_for(int(q.get("stream_id", 0)), int(q.get("limit", 10)))})
            return self.send_json([])
        if url.path == "/xmltv.php":
            if q.get("username") != USER or q.get("password") != PASS:
                self.send_response(401); self.end_headers(); return
            body = xmltv()
            self.send_response(200)
            self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        parts = url.path.strip("/").split("/")
        if len(parts) == 4 and parts[0] == "live":
            if parts[1] != USER or parts[2] != PASS:
                self.send_response(403); self.end_headers(); return
            stream_id = int(parts[3].split(".")[0])
            if stream_id == 999:
                self.send_response(404); self.end_headers(); return
            if parts[3].endswith(".ts"):
                return stream_live_ts(self)
            self.send_response(302)
            self.send_header("Location", TEST_STREAMS[stream_id % len(TEST_STREAMS)])
            self.end_headers()
            return
        self.send_response(404); self.end_headers()


if __name__ == "__main__":
    print(f"Mock Xtream server on http://127.0.0.1:{PORT}  (user {USER} / pass {PASS})")
    # MOCK_HOST=0.0.0.0 makes it reachable from other devices (e.g. to test casting between simulators).
    ThreadingHTTPServer((os.environ.get("MOCK_HOST", "127.0.0.1"), PORT), Handler).serve_forever()
