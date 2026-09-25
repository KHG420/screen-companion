#!/usr/bin/env python3
"""Local browser development server; /api/ proxies to the Go signaling service."""
import argparse
import http.client
import http.server
import pathlib
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=18861)
parser.add_argument('--backend-port', type=int, default=18765)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1] / 'web'
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(root), **kw)
    def proxy(self):
        if not self.path.startswith('/api/'):
            self.send_error(404)
            return
        connection = http.client.HTTPConnection('127.0.0.1', args.backend_port, timeout=28)
        try:
            body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            headers = {k: v for k, v in self.headers.items() if k.lower() in ('authorization', 'content-type', 'accept')}
            connection.request(self.command, self.path[4:], body=body, headers=headers)
            response = connection.getresponse()
            data = response.read()
            self.send_response(response.status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(data)
        except (OSError, http.client.HTTPException):
            self.send_error(502, 'Start the Go signaling server first')
        finally:
            connection.close()
    def do_GET(self):
        if self.path.startswith('/api/'):
            self.proxy()
        else:
            super().do_GET()
    do_POST = proxy
    do_DELETE = proxy
http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
