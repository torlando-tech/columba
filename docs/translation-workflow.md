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

1. Create `app/src/main/res/values-<lang>/` (e.g. `values-de/`).
2. Copy `values/strings.xml` into it and translate the `<string>`/`<plurals>`
   values. Keep `name` attributes, placeholders, and XML escapes intact.
3. Translate only `values-*`; never edit `values/strings.xml` as a
   "translation" — it is the English source.
4. Strings that must not be translated are already excluded by the
   do-not-translate policy above; they should still be copied verbatim.
5. Verify locally with the pseudo-locale (below) and `./gradlew
   :app:lintDebug`.

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

- `MissingTranslation`, `ExtraTranslation` — locale completeness
- `ImpliedQuantity` — plurals that need `other` variants for gender/context
- `StringFormatInvalid`, `StringFormatMatches` — placeholder/type mismatches
- `PluralsCandidate` — English strings that should become plurals

Fix the entire backlog before adding each check; never baseline.

## Translation platform

Weblate vs Crowdin is **not decided yet** (deferred; tracked in the i18n
issue tracker). Either way the contract is the same: the platform syncs
`app/src/main/res/values-*/strings.xml` against `values/strings.xml` via
GitHub; no export format conversion is introduced.
