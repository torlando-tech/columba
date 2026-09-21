"""
The Discovered Interfaces screen on the Python flavor used to take >10s to
load once a node had heard hundreds of interfaces. The bottleneck was not the
RNS-side file reads (8ms for 552 files) but the Kotlin side reading ~28 keys
off each per-interface dict through Chaquopy (~15,000 JNI crossings).

event_bridge.serialize_discovered_interfaces() collapses the whole snapshot
into a single JSON string PYTHON-side, so the Kotlin side makes one callAttr
+ one string crossing regardless of the interface count. This test locks the
contract the Kotlin side relies on:

  - the JSON array parses
  - `config_entry` is dropped (it is a multi-line config blob no UI reads)
  - `value` -> `stamp_value`, `sf` -> `spreading_factor`,
    `cr` -> `coding_rate` remaps land in the shape
    DiscoveredInterface.parseFromJson expects (and the original keys are
    gone, so the field is not carried twice)
  - a None handler and a handler that raises both yield "[]" (an empty list
    is a valid screen state; an exception would surface as a load failure)
  - bytes values are hex-encoded (the _jsonable contract)
"""
import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


def _noop(*args, **kwargs):
    pass


class FakeDiscoveryHandler:
    def __init__(self, infos=None, raises=None):
        self._infos = infos or []
        self._raises = raises

    def list_discovered_interfaces(self):
        if self._raises is not None:
            raise self._raises
        return self._infos


def _rnode_info():
    """A realistic RNodeInterface dict as stored by the pinned RNS."""
    return {
        "type": "RNodeInterface",
        "transport": True,
        "name": "test-rnode",
        "received": 1720000000.0,
        "stamp": b"\x01\x02\x03",
        "value": 42,  # -> stamp_value
        "transport_id": "aa" * 20,
        "network_id": "bb" * 20,
        "hops": 1,
        "latitude": 37.7,
        "longitude": -122.4,
        "height": 50.0,
        "frequency": 915000000,
        "bandwidth": 125000,
        "sf": 9,  # -> spreading_factor
        "cr": 5,  # -> coding_rate
        "config_entry": "[[test-rnode]]\n  type = RNodeInterface\n  enabled = yes\n",
        "status": "available",
        "status_code": 1000,
        "discovered": 1719999000.0,
        "heard_count": 7,
        "last_heard": 1720000000.0,
        "discovery_hash": "cc" * 32,
    }


def _tcp_info():
    return {
        "type": "TCPServerInterface",
        "transport": True,
        "name": "test-tcp",
        "received": 1720000000.0,
        "stamp": b"\x04\x05",
        "value": 7,
        "transport_id": "dd" * 20,
        "network_id": "ee" * 20,
        "hops": 1,
        "latitude": None,
        "longitude": None,
        "height": None,
        "reachable_on": "10.0.0.5",
        "port": 4242,
        "ifac_netname": "mynet",
        "config_entry": "[[test-tcp]]\n  type = TCPServerInterface\n",
        "status": "unknown",
        "status_code": 100,
        "discovered": 1719999000.0,
        "heard_count": 1,
        "last_heard": 1720000000.0,
        "discovery_hash": "ff" * 32,
    }


class SerializeDiscoveredInterfacesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # event_bridge imports RNS and LXMF at module top; stub them like the
        # other event_bridge tests.
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(rns, "Destination", types.SimpleNamespace(
            hash_from_name_and_identity=lambda aspect, identity: b"destination"
        ))
        setattr(rns, "Transport", types.SimpleNamespace(
            PATHFINDER_M=128,
            hops_to=lambda destination_hash: 1,
            path_table={},
            register_announce_handler=_noop,
        ))
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(lxmf, "LXStamper", types.SimpleNamespace(
            set_external_generator=lambda *args: None,
        ))
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_serialize_discovered_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_none_handler_returns_empty_array(self):
        self.assertEqual("[]", self.module.serialize_discovered_interfaces(None))

    def test_raising_handler_returns_empty_array(self):
        handler = FakeDiscoveryHandler(raises=OSError("disk error"))
        self.assertEqual("[]", self.module.serialize_discovered_interfaces(handler))

    def test_empty_discovery_returns_empty_array(self):
        handler = FakeDiscoveryHandler(infos=[])
        self.assertEqual("[]", self.module.serialize_discovered_interfaces(handler))

    def test_json_parses_and_preserves_count(self):
        handler = FakeDiscoveryHandler(infos=[_rnode_info(), _tcp_info()])
        result = self.module.serialize_discovered_interfaces(handler)
        parsed = json.loads(result)
        self.assertEqual(2, len(parsed))

    def test_config_entry_is_dropped(self):
        handler = FakeDiscoveryHandler(infos=[_rnode_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        self.assertNotIn("config_entry", parsed[0])

    def test_value_remapped_to_stamp_value(self):
        handler = FakeDiscoveryHandler(infos=[_rnode_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        self.assertEqual(42, parsed[0]["stamp_value"])
        self.assertNotIn("value", parsed[0])

    def test_sf_and_cr_remapped_for_rnode(self):
        # The original latent bug: the Kotlin reader looked for
        # spreading_factor / coding_rate, but RNS stores sf / cr - so the LoRa
        # params were always null. The serializer must land them in the parser
        # keys and drop the originals.
        handler = FakeDiscoveryHandler(infos=[_rnode_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        self.assertEqual(9, parsed[0]["spreading_factor"])
        self.assertEqual(5, parsed[0]["coding_rate"])
        self.assertNotIn("sf", parsed[0])
        self.assertNotIn("cr", parsed[0])

    def test_tcp_fields_pass_through_unchanged(self):
        handler = FakeDiscoveryHandler(infos=[_tcp_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        item = parsed[0]
        self.assertEqual("10.0.0.5", item["reachable_on"])
        self.assertEqual(4242, item["port"])
        self.assertEqual("mynet", item["ifac_netname"])
        # TCP has no sf/cr/value-as-stamp confusion: stamp_value from value.
        self.assertEqual(7, item["stamp_value"])

    def test_bytes_values_are_hex_encoded(self):
        # `stamp` is raw bytes; _jsonable must hex-encode it so the JSON stays
        # string-safe across the JNI boundary.
        handler = FakeDiscoveryHandler(infos=[_rnode_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        self.assertEqual("010203", parsed[0]["stamp"])

    def test_none_fields_stay_null_in_json(self):
        handler = FakeDiscoveryHandler(infos=[_tcp_info()])
        parsed = json.loads(self.module.serialize_discovered_interfaces(handler))
        self.assertIsNone(parsed[0]["latitude"])
        self.assertIsNone(parsed[0]["height"])


if __name__ == "__main__":
    unittest.main()
