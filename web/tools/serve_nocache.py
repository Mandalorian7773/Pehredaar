#!/usr/bin/env python3
"""Static server for the demo with caching turned off.

    python3 tools/serve_nocache.py [port]
    python3 tools/serve_nocache.py --selftest

Plain `python3 -m http.server` sends Last-Modified and no Cache-Control, so browsers
keep serving a stale index.html after every edit — which looks exactly like a change
that did not land.

It also has no Range support: it answers every request 200 with the whole file, so
`video.currentTime = x` seeks into a region the browser cannot fetch and snaps back
to the start. Chrome needs a 206 to seek, so this adds single-range replies.
"""
import io
import os
import re
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"^bytes=(\d*)-(\d*)$")


def byte_range(header, size):
    """Inclusive (start, end) for a single byte range.

    None when the header is absent or is anything we do not serve ourselves
    (multi-range, garbage) and the caller should send the whole file; ()
    when the range is unsatisfiable and the caller should send 416.
    """
    m = RANGE_RE.match((header or "").strip())
    if not m:
        return None
    first, last = m.group(1), m.group(2)
    if first:                        # bytes=N-  or  bytes=N-M
        start, end = int(first), int(last) if last else size - 1
    elif last:                       # bytes=-N, the final N bytes
        start, end = max(0, size - int(last)), size - 1
    else:                            # bytes=-, meaningless
        return None
    end = min(end, size - 1)
    return (start, end) if start <= end else ()


class NoCache(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        self.send_header("Accept-Ranges", "bytes")
        super().end_headers()

    def send_head(self):
        if "Range" not in self.headers:
            return super().send_head()
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "File not found")
            return None
        with f:
            size = os.fstat(f.fileno()).st_size
            rng = byte_range(self.headers["Range"], size)
            if rng is None:
                f.seek(0)
                return self._whole(f, path, size)
            if rng == ():
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            start, end = rng
            f.seek(start)
            # ponytail: the slice is buffered in memory. Chrome asks for bounded
            # ranges; an open-ended bytes=0- on a huge file would hold it all.
            # Stream it if this ever serves something that does not fit.
            body = f.read(end - start + 1)
            self.send_response(206)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return io.BytesIO(body)

    def _whole(self, f, path, size):
        body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Length", str(size))
        self.end_headers()
        return io.BytesIO(body)

    def log_message(self, fmt, *args):
        pass


def _selftest():
    assert byte_range("bytes=0-99", 1000) == (0, 99)
    assert byte_range("bytes=500-", 1000) == (500, 999)
    assert byte_range("bytes=-100", 1000) == (900, 999)
    assert byte_range("bytes=0-99999", 1000) == (0, 999)     # clamped to the file
    assert byte_range("bytes=-99999", 1000) == (0, 999)      # suffix longer than file
    assert byte_range("bytes=999-999", 1000) == (999, 999)   # single final byte
    assert byte_range("bytes=1000-", 1000) == ()             # past the end -> 416
    assert byte_range("bytes=-", 1000) is None
    assert byte_range("bytes=0-1, 5-6", 1000) is None        # multi-range -> whole file
    assert byte_range("", 1000) is None
    assert byte_range(None, 1000) is None
    print("selftest ok")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        _selftest()
        raise SystemExit(0)
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), partial(NoCache, directory=".")).serve_forever()
