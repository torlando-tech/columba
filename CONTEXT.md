# Columba Domain Glossary

Names for the concepts this codebase is built around. When code, docs, or
architecture discussions need one of these concepts, use the term defined
here — don't invent a synonym.

## Message delivery

### DeliveryState

The lifecycle of an outbound LXMF message, from optimistic creation through
terminal success or failure. A closed vocabulary, modeled as the sealed
interface `network.columba.app.rns.api.model.DeliveryState`:

| State | Meaning | Rendered |
|---|---|---|
| `Pending` | Handed to the router, awaiting an outcome | ○ |
| `Sent` | Transmitted, no proof yet (legacy initial state, Room column default) | ✓ |
| `Delivered` | Delivery proof received from the recipient | ✓✓ |
| `Propagated` | Stored on the configured propagation node; recipient will fetch it | ✓ |
| `RetryingViaPropagation` | Direct delivery failed; rebuilt as PROPAGATED and re-routed via relay | ✓ |
| `Failed` | Delivery failed with no retry remaining | ! |

Rules that travel with the concept:

- **Terminal success never degrades** (Issue #257): `Sent`, `Propagated`,
  and `Delivered` never transition to `Failed`; `Delivered` never
  transitions to anything. LXMF can fire spurious failure callbacks after
  a success confirmation — consumers must guard, and the guard lives with
  the type (`isTerminalSuccess`).
- **The string form is a persistence contract.** Room stores
  `encode()`-produced strings (`"pending"`, `"sent"`, …) in
  `MessageEntity.status`; the maestro logcat harness greps their uppercased
  forms. Old databases are an open string set, so `decode()` is nullable —
  null means "unrecognized legacy value", rendered as the neutral
  presentation.

### DeliveryMethod

How a send is routed — a different axis than DeliveryState, modeled as
`network.columba.app.rns.api.model.DeliveryMethod`:

- `OPPORTUNISTIC` — single packet, no link required; small messages.
- `DIRECT` — link-based delivery with retries; supports large messages.
- `PROPAGATED` — delivered via a relay (propagation node) for offline
  recipients.

**Trap:** the word "propagated" appears in both vocabularies. As a *method*
it means "route via relay"; as a *state* it means "the relay accepted it".
Success for a PROPAGATED-method send is the `Propagated` state (✓), not
`Delivered` (✓✓) — the relay holding a message is a weaker promise than a
recipient acknowledging it.

### Propagation node

A relay in the Reticulum mesh that stores LXMF messages for recipients who
are offline, until they fetch them. The configured outbound propagation node
is a precondition for the `RetryingViaPropagation` fallback (the Sideband
retry pattern: try direct, on failure rebuild as PROPAGATED and re-route).

### DeliveryRetryPolicy

The shared *decision* half of the Sideband retry pattern
(`network.columba.app.rns.api.util.DeliveryRetryPolicy`): retry via
propagation iff the sender opted in, the message's desired method isn't
already PROPAGATED (that flip is what terminates the loop after one retry),
and a propagation node is configured. Both backend adapters consult this one
predicate; only the *mechanism* (rebuilding the live LXMessage and
re-submitting it) stays flavor-local, because it manipulates flavor-owned
router/message objects.

## Backends

### RnsBackend seam

The interface (`:rns-api`) between the app and the Reticulum protocol stack.
Two adapters implement it: the Python flavor (`:rns-backend-py`, upstream
RNS/LXMF via Chaquopy) and the native flavor (`:rns-backend-kt`,
reticulum-kt). Both emit `DeliveryStatusUpdate(messageHash, state, timestamp)`
carrying a `DeliveryState` — the states above are the seam's contract, not
per-backend inventions.
