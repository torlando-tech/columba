# Port deviations

Columba is a port of the LXMF/Reticulum stack from python (`~/repos/Reticulum`,
`~/repos/LXMF`) to Kotlin / Android. Per `feedback-port-must-match-reference`, the kt
port repos (`reticulum-kt`, `LXMF-kt`) mirror the python source and only diverge with
documented justification. This file lives in the Columba app itself and tracks
**Android-app-only divergences** — features that exist in Columba but have no python
upstream equivalent and aren't expected to back-port. The kt/swift port libraries each
maintain their own `port-deviations.md`.

---

## `InterfaceConfig.networkRestriction` (Columba-only)

Each interface configuration carries a `networkRestriction: NetworkRestriction = ANY`
field with values `ANY | WIFI_ONLY | CELLULAR_ONLY`. The python reference
(`RNS/Interfaces/`) has no equivalent — `AutoInterface.carrier_changed` is internal-only
and not user-controllable, and no other interface type exposes a transport filter.

The field is enforced by `InterfaceTransportFilter.filterByTransport` against the
device's current `NetworkCapabilities` (Wi-Fi/Ethernet → `WIFI_LIKE`, cellular →
`CELLULAR`, unsupported live default route → `UNKNOWN`, none → `NONE`). On transport
transitions, `InterfaceTransportObserver` re-applies the filter and feeds the resulting subset into
`ReticulumProtocol.reloadInterfaces`. The filter ignores the field for non-IP
transports (`AndroidBLE` and `RNode` with `connectionMode != "tcp"`) — those don't
ride on the IP carrier so the restriction is meaningless for them.

**`AutoInterface` defaults to `WIFI_ONLY`** (not `ANY`). UDP multicast does not work
over mobile carriers, so an AutoInterface enabled on cellular just generates noise
without producing peers. All other types default to `ANY`.

**Justification.** This is a mobile-specific concern (battery + bandwidth on cellular)
that's irrelevant to the desktop python use case. Sideband (`~/repos/Sideband/sbapp/`)
doesn't have an equivalent either. Adding it here is a deliberate Columba divergence,
not a port gap.

**ETHERNET bucketing.** `TRANSPORT_ETHERNET` is treated as `WIFI_LIKE` so USB tethering
to a PC and dock setups behave like the user expects (a wired LAN-only TCP transport
should reach a LAN node over Ethernet, not get filtered out as "not Wi-Fi"). A
VPN-only or otherwise unsupported live default network is classified as `UNKNOWN` so
unrestricted IP interfaces stay up while Wi-Fi-only and cellular-only interfaces remain
safely filtered out. If Android exposes a deterministic underlying transport on the same
default-network capabilities, that underlying transport wins. `NONE` is reserved for the
absence of a live default route.

---

## Micron `` `T `` timestamp construct

`MicronParser` understands `` `T<unix-seconds>`T `` and
`` `T<unix-seconds>|<strftime-format>`T ``, which render an instant in the reader's own
timezone. Python's `MicronParser.make_output` has no such construct — it consumes the
marker and renders the payload as text — so a reader on the reference client sees raw
seconds where Columba shows a local time.

The construct is defined by the gonomadnet port, and its format language is POSIX strftime:
the conversions Python 3's `time.strftime` accepts, so a page renders identically on every
client that supports it. A page that does not use `` `T `` parses exactly as before.

One divergence is accepted: an instant outside `java.time`'s calendar renders as its payload
rather than as a year beyond it. `%Z` is read from Java's `z` formatter, since
`ZoneId.getDisplayName` returns CLDR metazone titles (`ET` where strftime says `EDT`).
