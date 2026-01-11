"""
Test suite for RMSP Client module.

Tests the RmspServerInfo, QueryResponse, RmspClientWrapper classes,
and related utility functions for map tile fetching over Reticulum.
"""

import sys
import os
import struct
import time
import unittest
from unittest.mock import Mock, MagicMock, patch
from dataclasses import FrozenInstanceError

# Add parent directory to path to import rmsp_client
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# Try to import u-msgpack-python, install if missing
try:
    import umsgpack
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, '-m', 'pip', 'install', 'u-msgpack-python', '-q'])
    import umsgpack

# Make umsgpack available BEFORE importing rmsp_client
sys.modules['umsgpack'] = umsgpack

# Mock RNS before importing rmsp_client
mock_rns = MagicMock()
sys.modules['RNS'] = mock_rns

# Now import the module under test
import rmsp_client
# Ensure umsgpack and RNS are available in the module
rmsp_client.umsgpack = umsgpack
rmsp_client.RNS = mock_rns


class TestProtocolConstants(unittest.TestCase):
    """Test that RMSP protocol constants are correctly defined."""

    def test_rmsp_app_name(self):
        """RMSP_APP_NAME should be 'rmsp'."""
        self.assertEqual(rmsp_client.RMSP_APP_NAME, "rmsp")

    def test_rmsp_aspect(self):
        """RMSP_ASPECT should be 'maps'."""
        self.assertEqual(rmsp_client.RMSP_ASPECT, "maps")

    def test_rmsp_version(self):
        """RMSP_VERSION should be defined."""
        self.assertEqual(rmsp_client.RMSP_VERSION, "0.1.0")

    def test_path_info(self):
        """PATH_INFO should be '/info'."""
        self.assertEqual(rmsp_client.PATH_INFO, "/info")

    def test_path_query(self):
        """PATH_QUERY should be '/query'."""
        self.assertEqual(rmsp_client.PATH_QUERY, "/query")

    def test_path_fetch(self):
        """PATH_FETCH should be '/fetch'."""
        self.assertEqual(rmsp_client.PATH_FETCH, "/fetch")

    def test_format_pmtiles(self):
        """FORMAT_PMTILES should be 'pmtiles'."""
        self.assertEqual(rmsp_client.FORMAT_PMTILES, "pmtiles")

    def test_format_micro(self):
        """FORMAT_MICRO should be 'micro'."""
        self.assertEqual(rmsp_client.FORMAT_MICRO, "micro")


class TestRmspServerInfo(unittest.TestCase):
    """Test the RmspServerInfo dataclass."""

    def setUp(self):
        """Set up test fixtures."""
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)
        self.server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test Server",
            coverage=["u4pr", "u4pq"],
            zoom_range=(0, 15),
            formats=["pmtiles"],
            layers=["osm"],
            updated=1703980800,
            size=1024000,
            hops=2,
        )

    def test_initialization(self):
        """Test server info initialization."""
        self.assertEqual(self.server.destination_hash, self.dest_hash)
        self.assertEqual(self.server.version, "0.1.0")
        self.assertEqual(self.server.name, "Test Server")
        self.assertEqual(self.server.coverage, ["u4pr", "u4pq"])
        self.assertEqual(self.server.zoom_range, (0, 15))
        self.assertEqual(self.server.formats, ["pmtiles"])
        self.assertEqual(self.server.layers, ["osm"])
        self.assertEqual(self.server.updated, 1703980800)
        self.assertEqual(self.server.size, 1024000)
        self.assertEqual(self.server.hops, 2)

    def test_last_seen_defaults_to_current_time(self):
        """Test that last_seen defaults to approximately current time."""
        before = time.time()
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        after = time.time()
        self.assertGreaterEqual(server.last_seen, before)
        self.assertLessEqual(server.last_seen, after)

    def test_size_defaults_to_none(self):
        """Test that size defaults to None."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertIsNone(server.size)

    def test_hops_defaults_to_zero(self):
        """Test that hops defaults to 0."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertEqual(server.hops, 0)


class TestRmspServerInfoCoversGeohash(unittest.TestCase):
    """Test the covers_geohash method."""

    def setUp(self):
        """Set up test fixtures."""
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)

    def test_empty_coverage_covers_all(self):
        """Empty coverage list means global coverage."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Global Server",
            coverage=[],  # Empty = global
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertTrue(server.covers_geohash("u4pr"))
        self.assertTrue(server.covers_geohash("ezs42"))
        self.assertTrue(server.covers_geohash("gcpvj"))

    def test_empty_string_in_coverage_covers_all(self):
        """Empty string in coverage means global coverage."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Global Server",
            coverage=[""],  # Empty string = global
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertTrue(server.covers_geohash("u4pr"))
        self.assertTrue(server.covers_geohash("anything"))

    def test_exact_geohash_match(self):
        """Test exact geohash matching."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Local Server",
            coverage=["u4pr", "u4pq"],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertTrue(server.covers_geohash("u4pr"))
        self.assertTrue(server.covers_geohash("u4pq"))
        self.assertFalse(server.covers_geohash("ezs42"))

    def test_geohash_starts_with_coverage_prefix(self):
        """Test geohash that starts with coverage prefix."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Regional Server",
            coverage=["u4"],  # Covers all u4* geohashes
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        self.assertTrue(server.covers_geohash("u4pr"))
        self.assertTrue(server.covers_geohash("u4pq"))
        self.assertTrue(server.covers_geohash("u4xyz"))
        self.assertFalse(server.covers_geohash("ezs42"))

    def test_coverage_prefix_starts_with_geohash(self):
        """Test coverage prefix that starts with the queried geohash."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Local Server",
            coverage=["u4prxyz"],  # More specific than query
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        # Query is prefix of coverage - still covered
        self.assertTrue(server.covers_geohash("u4pr"))
        self.assertTrue(server.covers_geohash("u4"))
        self.assertFalse(server.covers_geohash("u5"))


class TestRmspServerInfoToDict(unittest.TestCase):
    """Test the to_dict method."""

    def setUp(self):
        """Set up test fixtures."""
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)

    def test_to_dict_returns_dict(self):
        """to_dict should return a dictionary."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test Server",
            coverage=["u4pr"],
            zoom_range=(0, 15),
            formats=["pmtiles"],
            layers=["osm"],
            updated=1703980800,
            size=1024000,
            last_seen=1703980900.5,
            hops=2,
        )
        result = server.to_dict()
        self.assertIsInstance(result, dict)

    def test_to_dict_converts_destination_hash_to_hex(self):
        """Destination hash should be hex string in dict."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )
        result = server.to_dict()
        self.assertEqual(result["destination_hash"], self.dest_hash.hex())

    def test_to_dict_converts_zoom_range_to_list(self):
        """Zoom range tuple should be list in dict."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(5, 18),
            formats=[],
            layers=[],
            updated=0,
        )
        result = server.to_dict()
        self.assertEqual(result["zoom_range"], [5, 18])
        self.assertIsInstance(result["zoom_range"], list)

    def test_to_dict_converts_last_seen_to_millis(self):
        """Last seen should be converted to milliseconds."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
            last_seen=1703980800.123,
        )
        result = server.to_dict()
        self.assertEqual(result["last_seen"], 1703980800123)

    def test_to_dict_includes_all_fields(self):
        """to_dict should include all expected fields."""
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test Server",
            coverage=["u4pr"],
            zoom_range=(0, 15),
            formats=["pmtiles"],
            layers=["osm"],
            updated=1703980800,
            size=1024000,
            hops=2,
        )
        result = server.to_dict()
        expected_keys = [
            "destination_hash", "version", "name", "coverage",
            "zoom_range", "formats", "layers", "updated", "size",
            "last_seen", "hops"
        ]
        for key in expected_keys:
            self.assertIn(key, result)


class TestQueryResponse(unittest.TestCase):
    """Test the QueryResponse dataclass."""

    def test_initialization_minimal(self):
        """Test minimal initialization."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
        )
        self.assertTrue(response.available)
        self.assertEqual(response.geohash, "u4pr")
        self.assertIsNone(response.zoom_range)
        self.assertIsNone(response.size)

    def test_initialization_full(self):
        """Test full initialization."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
            zoom_range=(5, 15),
            size=2048000,
            tile_count=1500,
            eta=30,
            content_hash=bytes.fromhex("abcd1234"),
            updated=1703980800,
            ttl=3600,
        )
        self.assertTrue(response.available)
        self.assertEqual(response.geohash, "u4pr")
        self.assertEqual(response.zoom_range, (5, 15))
        self.assertEqual(response.size, 2048000)
        self.assertEqual(response.tile_count, 1500)
        self.assertEqual(response.eta, 30)
        self.assertEqual(response.content_hash, bytes.fromhex("abcd1234"))
        self.assertEqual(response.updated, 1703980800)
        self.assertEqual(response.ttl, 3600)

    def test_error_response(self):
        """Test error response initialization."""
        response = rmsp_client.QueryResponse(
            available=False,
            geohash="u4pr",
            error_code=-1,
            error_message="Server not found",
        )
        self.assertFalse(response.available)
        self.assertEqual(response.error_code, -1)
        self.assertEqual(response.error_message, "Server not found")


class TestQueryResponseToDict(unittest.TestCase):
    """Test QueryResponse.to_dict method."""

    def test_to_dict_returns_dict(self):
        """to_dict should return a dictionary."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
        )
        result = response.to_dict()
        self.assertIsInstance(result, dict)

    def test_to_dict_converts_zoom_range_to_list(self):
        """Zoom range tuple should be list in dict."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
            zoom_range=(5, 15),
        )
        result = response.to_dict()
        self.assertEqual(result["zoom_range"], [5, 15])
        self.assertIsInstance(result["zoom_range"], list)

    def test_to_dict_zoom_range_none(self):
        """Zoom range should be None if not set."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
        )
        result = response.to_dict()
        self.assertIsNone(result["zoom_range"])

    def test_to_dict_converts_content_hash_to_hex(self):
        """Content hash should be hex string in dict."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
            content_hash=bytes.fromhex("abcd1234"),
        )
        result = response.to_dict()
        self.assertEqual(result["content_hash"], "abcd1234")

    def test_to_dict_content_hash_none(self):
        """Content hash should be None if not set."""
        response = rmsp_client.QueryResponse(
            available=True,
            geohash="u4pr",
        )
        result = response.to_dict()
        self.assertIsNone(result["content_hash"])

    def test_to_dict_includes_error_fields(self):
        """to_dict should include error fields when set."""
        response = rmsp_client.QueryResponse(
            available=False,
            geohash="u4pr",
            error_code=-2,
            error_message="Timeout",
        )
        result = response.to_dict()
        self.assertEqual(result["error_code"], -2)
        self.assertEqual(result["error_message"], "Timeout")


class TestRmspClientWrapper(unittest.TestCase):
    """Test the RmspClientWrapper class."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()

    def test_initialization(self):
        """Test client wrapper initialization."""
        self.assertIsInstance(self.client.servers, dict)
        self.assertEqual(len(self.client.servers), 0)
        self.assertFalse(self.client._initialized)

    @patch('rmsp_client.RNS', new_callable=MagicMock)
    def test_initialize_success(self, mock_rns):
        """Test successful initialization."""
        result = self.client.initialize()
        self.assertTrue(result)
        self.assertTrue(self.client._initialized)

    @patch('rmsp_client._ensure_imports')
    def test_initialize_without_rns(self, mock_ensure):
        """Test initialization when RNS not available."""
        # Temporarily set RNS to None - mock _ensure_imports to prevent re-import
        original_rns = rmsp_client.RNS
        rmsp_client.RNS = None
        try:
            result = self.client.initialize()
            self.assertFalse(result)
        finally:
            rmsp_client.RNS = original_rns


class TestRmspClientWrapperParseAnnounce(unittest.TestCase):
    """Test parse_rmsp_announce method."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)
        self.mock_identity = MagicMock()

    def test_parse_valid_announce(self):
        """Test parsing a valid RMSP announce."""
        announce_data = {
            "v": "0.1.0",
            "n": "Test Map Server",
            "c": ["u4pr", "u4pq"],
            "z": [0, 18],
            "f": ["pmtiles", "micro"],
            "l": ["osm", "satellite"],
            "u": 1703980800,
            "s": 5000000,
        }
        app_data = umsgpack.packb(announce_data)

        result = self.client.parse_rmsp_announce(
            self.dest_hash,
            self.mock_identity,
            app_data,
            hops=3,
        )

        self.assertIsNotNone(result)
        self.assertIsInstance(result, rmsp_client.RmspServerInfo)
        self.assertEqual(result.version, "0.1.0")
        self.assertEqual(result.name, "Test Map Server")
        self.assertEqual(result.coverage, ["u4pr", "u4pq"])
        self.assertEqual(result.zoom_range, (0, 18))
        self.assertEqual(result.formats, ["pmtiles", "micro"])
        self.assertEqual(result.layers, ["osm", "satellite"])
        self.assertEqual(result.updated, 1703980800)
        self.assertEqual(result.size, 5000000)
        self.assertEqual(result.hops, 3)

    def test_parse_announce_with_defaults(self):
        """Test parsing announce with missing optional fields."""
        announce_data = {
            "v": "0.1.0",
        }
        app_data = umsgpack.packb(announce_data)

        result = self.client.parse_rmsp_announce(
            self.dest_hash,
            self.mock_identity,
            app_data,
        )

        self.assertIsNotNone(result)
        self.assertEqual(result.version, "0.1.0")
        self.assertEqual(result.name, "Unknown")
        self.assertEqual(result.coverage, [])
        self.assertEqual(result.zoom_range, (0, 15))
        self.assertEqual(result.formats, ["pmtiles"])
        self.assertEqual(result.layers, ["osm"])
        self.assertEqual(result.updated, 0)
        self.assertIsNone(result.size)

    def test_parse_announce_stores_server(self):
        """Test that parsed server is stored in servers dict."""
        announce_data = {"v": "0.1.0", "n": "Stored Server"}
        app_data = umsgpack.packb(announce_data)

        result = self.client.parse_rmsp_announce(
            self.dest_hash,
            self.mock_identity,
            app_data,
        )

        self.assertIn(self.dest_hash, self.client.servers)
        self.assertEqual(self.client.servers[self.dest_hash], result)

    def test_parse_announce_invalid_msgpack(self):
        """Test parsing invalid msgpack data."""
        result = self.client.parse_rmsp_announce(
            self.dest_hash,
            self.mock_identity,
            b"not valid msgpack data",
        )
        self.assertIsNone(result)

    def test_parse_announce_without_umsgpack(self):
        """Test parsing when umsgpack not available."""
        original_umsgpack = rmsp_client.umsgpack
        rmsp_client.umsgpack = None
        try:
            result = self.client.parse_rmsp_announce(
                self.dest_hash,
                self.mock_identity,
                b"data",
            )
            self.assertIsNone(result)
        finally:
            rmsp_client.umsgpack = original_umsgpack


class TestRmspClientWrapperGetServers(unittest.TestCase):
    """Test server retrieval methods."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.mock_identity = MagicMock()

        # Add some test servers
        for i, (name, coverage, hops) in enumerate([
            ("Server A", ["u4pr"], 1),
            ("Server B", ["ezs4"], 3),
            ("Server C", ["u4pr", "u4pq"], 2),
            ("Server D", [], 5),  # Global coverage
        ]):
            dest_hash = bytes([i] * 16)
            server = rmsp_client.RmspServerInfo(
                destination_hash=dest_hash,
                identity=self.mock_identity,
                version="0.1.0",
                name=name,
                coverage=coverage,
                zoom_range=(0, 15),
                formats=["pmtiles"],
                layers=["osm"],
                updated=1703980800,
                hops=hops,
            )
            self.client.servers[dest_hash] = server

    def test_get_servers_returns_all(self):
        """get_servers should return all servers as dicts."""
        result = self.client.get_servers()
        self.assertEqual(len(result), 4)
        self.assertIsInstance(result[0], dict)

    def test_get_servers_for_geohash(self):
        """get_servers_for_geohash should filter by coverage."""
        result = self.client.get_servers_for_geohash("u4pr")
        # Server A covers u4pr, Server C covers u4pr and u4pq, Server D is global
        names = [s["name"] for s in result]
        self.assertIn("Server A", names)
        self.assertIn("Server C", names)
        self.assertIn("Server D", names)
        self.assertNotIn("Server B", names)

    def test_get_servers_for_geohash_no_matches(self):
        """get_servers_for_geohash should return empty for no matches."""
        # Remove global server
        for h, s in list(self.client.servers.items()):
            if s.name == "Server D":
                del self.client.servers[h]

        result = self.client.get_servers_for_geohash("xyz123")
        self.assertEqual(len(result), 0)

    def test_get_nearest_servers(self):
        """get_nearest_servers should sort by hop count."""
        result = self.client.get_nearest_servers(limit=3)
        self.assertEqual(len(result), 3)
        # Should be sorted by hops
        self.assertEqual(result[0]["name"], "Server A")  # 1 hop
        self.assertEqual(result[1]["name"], "Server C")  # 2 hops
        self.assertEqual(result[2]["name"], "Server B")  # 3 hops

    def test_get_nearest_servers_limit(self):
        """get_nearest_servers should respect limit."""
        result = self.client.get_nearest_servers(limit=2)
        self.assertEqual(len(result), 2)

    def test_get_server_by_hash(self):
        """get_server should return specific server by hash."""
        # Get first server's hash
        dest_hash = list(self.client.servers.keys())[0]
        hex_hash = dest_hash.hex()

        result = self.client.get_server(hex_hash)
        self.assertIsNotNone(result)
        self.assertEqual(result["destination_hash"], hex_hash)

    def test_get_server_not_found(self):
        """get_server should return None for unknown hash."""
        result = self.client.get_server("ffff" * 8)
        self.assertIsNone(result)

    def test_get_server_invalid_hex(self):
        """get_server should handle invalid hex gracefully."""
        result = self.client.get_server("not a valid hex")
        self.assertIsNone(result)


class TestRmspClientWrapperQueryServer(unittest.TestCase):
    """Test query_server method."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)

        # Add a test server
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test Server",
            coverage=["u4pr"],
            zoom_range=(0, 15),
            formats=["pmtiles"],
            layers=["osm"],
            updated=1703980800,
        )
        self.client.servers[self.dest_hash] = server

    def test_query_server_not_found(self):
        """Query should return error for unknown server."""
        result = self.client.query_server("ffff" * 8, "u4pr")
        self.assertFalse(result["available"])
        self.assertEqual(result["error_code"], -1)
        self.assertEqual(result["error_message"], "Server not found")

    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_link_failure(self, mock_establish_link):
        """Query should return error if link establishment fails."""
        mock_establish_link.return_value = None

        result = self.client.query_server(self.dest_hash.hex(), "u4pr")
        self.assertFalse(result["available"])
        self.assertEqual(result["error_message"], "Failed to establish link")

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_request_timeout(self, mock_establish_link, mock_request):
        """Query should return error on request timeout."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link
        mock_request.return_value = None  # Timeout

        result = self.client.query_server(self.dest_hash.hex(), "u4pr")
        self.assertFalse(result["available"])
        self.assertEqual(result["error_message"], "Request timed out")
        mock_link.teardown.assert_called_once()

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_success(self, mock_establish_link, mock_request):
        """Test successful query."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link

        response_data = umsgpack.packb({
            "a": True,
            "g": "u4pr",
            "z": [5, 15],
            "s": 2048000,
            "t": 1500,
            "eta": 30,
            "h": bytes.fromhex("abcd1234"),
            "u": 1703980800,
            "ttl": 3600,
        })
        mock_request.return_value = response_data

        result = self.client.query_server(self.dest_hash.hex(), "u4pr")
        self.assertTrue(result["available"])
        self.assertEqual(result["geohash"], "u4pr")
        self.assertEqual(result["zoom_range"], [5, 15])
        self.assertEqual(result["size"], 2048000)
        self.assertEqual(result["tile_count"], 1500)
        mock_link.teardown.assert_called_once()

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_error_response(self, mock_establish_link, mock_request):
        """Test query with error response from server."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link

        response_data = umsgpack.packb({
            "e": 404,
            "m": "Area not available",
        })
        mock_request.return_value = response_data

        result = self.client.query_server(self.dest_hash.hex(), "u4pr")
        self.assertFalse(result["available"])
        self.assertEqual(result["error_code"], 404)
        self.assertEqual(result["error_message"], "Area not available")

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_with_zoom_range(self, mock_establish_link, mock_request):
        """Test query with zoom range parameter."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link
        mock_request.return_value = umsgpack.packb({"a": True, "g": "u4pr"})

        result = self.client.query_server(
            self.dest_hash.hex(),
            "u4pr",
            zoom_range=[5, 10],
        )

        # Verify request was called with zoom range
        call_args = mock_request.call_args
        self.assertIn("z", call_args[0][2])

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_query_server_with_format(self, mock_establish_link, mock_request):
        """Test query with format parameter."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link
        mock_request.return_value = umsgpack.packb({"a": True, "g": "u4pr"})

        result = self.client.query_server(
            self.dest_hash.hex(),
            "u4pr",
            format="micro",
        )

        # Verify request was called with format
        call_args = mock_request.call_args
        self.assertIn("f", call_args[0][2])


class TestRmspClientWrapperFetchTiles(unittest.TestCase):
    """Test fetch_tiles method."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)

        # Add a test server
        server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test Server",
            coverage=["u4pr"],
            zoom_range=(0, 15),
            formats=["pmtiles"],
            layers=["osm"],
            updated=1703980800,
        )
        self.client.servers[self.dest_hash] = server

    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_fetch_tiles_link_failure(self, mock_establish_link):
        """Fetch should return None if link establishment fails."""
        mock_establish_link.return_value = None

        result = self.client.fetch_tiles(
            self.dest_hash.hex(),
            b"public_key_bytes",
            "u4pr",
        )
        self.assertIsNone(result)

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_fetch_tiles_timeout(self, mock_establish_link, mock_request):
        """Fetch should return None on timeout."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link
        mock_request.return_value = None  # Timeout

        result = self.client.fetch_tiles(
            self.dest_hash.hex(),
            b"public_key_bytes",
            "u4pr",
        )
        self.assertIsNone(result)
        mock_link.teardown.assert_called_once()

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_fetch_tiles_success(self, mock_establish_link, mock_request):
        """Test successful tile fetch."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link

        # Simulate tile data (larger than 1000 bytes to pass error check)
        tile_data = b"tile_data_here" * 100
        mock_request.return_value = tile_data

        result = self.client.fetch_tiles(
            self.dest_hash.hex(),
            b"public_key_bytes",
            "u4pr",
        )
        self.assertEqual(result, tile_data)
        mock_link.teardown.assert_called_once()

    @patch.object(rmsp_client.RmspClientWrapper, '_request')
    @patch.object(rmsp_client.RmspClientWrapper, '_establish_link')
    def test_fetch_tiles_error_response(self, mock_establish_link, mock_request):
        """Test fetch with error response (small msgpack data)."""
        mock_link = MagicMock()
        mock_establish_link.return_value = mock_link

        # Small error response (less than 1000 bytes)
        error_response = umsgpack.packb({"e": 404, "m": "Not found"})
        mock_request.return_value = error_response

        result = self.client.fetch_tiles(
            self.dest_hash.hex(),
            b"public_key_bytes",
            "u4pr",
        )
        self.assertIsNone(result)

    @patch('rmsp_client.RNS')
    @patch('rmsp_client.time.sleep')
    def test_fetch_tiles_server_not_in_cache(self, mock_sleep, mock_rns):
        """Test fetch when server not in cache, uses public key."""
        # Remove server from cache
        del self.client.servers[self.dest_hash]

        # Mock RNS.Identity for creating from public key
        mock_identity = MagicMock()
        mock_identity_class = MagicMock(return_value=mock_identity)
        mock_rns.Identity = mock_identity_class

        with patch.object(self.client, '_establish_link') as mock_link_fn, \
             patch.object(self.client, '_request') as mock_request:
            mock_link = MagicMock()
            mock_link_fn.return_value = mock_link
            mock_request.return_value = b"tile_data" * 200

            result = self.client.fetch_tiles(
                self.dest_hash.hex(),
                b"public_key_bytes",
                "u4pr",
                timeout=0.1,  # Short timeout
            )

            # Should have created identity from public key
            mock_identity.load_public_key.assert_called_once_with(b"public_key_bytes")


class TestRmspClientWrapperEstablishLink(unittest.TestCase):
    """Test _establish_link method."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.mock_identity = MagicMock()
        self.dest_hash = bytes.fromhex("deadbeef" * 4)
        self.server = rmsp_client.RmspServerInfo(
            destination_hash=self.dest_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Test",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
        )

    def test_establish_link_no_rns(self):
        """Should return None if RNS not available."""
        original_rns = rmsp_client.RNS
        rmsp_client.RNS = None
        try:
            result = self.client._establish_link(self.server, 5.0)
            self.assertIsNone(result)
        finally:
            rmsp_client.RNS = original_rns

    @patch('rmsp_client.RNS')
    @patch('rmsp_client.time.time')
    @patch('rmsp_client.time.sleep')
    def test_establish_link_path_request_timeout(self, mock_sleep, mock_time, mock_rns):
        """Should return None if path request times out."""
        mock_rns.Transport.has_path.return_value = False
        mock_time.side_effect = [0.0, 1.0, 35.0]  # Simulates timeout

        result = self.client._establish_link(self.server, 30.0)
        self.assertIsNone(result)
        mock_rns.Transport.request_path.assert_called_once()

    @patch('rmsp_client.RNS')
    @patch('rmsp_client.time.time')
    @patch('rmsp_client.time.sleep')
    def test_establish_link_success(self, mock_sleep, mock_time, mock_rns):
        """Test successful link establishment."""
        mock_rns.Transport.has_path.return_value = True

        # Mock destination
        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest
        mock_rns.Destination.OUT = "OUT"
        mock_rns.Destination.SINGLE = "SINGLE"

        # Use explicit constants
        ACTIVE = "ACTIVE"
        CLOSED = "CLOSED"
        mock_rns.Link.ACTIVE = ACTIVE
        mock_rns.Link.CLOSED = CLOSED

        # Mock link that is immediately active
        mock_link = MagicMock()
        mock_link.status = ACTIVE
        mock_rns.Link.return_value = mock_link

        mock_time.return_value = 0.0

        result = self.client._establish_link(self.server, 30.0)
        self.assertEqual(result, mock_link)

    @patch('rmsp_client.RNS')
    @patch('rmsp_client.time.time')
    @patch('rmsp_client.time.sleep')
    def test_establish_link_closed(self, mock_sleep, mock_time, mock_rns):
        """Should return None if link is closed."""
        mock_rns.Transport.has_path.return_value = True

        # Mock destination
        mock_dest = MagicMock()
        mock_rns.Destination.return_value = mock_dest

        # Use explicit constants instead of MagicMock comparisons
        CLOSED = "CLOSED"
        ACTIVE = "ACTIVE"
        mock_rns.Link.CLOSED = CLOSED
        mock_rns.Link.ACTIVE = ACTIVE

        # Mock link that is closed - use the actual constant
        mock_link = MagicMock()
        mock_link.status = CLOSED
        mock_rns.Link.return_value = mock_link

        mock_time.return_value = 0.0

        result = self.client._establish_link(self.server, 30.0)
        self.assertIsNone(result)


class TestRmspClientWrapperServerManagement(unittest.TestCase):
    """Test server management methods."""

    def setUp(self):
        """Set up test fixtures."""
        self.client = rmsp_client.RmspClientWrapper()
        self.mock_identity = MagicMock()

    def test_clear_servers(self):
        """Test clearing all servers."""
        # Add some servers
        for i in range(3):
            dest_hash = bytes([i] * 16)
            server = rmsp_client.RmspServerInfo(
                destination_hash=dest_hash,
                identity=self.mock_identity,
                version="0.1.0",
                name=f"Server {i}",
                coverage=[],
                zoom_range=(0, 15),
                formats=[],
                layers=[],
                updated=0,
            )
            self.client.servers[dest_hash] = server

        self.assertEqual(len(self.client.servers), 3)
        self.client.clear_servers()
        self.assertEqual(len(self.client.servers), 0)

    @patch('rmsp_client.time.time')
    def test_remove_stale_servers(self, mock_time):
        """Test removing stale servers."""
        mock_time.return_value = 5000.0

        # Add servers with different last_seen times
        fresh_hash = bytes([1] * 16)
        stale_hash = bytes([2] * 16)

        fresh_server = rmsp_client.RmspServerInfo(
            destination_hash=fresh_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Fresh Server",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
            last_seen=4500.0,  # 500 seconds ago
        )
        stale_server = rmsp_client.RmspServerInfo(
            destination_hash=stale_hash,
            identity=self.mock_identity,
            version="0.1.0",
            name="Stale Server",
            coverage=[],
            zoom_range=(0, 15),
            formats=[],
            layers=[],
            updated=0,
            last_seen=1000.0,  # 4000 seconds ago
        )

        self.client.servers[fresh_hash] = fresh_server
        self.client.servers[stale_hash] = stale_server

        self.assertEqual(len(self.client.servers), 2)
        self.client.remove_stale_servers(max_age_seconds=3600.0)
        self.assertEqual(len(self.client.servers), 1)
        self.assertIn(fresh_hash, self.client.servers)
        self.assertNotIn(stale_hash, self.client.servers)


class TestUnpackTiles(unittest.TestCase):
    """Test the unpack_tiles utility function."""

    def test_unpack_empty_data(self):
        """Should return empty list for data less than 4 bytes."""
        result = rmsp_client.unpack_tiles(b"")
        self.assertEqual(result, [])

        result = rmsp_client.unpack_tiles(b"abc")
        self.assertEqual(result, [])

    def test_unpack_zero_tiles(self):
        """Should return empty list for zero tile count."""
        # Header with count=0
        data = struct.pack(">I", 0)
        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(result, [])

    def test_unpack_single_tile(self):
        """Test unpacking a single tile."""
        tile_content = b"tile_data_here"
        tile_size = len(tile_content)

        # Build tile data: count + (z, x, y, size) + tile_data
        data = struct.pack(">I", 1)  # count = 1
        data += struct.pack(">BIII", 5, 100, 200, tile_size)  # z=5, x=100, y=200
        data += tile_content

        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(len(result), 1)
        z, x, y, tile_data = result[0]
        self.assertEqual(z, 5)
        self.assertEqual(x, 100)
        self.assertEqual(y, 200)
        self.assertEqual(tile_data, tile_content)

    def test_unpack_multiple_tiles(self):
        """Test unpacking multiple tiles."""
        tiles = [
            (0, 0, 0, b"tile0"),
            (5, 10, 20, b"tile5"),
            (10, 512, 1024, b"tile10_longer_data"),
        ]

        # Build data
        data = struct.pack(">I", len(tiles))
        for z, x, y, content in tiles:
            data += struct.pack(">BIII", z, x, y, len(content))
            data += content

        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(len(result), 3)

        for i, (z, x, y, content) in enumerate(result):
            self.assertEqual(z, tiles[i][0])
            self.assertEqual(x, tiles[i][1])
            self.assertEqual(y, tiles[i][2])
            self.assertEqual(content, tiles[i][3])

    def test_unpack_truncated_header(self):
        """Should handle truncated tile header."""
        # Count says 1 tile but header is incomplete
        data = struct.pack(">I", 1)  # count = 1
        data += struct.pack(">BII", 5, 100, 200)  # Missing size field

        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(result, [])

    def test_unpack_truncated_data(self):
        """Should handle truncated tile data."""
        # Header says tile is 100 bytes but only 10 provided
        data = struct.pack(">I", 1)  # count = 1
        data += struct.pack(">BIII", 5, 100, 200, 100)  # size=100
        data += b"short"  # Only 5 bytes

        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(result, [])

    def test_unpack_partial_tiles(self):
        """Should unpack valid tiles even if later tiles are truncated."""
        # Two tiles, second one truncated
        data = struct.pack(">I", 2)  # count = 2

        # First tile - complete
        tile1 = b"tile1_data"
        data += struct.pack(">BIII", 5, 10, 20, len(tile1))
        data += tile1

        # Second tile - truncated
        data += struct.pack(">BIII", 6, 30, 40, 100)  # size=100
        data += b"short"  # Only 5 bytes

        result = rmsp_client.unpack_tiles(data)
        # Should have the first tile
        self.assertEqual(len(result), 1)
        z, x, y, content = result[0]
        self.assertEqual(z, 5)
        self.assertEqual(content, tile1)

    def test_excessive_tile_count_rejected(self):
        """Tile count exceeding 100,000 should be rejected."""
        # Create data with tile_count = 100,001
        data = struct.pack(">I", 100001)
        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(len(result), 0)

    def test_max_tile_count_accepted(self):
        """Tile count of exactly 100,000 should be accepted (if data available)."""
        # Create data with tile_count = 100,000 but no actual tiles
        data = struct.pack(">I", 100000)
        result = rmsp_client.unpack_tiles(data)
        # Should return empty list (no tiles, but count is valid)
        self.assertEqual(len(result), 0)

    def test_excessive_tile_size_rejected(self):
        """Tile size exceeding 1MB should be rejected."""
        # Create data with one tile having size > 1MB
        data = struct.pack(">I", 1)  # tile_count = 1
        data += struct.pack(">BIII", 5, 10, 20, 1000001)  # size > 1MB
        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(len(result), 0)

    def test_max_tile_size_accepted(self):
        """Tile size of exactly 1MB should be accepted."""
        tile_data = b"x" * 1000000  # Exactly 1MB
        data = struct.pack(">I", 1)  # tile_count = 1
        data += struct.pack(">BIII", 5, 10, 20, 1000000)
        data += tile_data
        result = rmsp_client.unpack_tiles(data)
        self.assertEqual(len(result), 1)
        z, x, y, content = result[0]
        self.assertEqual(len(content), 1000000)


class TestGetRmspClient(unittest.TestCase):
    """Test the singleton get_rmsp_client function."""

    def setUp(self):
        """Reset singleton before each test."""
        rmsp_client._rmsp_client = None

    def tearDown(self):
        """Clean up singleton after each test."""
        rmsp_client._rmsp_client = None

    def test_get_rmsp_client_creates_instance(self):
        """First call should create a new instance."""
        result = rmsp_client.get_rmsp_client()
        self.assertIsInstance(result, rmsp_client.RmspClientWrapper)

    def test_get_rmsp_client_returns_same_instance(self):
        """Subsequent calls should return the same instance."""
        client1 = rmsp_client.get_rmsp_client()
        client2 = rmsp_client.get_rmsp_client()
        self.assertIs(client1, client2)

    def test_get_rmsp_client_state_persists(self):
        """State should persist between calls."""
        client1 = rmsp_client.get_rmsp_client()
        client1.servers[b"test"] = "value"

        client2 = rmsp_client.get_rmsp_client()
        self.assertIn(b"test", client2.servers)


class TestEnsureImports(unittest.TestCase):
    """Test the _ensure_imports function."""

    def test_ensure_imports_sets_globals(self):
        """_ensure_imports should set module globals."""
        # This is already tested implicitly, but verify it doesn't crash
        rmsp_client._ensure_imports()
        # umsgpack and RNS should be set (or remain set)
        # We just verify no exception is raised


if __name__ == '__main__':
    unittest.main()
