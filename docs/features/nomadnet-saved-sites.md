# Feature: Saved NomadNet Sites and Home Screen Shortcuts

## Overview

Two ways to get back to a frequently visited NomadNet page without retracing
the node directory: **saved sites** (a bookmark list on the NomadNet tab) and
**home screen shortcuts** (a launcher icon that opens the page directly).

Before this feature, the shortest path to a NomadNet page was: open the app →
NomadNet tab → find the node → open the site → navigate to the page. Pinning a
page to the launcher reduces that to one tap from the home screen.

## Problem Statement

Requested in issue #1080: shortcuts for specific NomadNet pages on the phone's
home screen, plus bookmarks as a lighter-weight alternative. Contact starring and
pinning already covered "a node I visit often", but not "a page inside a node",
and not "open it from the launcher".

## User-facing behaviour

### Saving a page

- The NomadNet browser toolbar has a bookmark icon: **Save page** when the
  displayed page is not saved, **Remove saved page** when it is.
- Tapping it toggles the saved state. The star reflects the page currently
  displayed, including after a tab switch (the session restores the page *and*
  the request that produced it).

### The saved sites list

The NomadNet tab home (`NomadNetHomeScreen`) lists saved sites between the
Continue row and the node directory (`Saved sites`, with a count):

- Tap a row to open the page.
- Long-press or the row's overflow menu offers **Open**, **Add to home screen**,
  and **Remove**. Removing shows a snackbar with **Undo**, which restores the
  entry at its original position.

### Home screen shortcut

The browser overflow menu offers **Add to home screen** for the displayed page.
That publishes a dynamic shortcut via `ShortcutManagerCompat.requestPinShortcut`,
so the launcher shows its own confirmation prompt.

- The shortcut icon is the node's identicon (same colors/grid as the in-app
  identicon), rendered at 192 px and downscaled by the launcher.
- Short label is the page's own heading when it has one, else its last meaningful
  path segment (`/page/forum/thread.mu` → `thread`; a bare `index` falls back to
  its parent segment). The long label adds the node hash prefix and bare path.
- A launcher that does not support pin requests (or a device policy that
  forbids them) gets a snackbar — `Your launcher does not support home screen
  shortcuts` — instead of a silent no-op.
- Re-pinning the same page updates the existing shortcut rather than adding a
  duplicate: the shortcut id is a SHA-256 digest of node + full path + request.

## Design notes

### Page identity

A page's identity is `nodeHash` + `persistPath` (+ request digest):

- `persistPath` is the full path **including the backtick field block** (for
  example `/page/forum/thread.mu\`category\``). The field block names the
  submitted variables, so reopening a saved var-bearing page re-submits the same
  fields instead of fetching the bare path (which many nodes reject).
- For a page reached by submitting a form (a search, a filtered index), the
  submitted values are *not* in the path — only their names are. `requestData`
  (the JSON request) is therefore part of the identity and is replayed on
  reopen. Two searches of one path with different values are two saved sites,
  and removing one never removes the other.
- Identity is a plain string for the stored key and a digest only for the
  launcher shortcut id (which must stay short and id-free of path separators).
  Plain pages keep the pre-request identity, so storage written before request
  replay existed still matches.

### Shortcut launch path

The shortcut intent is `ACTION_VIEW` on `nomadnetwork://<nodeHash>:<path>` with an
explicit component of `MainActivity` and `FLAG_ACTIVITY_NEW_TASK`; `requestData`
travels as the `nomadnet_request_data` extra because the hand-parsed deep-link
URI has no room for an encoded JSON payload.

`MainActivity` is `singleTask`, so a shortcut tap either cold-starts the app or
arrives at `onNewIntent`. Both run `MainActivityIntentHandler`, which produces
`PendingNavigation.NomadNetBrowser`; the nav layer switches to the NomadNet tab
and loads the page. No shortcut-specific intent handling exists anywhere else —
a shortcut is exactly the same deep link an in-app Share produces.

### Storage

Saved sites are one JSON string in DataStore
(`SettingsRepository.saveNomadNetSavedSite` / `removeNomadNetSavedSite` /
`restoreNomadNetSavedSite`). Every mutation is a read-modify-write inside a
single `edit` block, so a concurrent save and remove cannot drop an entry.
Malformed storage decodes to an empty list instead of crashing.

Both the browser and the NomadNet tab home observe the same list, so the star in
the toolbar and the list update together without a round trip through the store.

## Files

| Path | Role |
| --- | --- |
| `app/.../nomadnet/NomadNetSavedSites.kt` | `NomadNetSavedSite` model, storage key rules, JSON codec, add/remove transitions (no Android types) |
| `app/.../nomadnet/NomadNetShortcuts.kt` | `NomadNetShortcutSpec`, shortcut id / labels / deep link (platform-free) |
| `app/.../nomadnet/NomadNetShortcutPinner.kt` | Builds `ShortcutInfoCompat` + identicon, requests the pin |
| `app/.../repository/SettingsRepository.kt` | DataStore persistence of the saved list |
| `app/.../viewmodel/NomadNetSitesViewModel.kt` | Saved list state shared by tab home and browser star |
| `app/.../viewmodel/NomadNetBrowserViewModel.kt` | Request-aware page identity; session + history carry `requestData` |
| `app/.../ui/screens/NomadNetHomeScreen.kt` | Saved sites section, row menu, undo snackbar |
| `app/.../ui/screens/NomadNetBrowserScreen.kt` | Toolbar bookmark toggle, "Add to home screen" menu item |
| `app/.../MainActivityIntentHandler.kt` | `nomadnetwork://` deep link (+ request extra) → `PendingNavigation` |

## Verification

- `NomadNetSavedSitesTest`, `NomadNetShortcutsTest` — identity, codec, ordering,
  de-duplication, malformed storage, label/deep-link derivation.
- `NomadNetShortcutPinnerTest` (+ Robolectric) — intent wiring, pin request,
  unsupported-launcher fallback.
- `NomadNetSitesViewModelTest`, `NomadNetBrowserViewModelTest` — save/remove/undo,
  star state per page, request-aware identity, session restore and Back keeping
  `requestData`.
- `NomadNetBrowserTabOwnershipTest`, `NavigationGraphNotSetContractTest` — the
  shortcut deep link reaches the NomadNet tab even when it arrives before the nav
  graph is attached (retried, not dropped).
- Device check: pin a page, tap the shortcut from the launcher, confirm the page
  (not the tab default) loads; save a page and confirm the star is filled.
