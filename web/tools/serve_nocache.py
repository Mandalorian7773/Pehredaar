#!/usr/bin/env python3
"""Static server for the demo with caching turned off.

    python3 tools/serve_nocache.py [port]

Plain `python3 -m http.server` sends Last-Modified and no Cache-Control, so browsers
keep serving a stale index.html after every edit — which looks exactly like a change
that did not land. Range requests still work, so video seeking is unaffected.
"""
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class NoCache(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), partial(NoCache, directory=".")).serve_forever()
