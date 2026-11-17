#!/usr/bin/env python3
"""Validate Android locale resources and track translation staleness.

Source of truth is always `src/main/res/values/strings.xml` of each module.
Locale files are `src/main/res/values-<tag>/strings.xml` (e.g. `values-ru`).

Structural errors (block the merge):
  - key in a locale file that no longer exists in the source
  - resource kind mismatch (`<string>` vs `<plurals>` vs `<string-array>`)
  - placeholder drop, extra positional argument, type change, bare `%s`
  - malformed percent sequence (lone `%`, `%1$d%` instead of `%1$d%%`)
  - `<plurals>` missing the `other` quantity
  - duplicate `name` in one file
  - non-translatable (`translatable="false"`) value copied with edits
  - unparsable XML

Bookkeeping errors (`--verify`, block the merge):
  - a locale key with no entry in `l10n/state.json`
  - a state entry whose key is gone from the locale file

Reported, never fatal:
  - missing keys (partial files are legal; Android falls back to English)
  - stale translations: source text changed since the recorded hash
  - values byte-identical to English (usually an untranslated copy)

A deliberate placeholder drop can be excused next to the string with an XML
comment `<!-- l10n-allow: reason -->` (applies to the following element).

Usage:
  check_locales.py                 # validate + report
  check_locales.py --verify        # also enforce state bookkeeping (CI)
  check_locales.py --write-state   # refresh l10n/state.json (translator PRs)
  check_locales.py --summary-md    # markdown table for $GITHUB_STEP_SUMMARY
  check_locales.py --fail-on-stale # release gate on stale translations
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
STATE_PATH = REPO_ROOT / "l10n" / "state.json"
SOURCE_DIR = "values"

# values-ru, values-pl, values-ru-rRU, values-b+sr+Latn, values-zh-rCN.
# Android configuration qualifiers (values-v31, values-night, values-sw600dp,
# values-xxhdpi, ...) must not match.
LOCALE_DIR = re.compile(r"^values-(?:b\+[A-Za-z+]+|[a-z]{2,3}(?:-r(?:[A-Z]{2}|\d{3}))?)$")
SKIP_DIRS = {"build", ".git", ".gradle", ".worktrees", "node_modules"}
FORMAT_SPEC = re.compile(r"%(?:(\d+)\$)?([-+#0 ]*)(\d+)?(?:\.(\d+))?([A-Za-z%])")
ALLOW_PREFIX = "l10n-allow:"


class Item:
    """One resource entry from a strings.xml."""

    __slots__ = ("kind", "text", "quantities", "translatable", "formatted", "allow")

    def __init__(self, kind, text="", quantities=None, translatable=True,
                 formatted=True, allow=False):
        self.kind = kind
        self.text = text
        self.quantities = quantities or {}
        self.translatable = translatable
        self.formatted = formatted
        self.allow = allow

    def source_hash(self):
        if self.kind == "string":
            payload = self.text.strip()
        elif self.kind == "plural":
            payload = json.dumps({q: t.strip() for q, t in sorted(self.quantities.items())},
                                 ensure_ascii=False)
        else:
            payload = "\x1f".join(self.text)
        return hashlib.sha1(payload.encode()).hexdigest()[:12]


def specs(text):
    """Return (positional args by position, malformed fragments, saw implicit arg)."""
    args, bad, implicit, order = {}, [], False, 0
    i = 0
    while i < len(text):
        if text[i] != "%":
            i += 1
            continue
        if text.startswith("%%", i):
            i += 2
            continue
        m = FORMAT_SPEC.match(text, i)
        if not m or m.group(5) == "%":
            bad.append(text[max(0, i - 8):i + 8])
            i += 1
            continue
        position = m.group(1)
        if position is None:
            implicit = True
            order += 1
            position = str(order)
        args[position] = m.group(5)
        i = m.end()
    return args, bad, implicit


def load_resources(path):
    """Parse one resource XML. Returns (items by name, duplicate names)."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.parse(path, parser).getroot()
    items, duplicates, pending_allow = {}, [], False
    for el in root:
        if el.tag is ET.Comment:
            if ALLOW_PREFIX in (el.text or ""):
                pending_allow = True
            continue
        name = el.get("name")
        if name is None:
            pending_allow = False
            continue
        if name in items:
            duplicates.append(name)
        translatable = el.get("translatable") != "false"
        formatted = el.get("formatted") != "false"
        if el.tag == "string":
            items[name] = Item("string", el.text or "", translatable=translatable,
                               formatted=formatted, allow=pending_allow)
        elif el.tag == "plurals":
            quantities = {it.get("quantity"): (it.text or "") for it in el}
            items[name] = Item("plural", quantities=quantities, translatable=translatable,
                               formatted=formatted, allow=pending_allow)
        elif el.tag in ("string-array", "array", "integer-array"):
            items[name] = Item("array", [it.text or "" for it in el],
                               translatable=translatable, formatted=formatted,
                               allow=pending_allow)
        elif el.tag == "item" and el.get("type") == "string":
            items[name] = Item("string", el.text or "", translatable=translatable,
                               formatted=formatted, allow=pending_allow)
        pending_allow = False
    return items, duplicates


def texts_of(item):
    """All text fragments of an entry (plural quantities, array items, single string)."""
    if item.kind == "string":
        return [item.text]
    if item.kind == "plural":
        return list(item.quantities.values())
    return list(item.text)


def union_args(item):
    """Placeholder arguments used anywhere in the entry: position -> conversion."""
    args, bad, implicit = {}, [], False
    if not item.formatted:
        return args, bad, implicit
    for fragment in texts_of(item):
        fragment_args, fragment_bad, fragment_implicit = specs(fragment)
        bad.extend(fragment_bad)
        implicit = implicit or fragment_implicit
        for position, conversion in fragment_args.items():
            args.setdefault(position, conversion)
    return args, bad, implicit


def check_entry(name, source, target, locale):
    errors = []
    if source.kind != target.kind:
        return [f"kind mismatch: {name} is <{source.kind}> in source, <{target.kind}> in {locale}"]

    if not source.translatable:
        if texts_of(target) != texts_of(source):
            errors.append(f"translatable=false was edited: {name}")
        return errors

    if source.kind == "plural":
        if "other" not in target.quantities:
            errors.append(f'plurals missing "other": {name}')
        unknown = set(target.quantities) - set(source.quantities) - {"one", "few", "many", "two", "zero"}
        if unknown:
            errors.append(f"unknown plural quantity {sorted(unknown)}: {name}")
    if source.kind == "array" and len(target.text) != len(source.text):
        errors.append(f"array length {len(source.text)} -> {len(target.text)}: {name}")
        return errors

    if not source.formatted:
        return errors

    source_args, source_bad, source_implicit = union_args(source)
    target_args, target_bad, target_implicit = union_args(target)
    if source_bad:
        errors.append(f"malformed percent sequence in source (fix values/strings.xml): {name}")
    if target_bad:
        errors.append(f"malformed percent sequence in {locale}: {name} ({', '.join(target_bad)})")
    for position, conversion in source_args.items():
        if position not in target_args:
            if not target.allow:
                errors.append(
                    f"placeholder %{position}${conversion} dropped: {name}"
                    f"  (add `<!-- l10n-allow: reason -->` above it if intentional)")
            continue
        if target_args[position] != conversion:
            errors.append(f"placeholder %{position}$ type {conversion} -> {target_args[position]}: {name}")
    for position in target_args.keys() - source_args.keys():
        errors.append(f"placeholder %{position}$ does not exist in source: {name}")
    # Legacy source entries still use bare %s/%d; only require positional
    # arguments where the English source is already fully positional.
    if target_implicit and not source_implicit:
        errors.append(f'non-positional placeholder (use %1$s): {name}')
    return errors


def locale_files(res_dir):
    if not res_dir.is_dir():
        return []
    return sorted(p for p in res_dir.iterdir() if p.is_dir() and LOCALE_DIR.match(p.name))


def discover_modules():
    """Every module directory that has a src/main/res with a values/strings.xml."""
    modules = []
    for res_dir in REPO_ROOT.glob("*/src/main/res"):
        relative_parts = res_dir.relative_to(REPO_ROOT).parts
        if any(part in SKIP_DIRS for part in relative_parts):
            continue
        if (res_dir / SOURCE_DIR / "strings.xml").is_file():
            modules.append(res_dir.parents[2])
    return sorted(modules)


def load_state():
    if not STATE_PATH.exists():
        return {}
    return json.loads(STATE_PATH.read_text(encoding="utf-8") or "{}")


def save_state(state):
    trimmed = {
        module: {locale: dict(sorted(keys.items())) for locale, keys in sorted(locales.items())
                 if keys}
        for module, locales in sorted(state.items()) if locales
    }
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps(trimmed, indent=2, ensure_ascii=False) + "\n",
                          encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true",
                        help="enforce l10n/state.json bookkeeping (CI mode)")
    parser.add_argument("--write-state", action="store_true",
                        help="record current source hashes into l10n/state.json")
    parser.add_argument("--fail-on-stale", action="store_true",
                        help="exit nonzero when any translation is stale")
    parser.add_argument("--summary-md", action="store_true",
                        help="print a markdown coverage table")
    args = parser.parse_args()

    errors, warnings, rows, stale_total = [], [], [], 0
    missing = {}
    state = load_state()
    state_changed = False

    for module_dir in discover_modules():
        module = module_dir.name
        source_items, source_dupes = load_resources(module_dir / "src/main/res/values/strings.xml")
        if source_dupes:
            errors.append(f"{module}: duplicate names in values/strings.xml: {sorted(set(source_dupes))}")

        for locale_dir in locale_files(module_dir / "src/main/res"):
            locale = locale_dir.name[len("values-"):]
            try:
                target_items, target_dupes = load_resources(locale_dir / "strings.xml")
            except ET.ParseError as exc:
                errors.append(f"{module}/{locale}: XML does not parse: {exc}")
                continue
            if target_dupes:
                errors.append(f"{module}/{locale}: duplicate names: {sorted(set(target_dupes))}")

            for name in sorted(set(target_items) - set(source_items)):
                errors.append(f"{module}/{locale}: extra key {name} (not in values/strings.xml)")
            for name, source in sorted(source_items.items()):
                target = target_items.get(name)
                if target is None:
                    missing.setdefault(f"{module}/{locale}", []).append(name)
                    continue
                errors.extend(f"{module}/{locale}: {e}"
                              for e in check_entry(name, source, target, locale))

            identical = sum(1 for name, item in target_items.items()
                            if item.kind == "string" and item.translatable
                            and name in source_items
                            and item.text == source_items[name].text
                            and item.text.strip())

            if f"{module}/{locale}" in missing:
                names = missing[f"{module}/{locale}"]
                shown = ", ".join(names[:8])
                more = f", +{len(names) - 8} more" if len(names) > 8 else ""
                warnings.append(f"{module}/{locale}: {len(names)} missing key(s): {shown}{more}")

            recorded = state.get(module, {}).get(locale, {})
            expected = {name: source_items[name].source_hash()
                        for name in target_items if name in source_items}
            if args.write_state:
                state.setdefault(module, {})[locale] = expected
                state_changed = True
            else:
                unrecorded = expected.keys() - recorded.keys()
                orphaned = recorded.keys() - expected.keys()
                if unrecorded and args.verify:
                    errors.append(f"{module}/{locale}: {len(unrecorded)} key(s) missing from "
                                  f"l10n/state.json — run --write-state and commit it")
                if orphaned and args.verify:
                    errors.append(f"{module}/{locale}: {len(orphaned)} stale state entr(ies) for "
                                  f"keys not in the locale file: {sorted(orphaned)[:5]}")
                stale = sorted(name for name in expected.keys() & recorded.keys()
                               if recorded[name] != expected[name])
                stale_total += len(stale)
                if stale:
                    warnings.append(f"{module}/{locale}: {len(stale)} stale translation(s), "
                                    f"source changed since recorded: {stale[:5]}")
                rows.append((module, locale, len(target_items.keys() & source_items.keys()),
                             len(source_items), len(stale), identical))

    if state_changed and not errors:
        save_state(state)
        print(f"Wrote {STATE_PATH.relative_to(REPO_ROOT)}", file=sys.stderr)
    elif state_changed:
        print("Not writing state: fix the errors above first", file=sys.stderr)

    for message in errors:
        print(f"ERROR {message}")
    for message in warnings:
        print(f"warn  {message}")

    if args.summary_md:
        print()
        if rows:
            print("| Module | Locale | Translated | Source | Coverage | Stale | Identical to English |")
            print("|---|---|---|---|---|---|---|")
            for module, locale, translated, source_count, stale, identical in rows:
                coverage = 100.0 * translated / max(source_count, 1)
                print(f"| {module} | {locale} | {translated} | {source_count} | "
                      f"{coverage:.1f}% | {stale} | {identical} |")
        else:
            print("No locale resources yet. Add `app/src/main/res/values-<lang>/strings.xml`.")
    else:
        for module, locale, translated, source_count, stale, identical in rows:
            coverage = 100.0 * translated / max(source_count, 1)
            print(f"{module}/{locale}: {translated}/{source_count} keys ({coverage:.1f}%), "
                  f"{stale} stale, {identical} identical to English")

    print(f"\n{len(errors)} error(s), {len(warnings)} warning(s)")
    if errors:
        return 1
    if args.fail_on_stale and stale_total:
        print(f"failing: {stale_total} stale translation(s) (--fail-on-stale)")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
