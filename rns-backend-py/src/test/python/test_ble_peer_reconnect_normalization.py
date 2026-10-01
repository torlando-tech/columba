"""Regression test: the pinned ble-reticulum normalizes peer addresses so a
fixed-MAC peer reconnecting via the other BLE mode is not read as MAC rotation.

Companion to the pin bump that moves ble-reticulum to the duplicate-identity
address-normalization fix (torlando-tech/ble-reticulum#48).

The same physical peer can be represented by more than one address string
depending on which code path saw it:

  * peripheral (GATT) callbacks carry the BlueZ D-Bus device-path form with a
    "dev:" prefix            -> "dev:B8:27:EB:43:04:BC"
  * central (scan/connect) paths carry the bare MAC -> "B8:27:EB:43:04:BC"

``BLEInterface.identity_to_address`` / ``address_to_identity`` are written by
whichever path stores first, so a later identity comparison against the other
path's form would see ``"dev:AA:BB" != "AA:BB"`` and wrongly conclude a
different MAC (a false Android MAC rotation) for a fixed-MAC peer, dropping
the reconnection. ``_normalize_address`` strips the ``dev:`` prefix and
upper-cases so the same physical MAC compares equal regardless of which form
it arrived in, while genuinely different MACs still differ.

This test drives the REAL pinned ``BLEInterface._normalize_address`` (not a
mock) against the wheel ``run_python_tests.py`` installs from build.gradle.kts.
The method's only dependency is the ``address`` argument, so it is called
unbound with a throwaway ``self`` - no full interface (driver, handshake,
advertising) construction is required. It therefore fails against a future
pin that regresses the normalization, even though the change lives in the
ble-reticulum wheel, and is contract-agnostic: it names no RNS version and no
specific comparison site, so it stays valid across RNS upgrades.

Skip semantics mirror ``test_ble_peer_ifac_size.py``: the class is skipped only
when the real pinned RNS is not importable (so the stub-based unit suite keeps
running anywhere). When real RNS IS present - i.e. this is the
pinned-dependency runner, which always installs the pinned ble-reticulum wheel
- a ``BLEInterface`` import failure raises in ``setUpClass`` and errors the
tests rather than skipping, so a pin that installs but breaks the import is
caught loudly instead of passing silently.
"""

import unittest


def _real_rns_available():
    """True when the real (non-stub) RNS package is importable.

    Matches ``test_ble_peer_ifac_size.py`` exactly: a stubbed RNS (used by the
    unit tests) has no ``_version`` submodule, so its presence is the reliable
    "real RNS" signal. The class is skipped on this alone, for portability
    with the stub-based unit suite.

    NOTE: the ``ble_reticulum`` import is deliberately NOT part of this gate.
    It happens in ``setUpClass`` so that, when the real RNS is present (i.e.
    this is the pinned-dependency runner, which always installs the pinned
    ble-reticulum wheel), a ``BLEInterface`` that can no longer be imported
    RAISES and errors the tests instead of silently skipping. A future pin
    that installs but breaks the import is exactly the regression this test
    exists to catch, so it must fail loudly, not pass.
    """
    try:
        import RNS  # noqa: F401
        import RNS._version  # noqa: F401  (a stubbed RNS has no _version)
        return True
    except Exception:
        return False


@unittest.skipUnless(
    _real_rns_available(),
    "real pinned RNS not importable in this environment",
)
class BlePeerReconnectNormalizationTests(unittest.TestCase):
    """The pinned BLEInterface must normalize peer addresses for identity
    mapping, so a fixed-MAC peer reconnecting via the other BLE mode is not
    misread as an Android MAC rotation."""

    _MAC = "B8:27:EB:43:04:BC"
    _OTHER_MAC = "B8:27:EB:BD:46:87"

    @classmethod
    def setUpClass(cls):
        # Import the REAL pinned BLEInterface from the wheel installed by
        # run_python_tests.py. Kept out of the module import so a broken pin
        # errors the tests (loud) rather than failing at collection time.
        from ble_reticulum.BLEInterface import BLEInterface

        cls._BLEInterface = BLEInterface

    def _normalize(self, address):
        # ``_normalize_address`` depends only on ``address`` (no self state),
        # so call it unbound with a throwaway self - no full interface (driver,
        # handshake, advertising) construction is needed.
        return self._BLEInterface._normalize_address(object(), address)

    def test_peripheral_dev_prefix_form_equals_central_bare_form(self):
        """The headline #48 invariant: the same MAC in peripheral (dev:) form
        and central (bare) form normalize to the same string, so a fixed-MAC
        peer seen first via one path and reconnecting via the other compares
        equal and is not dropped as a false MAC rotation."""
        peripheral = "dev:" + self._MAC
        central = self._MAC
        self.assertEqual(self._normalize(peripheral), self._normalize(central))
        self.assertEqual(self._normalize(central), self._normalize(peripheral))

    def test_normalization_is_case_insensitive_and_trimmed(self):
        """Upper-casing + trimming make the comparison robust to the mixed
        casing/whitespace the two code paths can carry."""
        self.assertEqual(self._normalize(self._MAC.lower()), self._normalize(self._MAC))
        self.assertEqual(self._normalize("dev:" + self._MAC.lower()), self._normalize(self._MAC.upper()))

    def test_distinct_macs_still_differ_after_normalization(self):
        """Normalization must collapse form, not MAC: two different physical
        peers must still normalize to distinct strings, or a genuine rotation
        (or a different peer) would be masked as the same one."""
        self.assertNotEqual(self._normalize(self._MAC), self._normalize(self._OTHER_MAC))
        self.assertNotEqual(
            self._normalize("dev:" + self._MAC), self._normalize(self._OTHER_MAC)
        )

    def test_empty_address_normalizes_to_empty(self):
        """An empty/None address normalizes to the empty string (the guard the
        wheel's comparison relies on), not an error or a non-empty value."""
        self.assertEqual(self._normalize(""), "")
        self.assertEqual(self._normalize(None), "")

    def test_reconnect_is_not_read_as_rotation(self):
        """End-to-end form of the fix: simulate a peer first registered via the
        peripheral path, then reconnecting via the central path. The identity
        comparison the wheel performs must see the same normalized address, so
        the reconnect is treated as the same peer, not a new/rotated MAC.

        This mirrors the wheel's reconnect comparison
        (``_normalize_address(existing) != _normalize_address(incoming)``)
        using the real pinned normalization.
        """
        # First seen via the peripheral (GATT) path.
        existing = "dev:" + self._MAC
        # Reconnects via the central (scan/connect) path.
        incoming = self._MAC
        same_peer = self._normalize(existing) == self._normalize(incoming)
        self.assertTrue(
            same_peer,
            "a fixed-MAC peer reconnecting via the other BLE mode must not "
            "look like a MAC rotation",
        )


if __name__ == "__main__":
    unittest.main()
