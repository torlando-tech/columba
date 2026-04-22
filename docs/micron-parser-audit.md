# Micron Parser Audit: Columba vs Reference Implementations

**Date:** 2026-03-14
**Scope:** Columba's `MicronParser.kt` compared against:
- NomadNet `MicronParser.py` (Python, reference/canonical)
- `micron-parser-js` (JavaScript, 1:1 port of NomadNet)

## Architecture Overview

### Columba's Three Rendering Modes

All three modes share the **same parser** (`MicronParser.parse()`) but differ in
layout and styling at the Compose rendering layer (`MicronComposables.kt`).

| Mode | Font | Font Size | Layout | Use Case |
|------|------|-----------|--------|----------|
| **MONOSPACE_SCROLL** | JetBrains Mono NL | 14sp | Horizontal + vertical scroll, pinch zoom (0.5x–3x), square line height for pixel art | ASCII/pixel art pages |
| **MONOSPACE_ZOOM** | JetBrains Mono NL | 10sp | Vertical scroll only, pinch zoom | Compact monospace viewing |
| **PROPORTIONAL_WRAP** | System default | 14sp | Text wraps within viewport, LazyColumn | Regular readable text |

**Rendering-mode-specific behaviors:**
- MONOSPACE_SCROLL uses `lineHeight = 2 × charWidth` (sp) for square half-block pixels, `letterSpacing = 0.sp`, and `includeFontPadding = false` for tight pixel art.
- MONOSPACE_SCROLL/ZOOM use `softWrap = false`; PROPORTIONAL_WRAP uses `softWrap = true`.
- Center/right-aligned text: MONOSPACE_SCROLL uses `widthIn(min=viewport)` (allowing horizontal scroll); other modes use `width(viewport)` (exact width for wrapping).
- MONOSPACE_SCROLL renders all lines in a single `Column` with scroll modifiers. Other modes use `LazyColumn` with per-line items.

---

## Discrepancies Found

### D1 — CRITICAL: Links and fields parsed without requiring backtick prefix

**NomadNet/micron-parser-js:** `[` and `<` only trigger link/field parsing when the
parser is in **formatting mode** (entered by encountering a backtick `` ` ``). In text
mode, `[` and `<` are literal characters.

**Columba:** `[` and `<` are parsed as link/field openers directly in `parseInline()`,
regardless of whether a backtick preceded them.

**Impact:** Any document containing literal `[` or `<` characters (e.g., `[some note]`,
`x < y`) will be incorrectly parsed as links or fields in Columba. Conversely, pages
that properly use `` `[label`dest] `` work fine in both.

**Files:** `MicronParser.kt:229-262` (link/field parsing in text mode)

**Fix:** Move `[` and `<` handling into the backtick processing branch. When a backtick
is encountered and the next char is `[` or `<`, delegate to link/field parsing. In text
mode, treat `[` and `<` as literal characters.

---

### D2 — CRITICAL: `<` section depth reset requires exact line match

**NomadNet/micron-parser-js:** `<` at line start resets depth to 0, then **recursively
re-parses the remainder** of the line:
```python
elif first_char == "<":
    state["depth"] = 0
    return parse_line(line[1:], state, url_delegate)
```

**Columba:** Requires `line == "<"` (exact match). A line like `<Content after reset`
would be treated as regular text, not a depth reset.

**Impact:** Pages that put content on the same line after `<` will not have depth reset
applied, and the content will not render.

**Files:** `MicronParser.kt:100-103`

**Fix:** Check `line.startsWith("<")`, reset depth, then re-parse `line.drop(1)`.

---

### D3 — MODERATE: Literal mode toggle accepts indented `` `= ``

**NomadNet/micron-parser-js:** Exact match — the line must be precisely the 2-character
string `` `= `` (no leading/trailing whitespace).
```python
if len(line) == 2 and line == "`=":
```

**Columba:** Uses `line.trimStart() == "`="` which matches even if there is leading
whitespace (e.g., `   \`=`).

**Impact:** Indented `` `= `` in a document would toggle literal mode in Columba but be
treated as regular inline text in NomadNet.

**Files:** `MicronParser.kt:62`

**Fix:** Change to `line == "\`="` (exact match, no trimming).

---

### D4 — MODERATE: No line-start escape to prevent block-level parsing

**NomadNet:** `\` as the first character of a line is consumed, prevents block-level
parsing (headings, dividers, comments, depth reset), and sets `pre_escape=True` so the
next character is treated as literal text.
```python
if first_char == "\\":
    line = line[1:]
    pre_escape = True
```

**micron-parser-js:** No explicit `pre_escape` mechanism, but `\` doesn't match any
block-level first-character checks, so block-level parsing is skipped incidentally.
The `\` then escapes the next character in inline mode.

**Columba:** Escape handling only exists in `parseInline()`. Block-level checks run
first. So `\>Hello` → heading parsing triggers on the `\` not matching `>` ... actually,
since `\` is the first char and it's not `>`, headings are skipped. But `\-` would not
match `-` either. However, `\#comment` WOULD skip comment detection because `\` ≠ `#`.

Wait — actually Columba checks `line.startsWith("#")`, `line.startsWith(">")`,
`line.startsWith("-")`. Since the line starts with `\`, none of these match, so block
parsing is incidentally skipped (similar to micron-parser-js). The `\` is then consumed
in `parseInline()` as an escape.

**Remaining issue:** The `\` itself is consumed in `parseInline` and escapes the next
character, so `\>Hello` outputs `>Hello` in all three parsers. This is functionally
equivalent.

**Verdict:** Not actually a discrepancy for Columba in practice. The block-level checks
naturally skip because `\` doesn't match any block-level starter. ✅ No fix needed.

---

### D5 — MODERATE: Divider custom character not restricted to 2-char lines

**NomadNet:** Custom divider character only when line is **exactly** 2 characters:
```python
if len(line) == 2:
    divider_char = line[1]
else:
    divider_char = "\u2500"
```

**micron-parser-js:** Any line starting with `-` that is longer than 1 char takes
`line[1]` as divider char (no length restriction).

**Columba:** Takes `line[1]` if `line.length >= 2`, matching micron-parser-js but not
NomadNet:
```kotlin
val dividerChar = if (line.length >= 2) line[1] else '\u2500'
```

**Impact:** A line like `-Hello` would produce a divider with char `H` in Columba and
micron-parser-js, but a default `─` divider in NomadNet.

**Files:** `MicronParser.kt:137`

**Fix:** Change to `if (line.length == 2) line[1] else '\u2500'` to match NomadNet.

---

### D6 — MODERATE: `trimEnd()` strips trailing whitespace from lines

**NomadNet/micron-parser-js:** No line trimming at all. Lines are used exactly as split
from `\n`.

**Columba:** Applies `line.trimEnd()` to every input line:
```kotlin
val line = rawLine.trimEnd()
```

**Impact:** Trailing whitespace with background colors is significant in pixel art pages.
A line ending in `\`B00f   ` (three spaces with blue background) would lose those spaces
in Columba, creating gaps in pixel art rendering.

**Files:** `MicronParser.kt:47`

**Fix:** Remove `trimEnd()`. Process lines exactly as split.

---

### D7 — MINOR: No heading + field conflict sanitization

**NomadNet:** When a line starts with `>` AND contains `` `< `` (a field marker), the
leading `>` characters are stripped to prevent heading formatting from interfering with
field rendering:
```python
if first_char == ">" and "`<" in line:
    line = line.lstrip(">")
```

**micron-parser-js:** Does NOT have this sanitization.

**Columba:** Does NOT have this sanitization.

**Impact:** Fields inside heading lines would be rendered with heading styling applied,
which may cause visual issues. This is an edge case.

**Files:** `MicronParser.kt:106-133`

**Fix:** Add the same check before heading processing.

---

### D8 — MINOR: No control character check for divider characters

**NomadNet:** Replaces divider characters with `ord < 32` with the default `\u2500`:
```python
if ord(divider_char) < 32:
    divider_char = "\u2500"
```

**Columba/micron-parser-js:** No such check.

**Impact:** A divider like `-\x01` would produce a control character divider in Columba.
Unlikely in practice but could cause rendering issues.

**Files:** `MicronParser.kt:137`

**Fix:** Add `if (dividerChar.code < 32) '\u2500' else dividerChar`.

---

### D9 — MINOR: Double-backtick reset also resets alignment

**NomadNet:** `` `` `` resets bold, underline, italic, fg, bg, AND alignment to defaults.

**micron-parser-js:** Has a special case for double-backtick in text mode that handles
the reset differently.

**Columba:** `` `` `` resets style to `MicronStyle()` AND sets alignment to
`MicronAlignment.LEFT`.

**Verdict:** Columba matches NomadNet here. ✅ No fix needed.

---

### D10 — NOTE: micron-parser-js supports truecolor (`FT`/`BT`) commands

**micron-parser-js** has extended color support with `FT` (truecolor foreground) and `BT`
(truecolor background) commands that accept 6-char hex values. Neither NomadNet nor
Columba supports these.

**Impact:** Pages using truecolor commands would not render colors in Columba. This is a
micron-parser-js extension, not a NomadNet feature.

---

### D11 — NOTE: 6-char hex color support in page directives

**NomadNet:** The `make_style()` function has a code path for 6-char color strings, but
the `F`/`B` inline commands only consume 3 chars. The 6-char path exists but is not
reachable via standard markup.

**Columba:** `MicronColor.parse()` only handles 3-char strings. Page directives
(`#!bg=`, `#!fg=`) also use `MicronColor.parse()`, so 6-char hex values in directives
would fail.

**Impact:** Negligible — no known NomadNet pages use 6-char colors.

---

## Summary

| ID | Severity | Discrepancy | NomadNet | Columba | Status |
|----|----------|-------------|----------|---------|--------|
| D1 | **CRITICAL** | `[`/`<` parsed without backtick | Requires backtick | Parsed directly | **FIXED** |
| D2 | **CRITICAL** | `<` depth reset exact match | Re-parses remainder | Exact match only | **FIXED** |
| D3 | MODERATE | Literal mode trim | Exact match `` `= `` | `trimStart()` match | **FIXED** |
| D5 | MODERATE | Divider custom char length | Exactly 2 chars | Any length ≥ 2 | **FIXED** |
| D6 | MODERATE | `trimEnd()` on lines | No trimming | Trims trailing whitespace | **FIXED** |
| D7 | MINOR | Heading + field sanitization | Strips `>` when field present | No sanitization | **FIXED** |
| D8 | MINOR | Divider control char check | Replaces ord < 32 | No check | **FIXED** |

All discrepancies verified against NomadNet `MicronParser.py` and MeshChatX source (2026-03-28)
and resolved in this PR with 19 regression tests.

### Test with provided sample document

The sample document provided uses:
- `` `= `` literal mode — would work (on its own line, no indentation)
- `` `! `` bold — works ✅
- `` `* `` italic — works ✅
- `-` and `-∿` dividers — `-∿` is 3+ bytes in Columba's length check → D5 applies (`∿` used as divider char, which happens to be correct since `line.length >= 2`)
- `` `c ``, `` `r ``, `` `a `` alignment — works ✅
- `` `B005 ``, `` `Fff ``, `` `Ff00 `` etc. colors — works ✅
- `` `_ `` underline — works ✅
- `` `` `` reset all — works ✅
- `[label`dest]` links — would be parsed even without preceding backtick → D1 applies
- `Ffd0` on first line — this is a formatting command but sits as its own line. In NomadNet, it would be parsed as inline text with `` `F `` consuming `fd0`. In Columba, same behavior ✅

### Rendering Mode Observations

The three rendering modes only affect layout/styling, not parsing. All discrepancies
above affect all three modes equally. However:

- **D6 (trimEnd)** is most impactful in MONOSPACE_SCROLL mode where pixel art with
  trailing colored spaces would break.
- **D1 (link/field without backtick)** could cause false positives in PROPORTIONAL_WRAP
  mode where wrapped text containing `[` brackets would be misinterpreted as links.
