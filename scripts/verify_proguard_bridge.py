#!/usr/bin/env python3
"""
Verify that Python-Kotlin bridge methods are not obfuscated by R8.

This script extracts method names from Python source code that call into Kotlin
bridges (via Chaquopy) and verifies those method names exist in the release APK's
DEX files. If any methods are missing (obfuscated), the build should fail.

Usage:
    python scripts/verify_proguard_bridge.py <path-to-release.apk>

Exit codes:
    0 - All methods verified
    1 - Some methods are missing (likely obfuscated)
    2 - Usage error or file not found
"""
import re
import sys
import zipfile
from pathlib import Path

# Patterns to find bridge method calls in Python code
# These cover all the ways Python code calls into Kotlin bridges
# Generic patterns match any variable containing 'kotlin' or 'bridge' with a method call
BRIDGE_CALL_PATTERNS = [
    # Any self.*kotlin*.method() - catches kotlin_ble_bridge, kotlin_rnode_bridge, etc.
    r'self\.\w*kotlin\w*\.(\w+)\s*\(',
    # Any self._*kotlin*.method() - catches _kotlin_call_bridge (private var convention)
    r'self\._\w*kotlin\w*\.(\w+)\s*\(',
    # Any self.*_bridge.method() - catches kotlin_call_bridge, audio_bridge, etc.
    r'self\.\w*_bridge\.(\w+)\s*\(',
]

# Bridge classes are detected dynamically from the codebase
# Any class ending in "Bridge" in the messenger package should be preserved
# This matches the ProGuard rule: -keep class com.lxmf.messenger.**.*Bridge { *; }
BRIDGE_CLASS_PATTERN = r'(\w+Bridge)'


def extract_methods_from_python(python_dir: Path) -> set[str]:
    """Extract all bridge method names called from Python code."""
    methods = set()

    for py_file in python_dir.glob('**/*.py'):
        try:
            content = py_file.read_text(encoding='utf-8')
        except Exception as e:
            print(f"  Warning: Could not read {py_file}: {e}")
            continue

        for pattern in BRIDGE_CALL_PATTERNS:
            found = re.findall(pattern, content)
            methods.update(found)

    return methods


def extract_bridge_classes_from_kotlin(kotlin_dir: Path) -> set[str]:
    """Extract all *Bridge class names from Kotlin source code.

    This matches the ProGuard rule: -keep class com.lxmf.messenger.**.*Bridge { *; }
    Any class ending in 'Bridge' is considered a Python-callable bridge.
    """
    classes = set()

    for kt_file in kotlin_dir.glob('**/*.kt'):
        # Only look at main source, not test files
        if '/test/' in str(kt_file) or '/androidTest/' in str(kt_file):
            continue

        try:
            content = kt_file.read_text(encoding='utf-8')
        except Exception as e:
            print(f"  Warning: Could not read {kt_file}: {e}")
            continue

        # Find class declarations ending in "Bridge"
        # Matches: class FooBridge, object BarBridge, etc.
        class_pattern = r'\b(?:class|object)\s+(\w+Bridge)\b'
        found = re.findall(class_pattern, content)
        classes.update(found)

    return classes


def extract_dex_content(apk_path: Path) -> bytes:
    """Extract and concatenate all DEX files from APK."""
    dex_content = b''

    with zipfile.ZipFile(apk_path, 'r') as apk:
        for name in apk.namelist():
            if name.endswith('.dex'):
                dex_content += apk.read(name)

    return dex_content


def verify_in_dex(items: set[str], dex_content: bytes, item_type: str) -> tuple[set, set]:
    """Check which items exist in DEX content."""
    found = set()
    missing = set()

    for item in items:
        # DEX files contain method/class names as UTF-8 strings
        if item.encode('utf-8') in dex_content:
            found.add(item)
        else:
            missing.add(item)

    return found, missing


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <path-to-release.apk>")
        print()
        print("Verifies that Python-Kotlin bridge methods are not obfuscated.")
        sys.exit(2)

    apk_path = Path(sys.argv[1])
    if not apk_path.exists():
        print(f"ERROR: APK not found: {apk_path}")
        sys.exit(2)

    # Find source directories relative to script location
    script_dir = Path(__file__).parent.resolve()
    project_dir = script_dir.parent
    python_dir = project_dir / 'python'
    kotlin_dirs = [
        project_dir / 'reticulum' / 'src' / 'main' / 'java',
        project_dir / 'LXST-kt' / 'lxst' / 'src' / 'main' / 'java',
    ]

    if not python_dir.exists():
        print(f"ERROR: Python directory not found: {python_dir}")
        sys.exit(2)

    # At least one Kotlin source dir must exist
    existing_kotlin_dirs = [d for d in kotlin_dirs if d.exists()]
    if not existing_kotlin_dirs:
        print(f"ERROR: No Kotlin directories found: {kotlin_dirs}")
        sys.exit(2)

    print("=" * 60)
    print("Python-Kotlin Bridge ProGuard Verification")
    print("=" * 60)
    print()

    # Step 1: Extract bridge classes from Kotlin source (dynamic discovery)
    bridge_classes = set()
    for kotlin_dir in existing_kotlin_dirs:
        print(f"Scanning Kotlin source for *Bridge classes: {kotlin_dir}")
        bridge_classes.update(extract_bridge_classes_from_kotlin(kotlin_dir))
    print(f"Found {len(bridge_classes)} bridge classes: {', '.join(sorted(bridge_classes))}")
    print()

    # Step 2: Extract methods from Python source
    print(f"Scanning Python source: {python_dir}")
    methods = extract_methods_from_python(python_dir)
    print(f"Found {len(methods)} unique bridge method calls in Python code")
    print()

    # Step 3: Extract DEX content from APK
    print(f"Extracting DEX from: {apk_path}")
    dex_content = extract_dex_content(apk_path)
    print(f"DEX content size: {len(dex_content):,} bytes")
    print()

    # Step 4: Verify methods exist in DEX
    print("Verifying methods...")
    methods_found, methods_missing = verify_in_dex(methods, dex_content, "method")

    # Step 5: Verify bridge classes exist in DEX
    print("Verifying bridge classes...")
    classes_found, classes_missing = verify_in_dex(bridge_classes, dex_content, "class")

    # Report results
    print()
    print("-" * 60)
    print("RESULTS")
    print("-" * 60)

    # Methods
    print(f"\nMethods: {len(methods_found)}/{len(methods)} verified")
    if methods_found:
        for m in sorted(methods_found):
            print(f"  ✓ {m}")

    if methods_missing:
        print(f"\n✗ {len(methods_missing)} methods MISSING (likely obfuscated):")
        for m in sorted(methods_missing):
            print(f"  ✗ {m}")

    # Classes
    print(f"\nBridge Classes: {len(classes_found)}/{len(bridge_classes)} verified")
    if classes_found:
        for c in sorted(classes_found):
            print(f"  ✓ {c}")

    if classes_missing:
        print(f"\n✗ {len(classes_missing)} classes MISSING (likely obfuscated):")
        for c in sorted(classes_missing):
            print(f"  ✗ {c}")

    # Final verdict
    print()
    print("=" * 60)

    if methods_missing or classes_missing:
        print("VERIFICATION FAILED")
        print("=" * 60)
        print()
        print("Some Python-Kotlin bridge methods/classes appear to be obfuscated!")
        print("This will cause runtime failures when Python tries to call Kotlin code.")
        print()
        print("Fix: Add the missing classes/methods to app/proguard-rules.pro")
        print("Example:")
        print("  -keep class com.lxmf.messenger.reticulum.bridge.KotlinReticulumBridge { *; }")
        print("  -keepclassmembers class com.lxmf.messenger.reticulum.bridge.KotlinReticulumBridge { *; }")
        sys.exit(1)
    else:
        print("VERIFICATION PASSED")
        print("=" * 60)
        print()
        print("All Python-Kotlin bridge methods and classes are preserved.")
        sys.exit(0)


if __name__ == '__main__':
    main()
