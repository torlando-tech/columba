"""TorClientInterface - SOCKS5 proxy support for Reticulum TCP connections.

Subclasses TCPClientInterface to route connections through a SOCKS5 proxy
(e.g., Orbot for Tor .onion address support).

Uses SOCKS5 Username/Password authentication for Tor stream isolation —
each interface gets its own Tor circuit via unique username.

Deployed as an RNS external interface module to {configdir}/interfaces/.

Configuration:
    [[Tor Hidden Service]]
      type = TorClientInterface
      enabled = yes
      target_host = abcdef1234567890.onion
      target_port = 4242
      proxy_host = 127.0.0.1
      proxy_port = 9050
"""

import struct
import socket
import platform

import RNS
from RNS.Interfaces.Interface import Interface
from RNS.Interfaces.TCPInterface import TCPClientInterface


class TorClientInterface(TCPClientInterface):
    INITIAL_CONNECT_TIMEOUT = 30  # Tor circuits are slower than direct TCP

    # Tor-appropriate keepalive settings (modeled on I2P values in upstream RNS)
    TOR_USER_TIMEOUT = 45
    TOR_PROBE_AFTER  = 10
    TOR_PROBE_INTERVAL = 9
    TOR_PROBES = 5

    def __init__(self, owner, configuration, connected_socket=None):
        # Extract proxy params BEFORE super().__init__ triggers connect()
        c = Interface.get_config_obj(configuration)
        self.proxy_host = c.get('proxy_host', '127.0.0.1')
        self.proxy_port = int(c.get('proxy_port', 9050))

        super().__init__(owner, configuration, connected_socket)

    def connect(self, initial=False):
        try:
            if initial:
                RNS.log(
                    f"Establishing Tor/SOCKS5 connection for {self} "
                    f"via {self.proxy_host}:{self.proxy_port}...",
                    RNS.LOG_DEBUG
                )

            # Close previous socket to prevent resource leak during reconnect
            if self.socket is not None:
                try:
                    self.socket.close()
                except Exception:
                    pass
                self.socket = None

            # Resolve the proxy address (supports both IPv4 and IPv6 proxies)
            proxy_info = socket.getaddrinfo(
                self.proxy_host, self.proxy_port, proto=socket.IPPROTO_TCP
            )[0]
            proxy_family = proxy_info[0]
            proxy_address = proxy_info[4]

            self.socket = socket.socket(proxy_family, socket.SOCK_STREAM)
            self.socket.settimeout(TorClientInterface.INITIAL_CONNECT_TIMEOUT)
            self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.socket.connect(proxy_address)

            # SOCKS5 handshake — after this, socket is tunneled to target
            self._socks5_handshake()

            self.socket.settimeout(None)

            if initial:
                RNS.log(
                    f"Tor/SOCKS5 connection for {self} established "
                    f"to {self.target_ip}:{self.target_port}",
                    RNS.LOG_DEBUG
                )

        except Exception as e:
            if self.socket is not None:
                try:
                    self.socket.close()
                except Exception:
                    pass
                self.socket = None

            if initial:
                RNS.log(
                    f"Initial Tor/SOCKS5 connection for {self} "
                    f"could not be established: {e}",
                    RNS.LOG_ERROR
                )
                RNS.log(
                    f"Leaving unconnected and retrying connection in "
                    f"{TCPClientInterface.RECONNECT_WAIT} seconds.",
                    RNS.LOG_ERROR
                )
                return False
            else:
                raise e

        if platform.system() == "Linux":
            self.set_timeouts_linux()
        elif platform.system() == "Darwin":
            self.set_timeouts_osx()

        self.online = True
        self.writing = False
        self.never_connected = False

        return True

    def _recv_exact(self, n):
        """Read exactly n bytes from the socket, raising on premature close."""
        buf = b''
        while len(buf) < n:
            chunk = self.socket.recv(n - len(buf))
            if not chunk:
                raise ConnectionError(
                    f"SOCKS5 proxy closed connection "
                    f"(received {len(buf)}/{n} bytes)"
                )
            buf += chunk
        return buf

    def _socks5_handshake(self):
        """SOCKS5 handshake with username auth for Tor stream isolation.

        Uses Username/Password auth (RFC 1929) so Tor assigns a unique
        circuit per interface — different usernames = different circuits.
        """
        # Greeting: version 5, 1 method, Username/Password (0x02)
        self.socket.sendall(b'\x05\x01\x02')
        resp = self._recv_exact(2)
        if resp != b'\x05\x02':
            raise ConnectionError(
                f"SOCKS5 proxy rejected Username/Password auth "
                f"(response: {resp.hex()}). "
                f"Ensure proxy supports method 0x02."
            )

        # Username/Password sub-negotiation (RFC 1929)
        # Encode target as username for Tor stream isolation
        username = f"{self.target_ip}:{self.target_port}".encode('utf-8')[:255]
        password = b'x'  # Tor ignores the password
        self.socket.sendall(
            b'\x01'
            + bytes([len(username)]) + username
            + bytes([len(password)]) + password
        )
        resp = self._recv_exact(2)
        if resp[1] != 0x00:
            raise ConnectionError("SOCKS5 username/password auth failed")

        # Connect request with domain name (ATYP=0x03)
        # Proxy resolves the hostname — required for .onion addresses
        dest_host_bytes = self.target_ip.encode('utf-8')
        if len(dest_host_bytes) > 255:
            raise ConnectionError(
                f"Target hostname too long for SOCKS5 "
                f"({len(dest_host_bytes)} bytes, max 255)"
            )
        dest_port_bytes = struct.pack('!H', self.target_port)
        connect_req = (
            b'\x05\x01\x00\x03'
            + bytes([len(dest_host_bytes)])
            + dest_host_bytes
            + dest_port_bytes
        )
        self.socket.sendall(connect_req)

        # Read connect response header (VER, REP, RSV, ATYP)
        resp = self._recv_exact(4)
        if resp[1] != 0x00:
            error_codes = {
                0x01: "general SOCKS server failure",
                0x02: "connection not allowed by ruleset",
                0x03: "network unreachable",
                0x04: "host unreachable",
                0x05: "connection refused",
                0x06: "TTL expired",
                0x07: "command not supported",
                0x08: "address type not supported",
            }
            error_msg = error_codes.get(
                resp[1], f"unknown error (0x{resp[1]:02x})"
            )
            raise ConnectionError(
                f"SOCKS5 connect to {self.target_ip}:{self.target_port} "
                f"failed: {error_msg}"
            )

        # Consume bind address based on ATYP
        atyp = resp[3]
        if atyp == 0x01:        # IPv4: 4 addr + 2 port
            self._recv_exact(6)
        elif atyp == 0x03:      # Domain: 1 len + domain + 2 port
            domain_len = self._recv_exact(1)[0]
            self._recv_exact(domain_len + 2)
        elif atyp == 0x04:      # IPv6: 16 addr + 2 port
            self._recv_exact(18)
        else:
            raise ConnectionError(
                f"SOCKS5 proxy returned unsupported address type: 0x{atyp:02x}"
            )

        # Socket is now tunneled through SOCKS5 to the target

    def set_timeouts_linux(self):
        """Tor-appropriate TCP keepalive timeouts for Linux/Android."""
        self.socket.setsockopt(
            socket.IPPROTO_TCP, socket.TCP_USER_TIMEOUT,
            int(TorClientInterface.TOR_USER_TIMEOUT * 1000)
        )
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        self.socket.setsockopt(
            socket.IPPROTO_TCP, socket.TCP_KEEPIDLE,
            int(TorClientInterface.TOR_PROBE_AFTER)
        )
        self.socket.setsockopt(
            socket.IPPROTO_TCP, socket.TCP_KEEPINTVL,
            int(TorClientInterface.TOR_PROBE_INTERVAL)
        )
        self.socket.setsockopt(
            socket.IPPROTO_TCP, socket.TCP_KEEPCNT,
            int(TorClientInterface.TOR_PROBES)
        )

    def set_timeouts_osx(self):
        """Tor-appropriate TCP keepalive timeouts for macOS."""
        TCP_KEEPIDLE = 0x10
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
        self.socket.setsockopt(
            socket.IPPROTO_TCP, TCP_KEEPIDLE,
            int(TorClientInterface.TOR_PROBE_AFTER)
        )

    def __str__(self):
        return f"TorClientInterface[{self.name}]"


# RNS external interface module convention
interface_class = TorClientInterface
