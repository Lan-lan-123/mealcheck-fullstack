"""Fixed TCP relays for host acceptance; backend/model networks remain internal.

No configurable destination or HTTP CONNECT support is exposed.
"""
import select
import socket
import socketserver
import threading


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(self.server.destination, timeout=5) as upstream:
                upstream.settimeout(None)
                peers = {self.request: upstream, upstream: self.request}
                while True:
                    readable, _, _ = select.select(list(peers), [], [], 90)
                    if not readable:
                        return
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        peers[source].sendall(data)
        except OSError:
            # Restarts and deliberate SIGKILL are part of this fixture.
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    for port, host, target in ((18080, 'backend-a', 8080), (18081, 'backend-b', 8080),
                               (19082, 'model-stub', 9082)):
        server = Server(('0.0.0.0', port), Relay)
        server.destination = (host, target)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    threading.Event().wait()
