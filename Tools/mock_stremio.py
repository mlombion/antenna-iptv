#!/usr/bin/env python3
"""Local stand-in for a Stremio account, for testing Movies & Series without a real account.

Serves the link-code sign-in (approves itself after a couple of polls), getUser, addonCollectionGet,
datastoreGet/datastorePut (in memory, and prints every change so progress syncing can be checked),
plus a small test add-on whose sources are public-domain films only (Blender's Big Buck Bunny and Sintel)
as a direct link and as torrents. Catalogs and details come from Stremio's public Cinemeta add-on.

Run:  python3 Tools/mock_stremio.py      (port 8790)
Emulator: adb shell am start -n com.malick.antenna.tv/.MainActivity -e devStremio http://10.0.2.2:8790/
"""
import json
import os
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("MOCK_PORT", "8790"))
HOST_FOR_DEVICE = os.environ.get("MOCK_PUBLIC", "http://10.0.2.2:%d" % PORT)
KEY = "mock-auth-key"

DIRECT = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
BBB_HASH = "dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c"
SINTEL_HASH = "08ada5a7a6183aae1e09d831df6748d566095a10"
TRACKERS = ["tracker:udp://explodie.org:6969", "tracker:udp://tracker.opentrackr.org:1337/announce",
            "tracker:udp://tracker.empire-js.us:1337", "tracker:udp://tracker.leechers-paradise.org:6969"]


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def item(id_, name, type_, poster, offset, duration, video_id=None):
    return {"_id": id_, "name": name, "type": type_, "poster": poster, "posterShape": "poster",
            "removed": False, "temp": False, "_ctime": now(), "_mtime": now(),
            "state": {"lastWatched": now(), "timeWatched": offset, "timeOffset": offset, "overallTimeWatched": offset,
                      "timesWatched": 0, "flaggedWatched": 0, "duration": duration, "video_id": video_id,
                      "watched": None, "noNotif": False},
            "behaviorHints": {"defaultVideoId": None, "featuredVideoId": None, "hasScheduledVideos": False}}


LIBRARY = {
    "tt1254207": item("tt1254207", "Big Buck Bunny", "movie",
                      "https://images.metahub.space/poster/medium/tt1254207/img", 95_000, 596_000, "tt1254207"),
    "tt0903747": item("tt0903747", "Breaking Bad", "series",
                      "https://images.metahub.space/poster/medium/tt0903747/img", 600_000, 2_800_000, "tt0903747:1:2"),
    "tt1727587": item("tt1727587", "Sintel", "movie", "https://images.metahub.space/poster/medium/tt1727587/img", 0, 0),
}
polls = {}

TEST_ADDON = {
    "id": "com.antenna.test", "version": "1.0.0", "name": "Antenna Test Sources",
    "description": "Public-domain test streams", "types": ["movie", "series"], "idPrefixes": ["tt"],
    "resources": ["stream"], "catalogs": [],
}


def cinemeta_manifest():
    try:
        with urllib.request.urlopen("https://v3-cinemeta.strem.io/manifest.json", timeout=10) as r:
            return json.load(r)
    except Exception as e:  # offline: a minimal one still lets the board render rows
        print("Couldn't fetch Cinemeta manifest:", e)
        return {"id": "com.linvo.cinemeta", "version": "3.0.0", "name": "Cinemeta", "types": ["movie", "series"],
                "idPrefixes": ["tt"], "resources": ["catalog", "meta"],
                "catalogs": [{"type": "movie", "id": "top", "name": "Popular"}, {"type": "series", "id": "top", "name": "Popular"}]}


CINEMETA = cinemeta_manifest()
try:
    with urllib.request.urlopen("https://opensubtitles-v3.strem.io/manifest.json", timeout=10) as r:
        OPENSUBS = json.load(r)
except Exception:
    OPENSUBS = {"id": "org.stremio.opensubtitlesv3", "version": "1.0.0", "name": "OpenSubtitles v3", "types": ["movie", "series"],
                "idPrefixes": ["tt"], "resources": ["subtitles"], "catalogs": []}


def streams_for(type_, vid):
    imdb = vid.split(":")[0]
    if imdb == "tt1727587":
        return [{"name": "Test\nTorrent", "description": "Sintel (2010) · public domain · torrent",
                 "infoHash": SINTEL_HASH, "sources": TRACKERS, "behaviorHints": {"bingeGroup": "test-torrent"}}]
    out = [
        {"name": "Test\nDirect", "description": "Apple test video · direct link (stands in for this title)",
         "url": DIRECT, "behaviorHints": {"bingeGroup": "test-direct"}},
        {"name": "Test\nTorrent", "description": "Big Buck Bunny · public domain · torrent",
         "infoHash": BBB_HASH, "fileIdx": None, "sources": TRACKERS, "behaviorHints": {"bingeGroup": "test-torrent"}},
    ]
    out[1].pop("fileIdx")
    return out


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("%s %s" % (self.command, self.path))

    def send(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        p = u.path
        if p == "/link/create":
            return self.send({"result": {"success": True, "code": "TEST",
                                         "link": HOST_FOR_DEVICE + "/linkpage/TEST", "qrcode": ""}})
        if p == "/link/read":
            code = q.get("code", [""])[0]
            polls[code] = polls.get(code, 0) + 1
            if polls[code] >= 3:
                return self.send({"result": {"authKey": KEY}})
            return self.send({"error": {"code": 101, "message": "Invalid or expired token"}})
        if p == "/addon/manifest.json":
            return self.send(TEST_ADDON)
        if p.startswith("/addon/stream/"):
            _, _, _, type_, rest = p.split("/", 4)
            vid = urllib.request.unquote(rest[:-5])
            return self.send({"streams": streams_for(type_, vid)})
        if p.startswith("/addon/subtitles/"):
            return self.send({"subtitles": []})
        self.send({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        method = urlparse(self.path).path.removeprefix("/api/")
        if body.get("authKey") != KEY:
            return self.send({"error": {"code": 1, "message": "Session does not exist"}})
        if method == "getUser":
            return self.send({"result": {"_id": "u1", "email": "tester@example.com", "avatar": None}})
        if method == "addonCollectionGet":
            return self.send({"result": {"addons": [
                {"transportUrl": "https://v3-cinemeta.strem.io/manifest.json", "manifest": CINEMETA, "flags": {"official": True}},
                {"transportUrl": HOST_FOR_DEVICE + "/addon/manifest.json", "manifest": TEST_ADDON, "flags": {}},
                {"transportUrl": "https://opensubtitles-v3.strem.io/manifest.json", "manifest": OPENSUBS, "flags": {}},
            ], "lastModified": now()}})
        if method == "datastoreGet":
            return self.send({"result": list(LIBRARY.values())})
        if method == "datastorePut":
            for c in body.get("changes", []):
                LIBRARY[c["_id"]] = c
                st = c.get("state", {})
                print("  PUT %s  video=%s offset=%ss duration=%ss watched=%s removed=%s temp=%s" % (
                    c.get("name"), st.get("video_id"), st.get("timeOffset", 0) // 1000, st.get("duration", 0) // 1000,
                    st.get("flaggedWatched"), c.get("removed"), c.get("temp")))
            return self.send({"result": {"success": True}})
        self.send({"error": {"message": "unknown method " + method}})


if __name__ == "__main__":
    print("Mock Stremio on :%d (device sees %s)" % (PORT, HOST_FOR_DEVICE))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
