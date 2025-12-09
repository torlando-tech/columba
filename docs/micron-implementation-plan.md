# Micron Rendering Implementation Plan for Columba

## Overview

Add Micron markup rendering to Columba Android LXMF messenger. This would make Columba the first Android app with native micron page rendering (Sideband lacks this feature; MeshChat is web-only).

---

## Architecture

### Current Message Flow
```
MessageEntity (DB) → Message → MessageUi (with decoded image) → MessagingScreen
```

### Proposed Flow with Micron
```
MessageEntity → Message → MessageUi (with decoded image + parsed Micron) → MessagingScreen
```

---

## Module Structure

Create new `:micron` module (pure Kotlin, no Android deps):

```
columba/
├── micron/
│   ├── build.gradle.kts
│   └── src/main/java/com/lxmf/messenger/micron/
│       ├── MicronParser.kt      # Main parser (~900 lines ported from JS)
│       ├── MicronState.kt       # Parser state machine
│       ├── MicronElement.kt     # Sealed class hierarchy for parsed elements
│       └── MicronStyle.kt       # Style definitions
```

---

## Critical Files to Modify

| File | Change |
|------|--------|
| `settings.gradle.kts` | Add `include(":micron")` |
| `app/build.gradle.kts` | Add `implementation(project(":micron"))` |
| `app/.../ui/model/MessageUi.kt` | Add `parsedContent: MicronDocument?`, `hasMicronMarkup: Boolean` |
| `app/.../ui/model/MessageMapper.kt` | Add micron detection + parsing in `toMessageUi()` |
| `app/.../ui/screens/MessagingScreen.kt:434` | Replace `Text()` with conditional `MicronText()` |
| NEW: `app/.../ui/components/MicronText.kt` | Compose composable for rendering |

---

## Implementation Phases

### Phase 1: Core Parser Module

**Data Classes:**
```kotlin
// MicronElement.kt - Sealed hierarchy
sealed class MicronElement {
    data class Text(val content: String, val style: MicronStyle) : MicronElement()
    data class Link(val label: String, val url: String, val style: MicronStyle) : MicronElement()
    data class Divider(val character: Char?) : MicronElement()
    data class Heading(val level: Int, val content: List<MicronElement>) : MicronElement()
    object LineBreak : MicronElement()
}

// MicronStyle.kt
data class MicronStyle(
    val foregroundColor: Int? = null,   // ARGB
    val backgroundColor: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: MicronAlignment = MicronAlignment.LEFT
)

// MicronDocument.kt - Parser output
data class MicronDocument(val lines: List<MicronLine>)
data class MicronLine(
    val elements: List<MicronElement>,
    val alignment: MicronAlignment,
    val indentLevel: Int,
    val isHeading: Boolean = false,
    val headingLevel: Int = 0
)
```

**Features to Port (Priority Order):**
1. Text formatting: `` `! `` (bold), `` `* `` (italic), `` `_ `` (underline)
2. Colors: `` `Fxxx `` (foreground), `` `Bxxx `` (background), `` `gxx `` (grayscale)
3. Escape sequences: `\` before special chars
4. Literal blocks: `` `= `` toggle
5. Headings: `>`, `>>`, `>>>`
6. Dividers: `-`, `-X`
7. Alignment: `` `c ``, `` `l ``, `` `r ``
8. Links: `[label`URL]`

---

### Phase 2: Data Model Integration

**MessageUi.kt changes:**
```kotlin
@Immutable
data class MessageUi(
    // ... existing fields
    val decodedImage: ImageBitmap? = null,
    val parsedContent: MicronDocument? = null,  // NEW
    val hasMicronMarkup: Boolean = false        // NEW
)
```

**MessageMapper.kt changes:**
```kotlin
fun Message.toMessageUi(): MessageUi {
    val decodedImage = decodeImageFromFields(fieldsJson)
    val hasMicron = containsMicronMarkup(content)  // Fast detection
    val parsedContent = if (hasMicron) micronParser.parse(content) else null

    return MessageUi(
        // ... existing
        parsedContent = parsedContent,
        hasMicronMarkup = hasMicron
    )
}

// Fast detection to skip parsing for plain text
private fun containsMicronMarkup(content: String): Boolean {
    if (content.length < 2) return false
    return content[0] in setOf('>', '-', '#') ||
           content.contains('`') ||
           content.contains('[')
}
```

---

### Phase 3: Compose UI

**New MicronText.kt composable:**
```kotlin
@Composable
fun MicronText(
    document: MicronDocument,
    baseStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    onLinkClick: ((String) -> Unit)? = null
) {
    Column {
        for (line in document.lines) {
            // Build AnnotatedString from elements
            // Handle alignment per-line
            // Render headings with larger font
            // Render dividers as HorizontalDivider
        }
    }
}
```

**MessagingScreen.kt:434 changes:**
```kotlin
// Replace existing Text() with:
if (message.parsedContent != null) {
    MicronText(
        document = message.parsedContent,
        baseColor = if (isFromMe) colorScheme.onPrimaryContainer else colorScheme.onSurface,
        onLinkClick = { url -> /* handle */ }
    )
} else {
    Text(text = message.content, ...)
}
```

---

## Compose Mapping Reference

| Micron | Compose |
|--------|---------|
| Bold | `SpanStyle(fontWeight = FontWeight.Bold)` |
| Italic | `SpanStyle(fontStyle = FontStyle.Italic)` |
| Underline | `SpanStyle(textDecoration = TextDecoration.Underline)` |
| Fg color | `SpanStyle(color = Color(argb))` |
| Bg color | `SpanStyle(background = Color(argb))` |
| Links | `ClickableText` with `pushStringAnnotation("URL", ...)` |
| Alignment | `TextAlign.Start/Center/End` on `Text()` |
| Headings | Larger `fontSize`, `FontWeight.Bold` |

---

## Testing Strategy

1. **Unit tests** in `:micron` module for parser logic
2. **Integration tests** for `MessageMapper` micron detection
3. **Compose UI tests** for `MicronText` rendering

---

## Future Enhancements (Not in Scope)

- Form fields (text input, checkbox, radio)
- NomadNet page browser
- `nomadnetwork://` URL scheme handler
- Field submission via LXMF

---

## Reference

- **JS Parser:** `micron-parser-js/js/micron-parser.js` (907 lines)
- **Columba MessageMapper:** `app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt`
- **Columba MessageUi:** `app/src/main/java/com/lxmf/messenger/ui/model/MessageUi.kt`
- **Columba MessagingScreen:** `app/src/main/java/com/lxmf/messenger/ui/screens/MessagingScreen.kt:434`
