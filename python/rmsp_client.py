"""
RMSP Client for Columba

Reticulum Map Service Protocol client integration for Columba.
Handles RMSP server discovery and tile fetching over the Reticulum mesh.

This module integrates with the existing reticulum_wrapper.py to:
1. Parse RMSP announces from discovered map servers
2. Query servers for available map data
3. Fetch tile bundles for offline use
"""

import struct
import time
from typing import Optional, Dict, List, Any, Callable
from dataclasses import dataclass, field
from logging_utils import log_debug, log_info, log_warning, log_error

# Lazy imports - available after RNS is loaded
umsgpack = None
RNS = None


# RMSP Protocol Constants
RMSP_APP_NAME = "rmsp"
RMSP_ASPECT = "maps"
RMSP_VERSION = "0.1.0"

# Request paths
PATH_INFO = "/info"
PATH_QUERY = "/query"
PATH_FETCH = "/fetch"

# Formats
FORMAT_PMTILES = "pmtiles"
FORMAT_MICRO = "micro"


def _ensure_imports():
    """Ensure required modules are imported."""
    global umsgpack, RNS
    if umsgpack is None:
        try:
            import umsgpack as _umsgpack
            umsgpack = _umsgpack
        except ImportError:
            pass
    if RNS is None:
        try:
            import RNS as _RNS
            RNS = _RNS
        except ImportError:
            pass


@dataclass
class RmspServerInfo:
    """Information about a discovered RMSP map server."""
    destination_hash: bytes
    identity: Any  # RNS.Identity
    version: str
    name: str
    coverage: List[str]
    zoom_range: tuple
    formats: List[str]
    layers: List[str]
    updated: int
    size: Optional[int] = None
    last_seen: float = field(default_factory=time.time)
    hops: int = 0

    def covers_geohash(self, geohash: str) -> bool:
        """Check if this server covers the given geohash."""
        if not self.coverage or "" in self.coverage:
            return True
        return any(
            geohash.startswith(prefix) or prefix.startswith(geohash)
            for prefix in self.coverage
        )

    def to_dict(self) -> Dict:
        """Convert to dictionary for JSON serialization."""
        return {
            "destination_hash": self.destination_hash.hex(),
            "version": self.version,
            "name": self.name,
            "coverage": self.coverage,
            "zoom_range": list(self.zoom_range),
            "formats": self.formats,
            "layers": self.layers,
            "updated": self.updated,
            "size": self.size,
            "last_seen": int(self.last_seen * 1000),  # Convert to millis
            "hops": self.hops,
        }


@dataclass
class QueryResponse:
    """Response from a /query request."""
    available: bool
    geohash: str
    zoom_range: Optional[tuple] = None
    size: Optional[int] = None
    tile_count: Optional[int] = None
    eta: Optional[int] = None
    content_hash: Optional[bytes] = None
    updated: Optional[int] = None
    ttl: Optional[int] = None
    error_code: Optional[int] = None
    error_message: Optional[str] = None

    def to_dict(self) -> Dict:
        """Convert to dictionary for JSON serialization."""
        return {
            "available": self.available,
            "geohash": self.geohash,
            "zoom_range": list(self.zoom_range) if self.zoom_range else None,
            "size": self.size,
            "tile_count": self.tile_count,
            "eta": self.eta,
            "content_hash": self.content_hash.hex() if self.content_hash else None,
            "updated": self.updated,
            "ttl": self.ttl,
            "error_code": self.error_code,
            "error_message": self.error_message,
        }


class RmspClientWrapper:
    """
    RMSP Client wrapper for Columba integration.

    Works with the existing Reticulum instance from reticulum_wrapper.py.
    """

    def __init__(self):
        """Initialize RMSP client wrapper."""
        self.servers: Dict[bytes, RmspServerInfo] = {}
        self._initialized = False
        log_info("RmspClient", "__init__", "RMSP client wrapper created")

    def initialize(self):
        """Initialize with RNS. Call after Reticulum is started."""
        _ensure_imports()
        if RNS is None:
            log_error("RmspClient", "initialize", "RNS not available")
            return False

        self._initialized = True
        log_info("RmspClient", "initialize", "RMSP client initialized")
        return True

    def parse_rmsp_announce(
        self,
        destination_hash: bytes,
        identity: Any,
        app_data: bytes,
        hops: int = 0,
    ) -> Optional[RmspServerInfo]:
        """
        Parse an RMSP announce and store the server info.

        Args:
            destination_hash: Server's destination hash
            identity: RNS identity object
            app_data: Announce app data (msgpack)
            hops: Number of network hops

        Returns:
            RmspServerInfo if valid, None otherwise
        """
        _ensure_imports()
        if umsgpack is None:
            log_error("RmspClient", "parse_rmsp_announce", "umsgpack not available")
            return None

        try:
            data = umsgpack.unpackb(app_data)

            # Validate version
            server_version = data.get("v", "0.0.0")

            server = RmspServerInfo(
                destination_hash=destination_hash,
                identity=identity,
                version=server_version,
                name=data.get("n", "Unknown"),
                coverage=data.get("c", []),
                zoom_range=tuple(data.get("z", [0, 15])),
                formats=data.get("f", [FORMAT_PMTILES]),
                layers=data.get("l", ["osm"]),
                updated=data.get("u", 0),
                size=data.get("s"),
                hops=hops,
            )

            self.servers[destination_hash] = server
            log_info("RmspClient", "parse_rmsp_announce",
                     f"Discovered RMSP server: {server.name} hash={destination_hash.hex()[:16]} (hops: {hops})")

            return server

        except Exception as e:
            log_error("RmspClient", "parse_rmsp_announce", f"Failed to parse: {e}")
            return None

    def get_servers(self) -> List[Dict]:
        """Get all known servers as JSON-serializable dicts."""
        return [server.to_dict() for server in self.servers.values()]

    def get_servers_for_geohash(self, geohash: str) -> List[Dict]:
        """Get servers that cover the given geohash."""
        return [
            server.to_dict()
            for server in self.servers.values()
            if server.covers_geohash(geohash)
        ]

    def get_nearest_servers(self, limit: int = 5) -> List[Dict]:
        """Get nearest servers by hop count."""
        sorted_servers = sorted(self.servers.values(), key=lambda s: s.hops)
        return [server.to_dict() for server in sorted_servers[:limit]]

    def get_server(self, destination_hash_hex: str) -> Optional[Dict]:
        """Get a specific server by destination hash."""
        try:
            dest_hash = bytes.fromhex(destination_hash_hex)
            server = self.servers.get(dest_hash)
            return server.to_dict() if server else None
        except Exception as e:
            log_error("RmspClient", "get_server", f"Error: {e}")
            return None

    def query_server(
        self,
        destination_hash_hex: str,
        geohash: str,
        zoom_range: Optional[List[int]] = None,
        format: Optional[str] = None,
        timeout: float = 30.0,
    ) -> Dict:
        """
        Query a server for available map data.

        Args:
            destination_hash_hex: Server destination hash as hex string
            geohash: Geohash area to query
            zoom_range: Optional [min_zoom, max_zoom]
            format: Optional format (pmtiles, micro)
            timeout: Request timeout in seconds

        Returns:
            QueryResponse as dict
        """
        _ensure_imports()

        try:
            dest_hash = bytes.fromhex(destination_hash_hex)
            server = self.servers.get(dest_hash)
            if not server:
                return QueryResponse(
                    available=False,
                    geohash=geohash,
                    error_code=-1,
                    error_message="Server not found",
                ).to_dict()

            # Build request
            request = {"g": geohash}
            if zoom_range:
                request["z"] = zoom_range
            if format:
                request["f"] = format

            # Establish link and send request
            link = self._establish_link(server, timeout)
            if not link:
                return QueryResponse(
                    available=False,
                    geohash=geohash,
                    error_code=-1,
                    error_message="Failed to establish link",
                ).to_dict()

            try:
                response_data = self._request(link, PATH_QUERY, request, timeout)
                if response_data is None:
                    return QueryResponse(
                        available=False,
                        geohash=geohash,
                        error_code=-1,
                        error_message="Request timed out",
                    ).to_dict()

                response = umsgpack.unpackb(response_data)

                # Check for error
                if "e" in response:
                    return QueryResponse(
                        available=False,
                        geohash=geohash,
                        error_code=response.get("e"),
                        error_message=response.get("m"),
                    ).to_dict()

                return QueryResponse(
                    available=response.get("a", False),
                    geohash=response.get("g", geohash),
                    zoom_range=tuple(response["z"]) if "z" in response else None,
                    size=response.get("s"),
                    tile_count=response.get("t"),
                    eta=response.get("eta"),
                    content_hash=response.get("h"),
                    updated=response.get("u"),
                    ttl=response.get("ttl"),
                ).to_dict()

            finally:
                link.teardown()

        except Exception as e:
            log_error("RmspClient", "query_server", f"Query failed: {e}")
            return QueryResponse(
                available=False,
                geohash=geohash,
                error_code=-1,
                error_message=str(e),
            ).to_dict()

    def fetch_tiles(
        self,
        destination_hash_hex: str,
        public_key: bytes,
        geohash: str,
        zoom_range: Optional[List[int]] = None,
        format: Optional[str] = None,
        timeout: float = 3600.0,
    ) -> Optional[bytes]:
        """
        Fetch tile data from a server.

        Args:
            destination_hash_hex: Server destination hash as hex string
            public_key: Server's RNS identity public key (for establishing link)
            geohash: Geohash area to fetch
            zoom_range: Optional [min_zoom, max_zoom]
            format: Optional format (pmtiles, micro)
            timeout: Request timeout in seconds

        Returns:
            Raw tile data bytes, or None on failure
        """
        _ensure_imports()

        try:
            dest_hash = bytes.fromhex(destination_hash_hex)

            # Try to get server from cache first (populated by announces)
            server = self.servers.get(dest_hash)

            # If not in cache, wait for an announce to arrive
            if not server:
                log_info("RmspClient", "fetch_tiles",
                        f"Server not in cache, waiting for announce...")
                wait_timeout = min(timeout, 60.0)  # Wait up to 60s for announce
                start = time.time()
                while not server and (time.time() - start) < wait_timeout:
                    time.sleep(1.0)
                    server = self.servers.get(dest_hash)
                    if server:
                        log_info("RmspClient", "fetch_tiles",
                                f"Server appeared in cache after {time.time() - start:.1f}s")

            # If still not in cache but we have public key, create from public key
            if not server and public_key:
                log_info("RmspClient", "fetch_tiles",
                        f"Server not discovered, creating from public key")
                try:
                    # Create identity from public key
                    identity = RNS.Identity(create_keys=False)
                    identity.load_public_key(public_key)

                    # Create a temporary server info
                    server = RmspServerInfo(
                        destination_hash=dest_hash,
                        identity=identity,
                        version="0.1.0",
                        name="Remote Server",
                        coverage=[],
                        zoom_range=(0, 15),
                        formats=[FORMAT_PMTILES],
                        layers=["osm"],
                        updated=0,
                        hops=0,
                    )
                    log_info("RmspClient", "fetch_tiles",
                            "Created server info from public key")
                except Exception as e:
                    log_error("RmspClient", "fetch_tiles",
                             f"Failed to create identity from public key: {e}")
                    return None

            if not server:
                log_error("RmspClient", "fetch_tiles", "Server not found and no public key")
                return None

            # Build request
            request = {"g": geohash}
            if zoom_range:
                request["z"] = zoom_range
            if format:
                request["f"] = format

            # Establish link and send request
            link = self._establish_link(server, min(timeout, 60.0))
            if not link:
                log_error("RmspClient", "fetch_tiles", "Failed to establish link")
                return None

            try:
                response_data = self._request(link, PATH_FETCH, request, timeout)
                if response_data is None:
                    log_error("RmspClient", "fetch_tiles", "Request timed out")
                    return None

                # Check if it's an error response by detecting msgpack map format
                # Error responses are msgpack dicts, tile data starts with u32 tile count
                # Msgpack maps start with: 0x80-0x8f (fixmap), 0xde (map16), 0xdf (map32)
                if response_data and len(response_data) > 0:
                    first_byte = response_data[0]
                    is_msgpack_map = (0x80 <= first_byte <= 0x8f) or first_byte in (0xde, 0xdf)
                    if is_msgpack_map:
                        try:
                            response = umsgpack.unpackb(response_data)
                            if isinstance(response, dict) and "e" in response:
                                log_error("RmspClient", "fetch_tiles",
                                         f"Fetch error: {response.get('m')}")
                                return None
                        except Exception:
                            pass  # Malformed msgpack, treat as tile data

                log_info("RmspClient", "fetch_tiles",
                        f"Received {len(response_data)} bytes")
                return response_data

            finally:
                link.teardown()

        except Exception as e:
            log_error("RmspClient", "fetch_tiles", f"Fetch failed: {e}")
            return None

    def _establish_link(self, server: RmspServerInfo, timeout: float) -> Optional[Any]:
        """Establish a link to a server."""
        if RNS is None:
            return None

        try:
            # First check if we have a path to this destination
            dest_hash_hex = server.destination_hash.hex()[:16]
            log_info("RmspClient", "_establish_link",
                    f"Checking path to {dest_hash_hex}...")

            if not RNS.Transport.has_path(server.destination_hash):
                log_info("RmspClient", "_establish_link",
                        f"No path to {dest_hash_hex}, requesting...")
                RNS.Transport.request_path(server.destination_hash)

                # Wait for path to be established
                path_timeout = min(timeout, 30.0)
                start = time.time()
                sleep_duration = 0.1  # Start with 100ms
                while not RNS.Transport.has_path(server.destination_hash):
                    if time.time() - start > path_timeout:
                        log_error("RmspClient", "_establish_link",
                                 "Path request timeout")
                        return None
                    time.sleep(sleep_duration)
                    sleep_duration = min(sleep_duration * 1.5, 2.0)  # Exponential backoff, max 2s
                log_info("RmspClient", "_establish_link", "Path established")

            # Create destination
            dest = RNS.Destination(
                server.identity,
                RNS.Destination.OUT,
                RNS.Destination.SINGLE,
                RMSP_APP_NAME,
                RMSP_ASPECT,
            )

            log_debug("RmspClient", "_establish_link",
                     f"Creating link to {server.destination_hash.hex()[:16]}...")

            link = RNS.Link(dest)

            start = time.time()
            sleep_duration = 0.05  # Start with 50ms
            while link.status != RNS.Link.ACTIVE:
                if link.status == RNS.Link.CLOSED:
                    log_error("RmspClient", "_establish_link", "Link closed")
                    return None
                if time.time() - start > timeout:
                    log_error("RmspClient", "_establish_link", "Link timeout")
                    link.teardown()
                    return None
                time.sleep(sleep_duration)
                sleep_duration = min(sleep_duration * 1.5, 1.0)  # Exponential backoff, max 1s

            log_info("RmspClient", "_establish_link", "Link active")
            return link

        except Exception as e:
            import traceback
            log_error("RmspClient", "_establish_link", f"Error: {e}")
            log_error("RmspClient", "_establish_link", f"Traceback: {traceback.format_exc()}")
            return None

    def _request(
        self,
        link: Any,
        path: str,
        data: dict,
        timeout: float,
    ) -> Optional[bytes]:
        """Send a request and wait for response."""
        result = {"response": None, "done": False}

        def on_response(receipt):
            result["response"] = receipt.response
            result["done"] = True

        def on_failed(receipt):
            log_error("RmspClient", "_request", "Request failed")
            result["done"] = True

        link.request(
            path,
            data=umsgpack.packb(data),
            response_callback=on_response,
            failed_callback=on_failed,
            timeout=timeout,
        )

        start = time.time()
        while not result["done"]:
            if time.time() - start > timeout:
                return None
            time.sleep(0.1)

        return result["response"]

    def clear_servers(self):
        """Clear all known servers."""
        self.servers.clear()
        log_info("RmspClient", "clear_servers", "Cleared all servers")

    def remove_stale_servers(self, max_age_seconds: float = 3600.0):
        """Remove servers not seen recently."""
        cutoff = time.time() - max_age_seconds
        stale = [h for h, s in self.servers.items() if s.last_seen < cutoff]
        for h in stale:
            del self.servers[h]
        if stale:
            log_info("RmspClient", "remove_stale_servers",
                    f"Removed {len(stale)} stale servers")


def unpack_tiles(data: bytes) -> List[tuple]:
    """
    Unpack tile data received from RMSP server.

    Args:
        data: Raw tile data from /fetch response

    Returns:
        List of (z, x, y, tile_data) tuples
    """
    if len(data) < 4:
        return []

    offset = 0
    tile_count = struct.unpack(">I", data[offset:offset+4])[0]
    offset += 4

    # Validate tile count to prevent DoS
    if tile_count > 100000:
        log_warning("RmspClient", "unpack_tiles",
                   f"Invalid tile count: {tile_count} (max 100000)")
        return []

    tiles = []
    for _ in range(tile_count):
        if offset + 13 > len(data):
            break

        z, x, y, size = struct.unpack(">BIII", data[offset:offset+13])
        offset += 13

        # Validate tile size to prevent OOM
        if size > 1000000:  # 1MB max
            log_warning("RmspClient", "unpack_tiles",
                       f"Invalid tile size: {size} bytes (max 1MB)")
            break

        if offset + size > len(data):
            log_warning("RmspClient", "unpack_tiles",
                       f"Truncated tile data (need {size} bytes, have {len(data) - offset})")
            break

        tile_data = data[offset:offset+size]
        offset += size

        tiles.append((z, x, y, tile_data))

    return tiles


# Singleton instance for use from Kotlin
_rmsp_client: Optional[RmspClientWrapper] = None


def get_rmsp_client() -> RmspClientWrapper:
    """Get or create the singleton RMSP client instance."""
    global _rmsp_client
    if _rmsp_client is None:
        _rmsp_client = RmspClientWrapper()
    return _rmsp_client
