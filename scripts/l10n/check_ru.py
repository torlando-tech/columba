#!/usr/bin/env python3
"""Проверка полноты и корректности русской локализации (values-ru).

Сравнивает app/src/main/res/values/strings.xml (источник, upstream) с
app/src/main/res/values-ru/strings.xml (перевод, форк) и проверяет:

  * ключи, которых нет в переводе (после слияния с upstream);
  * лишние ключи в переводе (upstream удалил/переименовал строку);
  * совпадение плейсхолдеров (%1$s, %2$d, ...);
  * наличие всех русских форм plurals: one / few / many / other;
  * длину string-array.

Также (информационно, флаг --hardcoded) считает английские литералы в
Kotlin-коде, которые не вынесены в ресурсы и потому не переводятся.

Коды выхода: 0 - всё в порядке, 1 - есть ошибки (для CI).
Запуск из корня репозитория:  python3 scripts/l10n/check_ru.py [--hardcoded]
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
SRC = RES / "values/strings.xml"
DST = RES / "values-ru/strings.xml"
RU_PLURALS = {"one", "few", "many", "other"}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXeEgcn]")


def load(path):
    out = {}
    for e in ET.parse(path).getroot():
        name = e.get("name")
        if e.tag == "string":
            out[name] = ("string", "".join(e.itertext()), e.get("translatable") != "false")
        elif e.tag == "plurals":
            out[name] = ("plurals", {i.get("quantity"): "".join(i.itertext()) for i in e}, True)
        elif e.tag == "string-array":
            out[name] = ("array", ["".join(i.itertext()) for i in e], e.get("translatable") != "false")
    return out


def phs(text):
    # "%%" - литерал процента, плейсхолдером не считается
    return sorted(PLACEHOLDER.findall(text.replace("%%", "")))


def check_resources():
    src, dst = load(SRC), load(DST)
    translatable = {k for k, v in src.items() if v[2]}
    errors, missing = [], sorted(translatable - set(dst))
    for k in missing:
        errors.append(f"нет перевода: {k}")
    for k in sorted(set(dst) - set(src)):
        errors.append(f"лишний ключ в values-ru (удалён в upstream?): {k}")
    for k in sorted(translatable & set(dst)):
        (st, sv, _), (dt, dv, _) = src[k], dst[k]
        if st != dt:
            errors.append(f"{k}: разный тип ресурса ({st} vs {dt})")
        elif st == "string":
            if phs(sv) != phs(dv):
                errors.append(f"{k}: плейсхолдеры {phs(sv)} != {phs(dv)}")
        elif st == "plurals":
            absent = RU_PLURALS - set(dv)
            if absent:
                errors.append(f"{k}: не хватает русских форм {sorted(absent)}")
            allowed = set(phs(sv.get("other", "")))
            for q, t in dv.items():
                bad = set(phs(t)) - allowed
                if bad:
                    errors.append(f"{k}[{q}]: лишние плейсхолдеры {sorted(bad)}")
        elif st == "array" and len(sv) != len(dv):
            errors.append(f"{k}: длина массива {len(sv)} != {len(dv)}")
    return errors, missing, src


def report_hardcoded():
    pats = {
        "Text(\"...\")": re.compile(r'\bText\(\s*(?:text\s*=\s*)?"[A-Za-z]'),
        "contentDescription": re.compile(r'contentDescription\s*=\s*"[A-Za-z]'),
        "Toast/Snackbar": re.compile(r'(?:Toast\.makeText\([^"\n]*|showSnackbar\()\s*"[A-Za-z]'),
    }
    total = {k: 0 for k in pats}
    per_file = {}
    for f in (ROOT / "app/src/main").rglob("*.kt"):
        text = f.read_text(encoding="utf-8", errors="replace")
        for k, p in pats.items():
            n = len(p.findall(text))
            if n:
                total[k] += n
                per_file[f.relative_to(ROOT)] = per_file.get(f.relative_to(ROOT), 0) + n
    print("\nЗахардкоженные английские строки в Kotlin (не переводятся):")
    for k, n in total.items():
        print(f"  {k}: {n}")
    for f, n in sorted(per_file.items(), key=lambda x: -x[1])[:15]:
        print(f"  {n:4d}  {f}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hardcoded", action="store_true", help="показать счётчик захардкоженных строк в .kt")
    args = ap.parse_args()

    errors, missing, src = check_resources()
    if missing:
        print("Строки для перевода (скопируйте в values-ru/strings.xml и переведите):\n")
        tree = ET.parse(SRC).getroot()
        for e in tree:
            if e.get("name") in missing:
                print("    " + ET.tostring(e, encoding="unicode").strip())
        print()
    for e in errors:
        print("ERROR:", e)
    if args.hardcoded:
        report_hardcoded()
    total = sum(1 for v in src.values() if v[2])
    print(f"\nПереведено ключей: {total - len(missing)}/{total}; ошибок: {len(errors)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
