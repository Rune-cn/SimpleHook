#!/usr/bin/env python3
"""stdio ⇄ loopback bridge for the SimpleHook MCP server.

Android apps cannot talk to a host's stdin/stdout, so the app exposes the same JSON-RPC
core over a loopback TCP socket with one JSON document per line. This script connects to
that socket and relays lines between the host's stdio and the socket, which makes the app
usable from clients that insist on spawning a stdio server (Claude Desktop, most IDE
integrations).

Usage:
    # through adb (phone connected by USB)
    adb forward tcp:57322 tcp:57322
    python3 simplehook_mcp_stdio.py --token shm_xxx

    # bridge over an existing reverse tunnel / same network
    python3 simplehook_mcp_stdio.py --host 127.0.0.1 --port 57322 --token shm_xxx

Client configuration (Claude Desktop example):
    {
      "mcpServers": {
        "simplehook": {
          "command": "python3",
          "args": ["/path/to/simplehook_mcp_stdio.py", "--token", "shm_xxx"]
        }
      }
    }

Protocol: JSON-RPC 2.0, one message per line, MCP revision 2026-07-28. Every request must
carry `params._meta["io.modelcontextprotocol/protocolVersion"]` and
`params._meta["io.modelcontextprotocol/clientCapabilities"]`; the app rejects anything else.
"""

from __future__ import annotations

import argparse
import socket
import sys
import threading

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 57322
MAX_LINE_BYTES = 8 * 1024 * 1024


def log(message: str) -> None:
    """Diagnostics go to stderr: stdout is reserved for protocol messages."""
    print(f"[simplehook-mcp] {message}", file=sys.stderr, flush=True)


def pump_stdin_to_socket(sock: socket.socket, stop: threading.Event) -> None:
    stdin = sys.stdin.buffer
    try:
        while not stop.is_set():
            line = stdin.readline(MAX_LINE_BYTES)
            if not line:
                break
            if not line.strip():
                continue
            sock.sendall(line if line.endswith(b"\n") else line + b"\n")
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass
    finally:
        stop.set()
        try:
            sock.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def pump_socket_to_stdout(sock: socket.socket, stop: threading.Event) -> None:
    stdout = sys.stdout.buffer
    buffer = b""
    try:
        while not stop.is_set():
            chunk = sock.recv(65536)
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                if line.strip():
                    stdout.write(line + b"\n")
                    stdout.flush()
    except (ConnectionResetError, OSError):
        pass
    finally:
        stop.set()


def main() -> int:
    parser = argparse.ArgumentParser(description="Bridge MCP stdio to the SimpleHook app.")
    parser.add_argument("--host", default=DEFAULT_HOST, help="loopback host of the app")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="line transport port")
    parser.add_argument("--token", required=True, help="access token shown in the app")
    parser.add_argument(
        "--timeout",
        type=float,
        default=10.0,
        help="connection timeout in seconds",
    )
    args = parser.parse_args()

    if args.host not in ("127.0.0.1", "localhost", "::1"):
        log(f"warning: {args.host} is not loopback; the token is sent in clear text")

    try:
        sock = socket.create_connection((args.host, args.port), timeout=args.timeout)
    except OSError as error:
        log(f"cannot connect to {args.host}:{args.port}: {error}")
        log("is the MCP server running and is 'adb forward' set up?")
        return 1

    # The first line is the token handshake; the app answers with an error if it is wrong.
    sock.sendall(args.token.encode("utf-8") + b"\n")
    sock.settimeout(None)

    stop = threading.Event()
    reader = threading.Thread(target=pump_socket_to_stdout, args=(sock, stop), daemon=True)
    reader.start()

    try:
        pump_stdin_to_socket(sock, stop)
    except KeyboardInterrupt:
        pass

    stop.set()
    try:
        sock.close()
    except OSError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
