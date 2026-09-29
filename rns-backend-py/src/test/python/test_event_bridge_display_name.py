import importlib.util
import sys
import types
import unittest
from pathlib import Path


EVENT_BRIDGE_PATH = Path(__file__).resolve().parents[2] / "main/python/event_bridge.py"


class FakeDestination:
    """Stands in for the local LXMF delivery `RNS.Destination`.

    `LXMRouter.get_announce_app_data` reads `display_name` off this object
    live when it builds announce app_data, so the attribute is the single
    source of truth the fix must update.
    """

    def __init__(self, display_name):
        self.display_name = display_name
        self.hash = b"destination"


class FakeRouter:
    def __init__(self, destination):
        self.delivery_destinations = {destination.hash: destination}


class DisplayNameBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rns = types.ModuleType("RNS")
        setattr(rns, "LOG_DEBUG", 1)
        setattr(rns, "LOG_ERROR", 3)
        setattr(rns, "LOG_WARNING", 2)
        setattr(rns, "log", lambda *args, **kwargs: None)
        setattr(
            rns,
            "Destination",
            types.SimpleNamespace(
                hash_from_name_and_identity=lambda aspect, identity: b"destination"
                if aspect == "lxmf.delivery"
                else b"other"
            ),
        )
        setattr(
            rns,
            "Transport",
            types.SimpleNamespace(
                PATHFINDER_M=128,
                hops_to=lambda destination_hash: 1,
                path_table={},
            ),
        )
        sys.modules["RNS"] = rns

        lxmf = types.ModuleType("LXMF")
        setattr(
            lxmf,
            "LXStamper",
            types.SimpleNamespace(set_external_generator=lambda *args: None),
        )
        sys.modules["LXMF"] = lxmf

        spec = importlib.util.spec_from_file_location(
            "event_bridge_display_name_test", EVENT_BRIDGE_PATH
        )
        assert spec is not None and spec.loader is not None
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def setUp(self):
        # Reset the module-level router to a clean delivery destination each
        # test so state never leaks between cases.
        self.destination = FakeDestination(display_name="Old Name")
        self.router = FakeRouter(self.destination)
        self.module._lxmf_router = self.router

    def tearDown(self):
        self.module._lxmf_router = None

    def test_set_display_name_updates_delivery_destination(self):
        # The delivery destination currently carries the name frozen at
        # registration (backend start). Applying a fresh name must update the
        # attribute LXMF's get_announce_app_data reads, so the next announce
        # carries it.
        self.assertEqual("Old Name", self.destination.display_name)

        result = self.module.set_display_name("New Name")

        self.assertTrue(result)
        self.assertEqual("New Name", self.destination.display_name)

    def test_set_display_name_applies_even_when_name_was_empty_at_start(self):
        # `config.displayName ?: ""` registers an empty name when the user had
        # none. An empty string is still a set attribute, so a later rename
        # must overwrite it the same way.
        self.destination.display_name = ""

        result = self.module.set_display_name("Fresh Name")

        self.assertTrue(result)
        self.assertEqual("Fresh Name", self.destination.display_name)

    def test_set_display_name_noop_without_router(self):
        # Early-init race: register_callbacks hasn't wired the module router
        # yet. Must fail closed (no exception, False returned) rather than
        # crash the announce path.
        self.module._lxmf_router = None

        result = self.module.set_display_name("Whatever")

        self.assertFalse(result)

    def test_set_display_name_noop_without_delivery_destination(self):
        # Router exists but has no registered delivery destination (e.g.
        # registration failed). Must fail closed.
        self.router.delivery_destinations = {}

        result = self.module.set_display_name("Whatever", self.router)

        self.assertFalse(result)

    def test_set_display_name_updates_passed_router_destination(self):
        # The announce path passes the router it is about to use. The name
        # must land on that router's delivery destination, not the module's.
        announce_router = self.router

        result = self.module.set_display_name("New Name", announce_router)

        self.assertTrue(result)
        self.assertEqual("New Name", self.destination.display_name)

    def test_set_display_name_uses_passed_router_not_stale_module_router(self):
        # The module-level router can lag the live router (start->wire window
        # or in-process restart). When the announce path hands us the router it
        # is about to use, we must update THAT destination and leave the stale
        # module router's destination untouched.
        stale_destination = FakeDestination(display_name="Stale Name")
        self.module._lxmf_router = FakeRouter(stale_destination)
        fresh_router = FakeRouter(self.destination)

        result = self.module.set_display_name("Fresh Name", fresh_router)

        self.assertTrue(result)
        self.assertEqual("Fresh Name", self.destination.display_name)
        self.assertEqual("Stale Name", stale_destination.display_name)

    def test_set_display_name_passed_router_without_destination_fails_closed(self):
        # A router is passed but it has no registered delivery destination.
        # Must fail closed (False), and must not fall back to the module
        # router's destination.
        stale_destination = FakeDestination(display_name="Stale Name")
        self.module._lxmf_router = FakeRouter(stale_destination)
        empty_router = FakeRouter(FakeDestination("x"))
        empty_router.delivery_destinations = {}

        result = self.module.set_display_name("New Name", empty_router)

        self.assertFalse(result)
        self.assertEqual("Stale Name", stale_destination.display_name)


if __name__ == "__main__":
    unittest.main()
