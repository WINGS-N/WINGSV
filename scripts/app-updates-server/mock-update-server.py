#!/usr/bin/env python3
"""Local mock of GitHub Releases API for AppUpdateManager testing.

Serves files from `<this script's directory>/fixtures`:
    GET /releases?per_page=4   -> releases.json
    GET /assets/<filename>     -> any other file under fixtures/

The `fixtures/` directory is produced by prepare-fixtures.sh.
"""

import http.server
import os
import socketserver
import sys

PORT = int(os.environ.get("WINGSV_MOCK_PORT", "8080"))
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        status = args[1] if len(args) > 1 else ""
        print(f"[{self.command}] {self.path} -> {status}", flush=True)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/releases":
            self._serve("releases.json", "application/json")
            return
        if path.startswith("/assets/"):
            filename = os.path.basename(path[len("/assets/"):])
            if not filename or filename.startswith(".") or "/" in filename:
                self.send_error(400, "Bad asset name")
                return
            content_type = (
                "application/vnd.android.package-archive"
                if filename.endswith(".apk")
                else "application/octet-stream"
            )
            self._serve(filename, content_type)
            return
        self.send_error(404, f"Not found: {path}")

    def _serve(self, filename, content_type):
        full = os.path.join(ROOT, filename)
        if not os.path.isfile(full):
            self.send_error(404, f"File not found: {filename}")
            return
        size = os.path.getsize(full)
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(size))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(full, "rb") as f:
            while True:
                chunk = f.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)


if __name__ == "__main__":
    if not os.path.isdir(ROOT):
        print(f"fixtures directory missing: {ROOT}", file=sys.stderr)
        print("Run scripts/app-updates-server/prepare-fixtures.sh first.", file=sys.stderr)
        sys.exit(1)
    with socketserver.ThreadingTCPServer(("127.0.0.1", PORT), Handler) as httpd:
        httpd.allow_reuse_address = True
        print(f"Mock GitHub releases server listening on http://127.0.0.1:{PORT}/", flush=True)
        httpd.serve_forever()
