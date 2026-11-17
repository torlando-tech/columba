# Translation Workflow

How Columba's UI strings are managed, how to add a locale, and what the tooling
enforces. Written when the `NoHardcodedUiString` migration reached a zero
baseline (i18n tickets 01–22).

## How strings are enforced

Every user-facing string lives in `app/src/main/res/values/strings.xml`
(`values/` is the English source of truth). Three layers keep it that way:

1. **`NoHardcodedUiString` detekt rule** (`detekt-rules/`). Flags string
   literals passed to UI sinks (`Text(...)`, `label =`, `contentDescription =`,
   `placeholder =`, notification titles, Toast/Snackbar text, enum and
   sealed-object display fields, …), descending through `+` concatenations,
   `if`/`else` branches, `?:` elvis chains, `when`-branch results, and
   supertype constructor args. The **detekt baseline for this rule is zero and
   must stay zero** — do not add `NoHardcodedUiString` entries to any
   `detekt-baseline.xml`, and do not use `@Suppress("NoHardcodedUiString")`
   (CI pins the suppression count to 0).
2. **Do-not-translate allowlist.** Strings that must *not* be translated
   (technical tokens, wire identifiers, animation labels, hardware product
   names, persisted default display names) are listed under
   `columba.NoHardcodedUiString.doNotTranslateLiterals` in
   `detekt-config.yml`, with a category comment per entry. If the rule flags a
   literal that genuinely must stay hardcoded, add it there — never suppress.
3. **Sink-equivalence gate** (CI job `ui-text-equivalence`). Dumps the UI-text
   skeleton of every changed file before/after migration and fails if any
   rendered string changed that shouldn't have. Migrations must be
   byte-identical: the `<string>` value must equal the literal it replaced
   exactly (including capitalization, `…` vs `...`, and `\n` placement).

## Conventions used by the migrated strings

- **Prefixes** group strings by screen/feature: `call_*`, `qr_scanner_*`,
  `offline_map_*`, `migration_*`, `flasher_*`, `ble_ctrl_*`, `settings_*`, …
  Follow the nearest existing block when adding strings.
- **Placeholders are positional**: `%1$s`, `%2$d` — never bare `%s`/`%d` in
  multi-argument strings, so translators can reorder arguments.
- **Plurals** use `<plurals>` with `%1$d` and are read with
  `pluralStringResource(R.plurals.x, n, n)`.
- **Newlines** are `\n` inside the XML value (rendered identically to the
  Kotlin literal it replaced).
- ViewModel/domain error text follows the `NewCallInvalidReason.stringRes()`
  pattern: the model exposes an enum/sealed type with a `@StringRes` mapping;
  the composable calls `stringResource(...)`. Never build user text in
  ViewModels.

## Adding a locale

1. Create `app/src/main/res/values-<lang>/` (e.g. `values-ru/`, `values-pl/`).
2. Copy `values/strings.xml` into it and translate the `<string>`/`<plurals>`
   values. Keep `name` attributes, placeholders, and XML escapes intact.
3. Translate only `values-*`; never edit `values/strings.xml` as a
   "translation" — it is the English source.
4. Strings that must not be translated are already excluded by the
   do-not-translate policy above; copy `translatable="false"` entries verbatim.
5. Run `python3 scripts/l10n/check_locales.py --write-state` and commit the
   `l10n/state.json` change in the same PR (see below).
6. Verify locally with the pseudo-locale (below) and `./gradlew
   :app:lintDebug`.

No manifest or XML file needs editing: `generateLocaleConfig = true`
(`app/build.gradle.kts`) makes AGP synthesize the `LocaleConfig` from the
`values-*` dirs and add `android:localeConfig` to the merged manifest, so
Android 13+ shows the app under Settings > Apps > Columba > Language. Do not
hand-write `res/xml/locale_config.xml` — the build fails if both mechanisms are
present. The default locale is declared once in `res/resources.properties`
(`unqualifiedResLocale=en`).

## Locale validator and staleness

`scripts/l10n/check_locales.py` is the whole enforcement layer, stdlib-only and
offline. It compares every `values-<lang>/strings.xml` with the module's
`values/strings.xml`.

Fatal (CI job `l10n-locales`, and `.github/workflows/l10n-status.yml`):

- key present in the locale file but absent from the source (renamed/deleted
  English strings left behind);
- resource kind mismatch (`<string>` vs `<plurals>` vs `<string-array>`);
- dropped placeholder, placeholder position that does not exist in the source,
  or `%s`/`%d` type change;
- malformed percent sequence (lone `%`, `%1$d%` instead of `%1$d%%`);
- `<plurals>` without the `other` quantity, or a quantity Android does not know;
- non-translatable (`translatable="false"`) entry edited;
- duplicate `name` in one file;
- unparsable XML;
- `--verify`: a locale key with no `l10n/state.json` entry, or a state entry
  whose key is no longer in the locale file.

Reported, never fatal: missing keys (partial files are legal, Android falls back
to English) and values byte-identical to English (usually an untranslated copy).

A deliberate placeholder drop can be excused next to the string:

```xml
<!-- l10n-allow: Russian drops the count here -->
<string name="map_time_days_ago">days ago</string>
```

### Staleness

`l10n/state.json` records, per module and locale, a 12-hex hash of the English
value each key was translated from:

```json
{ "app": { "ru": { "chat_send": "9c02ef11a8b3" } } }
```

- A translator PR runs `--write-state`, which records the current English hash
  for every key present in the locale file (and prunes entries for removed
  keys). This is the only way state changes.
- CI (`--verify`) fails if a locale key has no state entry — forgetting to
  commit state after adding translations is a merge-blocking mistake.
- When English text later changes, the recorded hash no longer matches and the
  key is listed as **stale** in CI output and in the weekly report. Staleness is
  never a merge gate for ordinary PRs (any English edit would poison them);
  `--fail-on-stale` exists for release gating.
- The hash is self-reported: `--write-state` refreshes it whether or not the
  translator re-read the change. What it guarantees is that drift is always
  *named and counted*, never silent. Translation quality stays a human review
  concern.

## Pseudo-locale

Debug builds enable `isPseudoLocalesEnabled = true`
(`app/build.gradle.kts`, debug buildType), generating the `en-XA` pseudo
locale. Selecting it in system language surfaces (a) any hardcoded string that
escaped the detekt rule (it stays English) and (b) truncation that real
translations would hit (pseudo-strings are length-expanded). Use a debug build
to smoke-test new layouts before translations land.

## Android Lint translation checks (next round)

Android Lint runs with a progressive `checkOnly` allowlist and **no baseline**
(root `build.gradle.kts`, "Android Lint — progressive enforcement"). Once a
first `values-*` locale lands, widen `checkOnly` on a clean day by adding, in
one change after fixing everything they flag:

- `ExtraTranslation`, `ImpliedQuantity` — keys Android cannot resolve, plural
  quantities the language needs
- `StringFormatInvalid`, `StringFormatMatches` — placeholder/type mismatches
- `PluralsCandidate` — English strings that should become plurals

`MissingTranslation` must stay a warning (or be handled only by
`scripts/l10n/check_locales.py`): as an error it would block any PR that adds a
new English string until every locale is updated in the same change.

Fix the entire backlog before adding each check; never baseline.

## No translation platform

Weblate and Crowdin were evaluated and **rejected**: translations arrive as
ordinary PRs against `app/src/main/res/values-*/strings.xml`, with the
validator and `l10n/state.json` above replacing the platform's sync, progress
board, and drift tracking. Rationale: two target locales do not justify a
service dependency, an account system, or per-string round-tripping; the
contract that matters (key parity, placeholder safety, staleness) is enforced
locally and in CI with no network access.

Translators need git and `python3`, nothing else. A web editor can be revisited
if the language count grows past the point where PR review stops scaling;
self-hosting Weblate remains an option that would keep data on our own
infrastructure, but it is not adopted.
