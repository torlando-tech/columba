package network.columba.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.columba.app.R
import network.columba.app.micron.MicronAlignment
import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronLine
import network.columba.app.micron.MicronStyle
import network.columba.app.nomadnet.PartialManager
import network.columba.app.viewmodel.NomadNetBrowserViewModel.RenderingMode

/** JetBrains Mono NL — block elements (▄ █ etc.) have the same advance width as ASCII. */
private val JetBrainsMonoFamily = FontFamily(Font(R.font.jetbrains_mono_nl_regular))

private const val INDENT_DP = 12
private const val HEADING1_SP = 24
private const val HEADING2_SP = 20
private const val HEADING3_SP = 18
private const val MIN_LINK_HEIGHT_DP = 48

@Composable
fun MicronPageContent(
    document: MicronDocument,
    formFields: Map<String, String>,
    renderingMode: RenderingMode,
    onLinkClick: (destination: String, fieldNames: List<String>) -> Unit,
    onFieldUpdate: (name: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
    minLineWidth: Dp = Dp.Unspecified,
    partialStates: Map<String, PartialManager.PartialState> = emptyMap(),
    lineIndexOffset: Int = 0,
) {
    val defaultFg = MaterialTheme.colorScheme.onSurface
    val pageBg = document.pageBackground?.toArgb()?.let { Color(it) }
    val containerModifier = if (pageBg != null) modifier.background(pageBg) else modifier

    // Measure monospace char width once so MicronLineComposable can set
    // lineHeight = 2 × charWidth (in sp), giving square half-block pixels.
    // Using TextStyle.lineHeight (not Modifier.layout) ensures SpanStyle.background
    // fills the full line height — critical for pixel art top-pixel rendering.
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val measuredLineHeightSp =
        remember(density) {
            val result =
                textMeasurer.measure(
                    AnnotatedString("A"),
                    TextStyle(fontFamily = JetBrainsMonoFamily, fontSize = 14.sp),
                )
            with(density) { (result.size.width * 2).toDp().toSp() }
        }
    val squareLineHeightSp =
        if (renderingMode == RenderingMode.MONOSPACE_SCROLL) measuredLineHeightSp else TextUnit.Unspecified

    Column(modifier = containerModifier) {
        for ((lineIndex, line) in document.lines.withIndex()) {
            MicronLineComposable(
                line = line,
                lineIndex = lineIndex + lineIndexOffset,
                formFields = formFields,
                defaultFg = defaultFg,
                renderingMode = renderingMode,
                onLinkClick = onLinkClick,
                onFieldUpdate = onFieldUpdate,
                minLineWidth = minLineWidth,
                partialStates = partialStates,
                squareLineHeightSp = squareLineHeightSp,
            )
        }
    }
}

@Suppress("ReturnCount")
@Composable
private fun MicronLineComposable(
    line: MicronLine,
    lineIndex: Int,
    formFields: Map<String, String>,
    defaultFg: Color,
    renderingMode: RenderingMode,
    onLinkClick: (destination: String, fieldNames: List<String>) -> Unit,
    onFieldUpdate: (name: String, value: String) -> Unit,
    minLineWidth: Dp = Dp.Unspecified,
    partialStates: Map<String, PartialManager.PartialState> = emptyMap(),
    squareLineHeightSp: TextUnit = TextUnit.Unspecified,
) {
    // Check if line is a line break
    if (line.elements.size == 1 && line.elements[0] is MicronElement.LineBreak) {
        Spacer(modifier = Modifier.height(8.dp))
        return
    }

    // Check if line is a divider
    val divider = line.elements.firstOrNull() as? MicronElement.Divider
    if (divider != null) {
        HorizontalDivider(
            modifier = Modifier.padding(start = (line.indentLevel * INDENT_DP).dp, top = 4.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }

    // Check if line is a partial
    val partial = line.elements.firstOrNull() as? MicronElement.Partial
    if (partial != null) {
        val key = partial.partialId?.let { "pid:$it" } ?: "pos:$lineIndex"
        val state = partialStates[key]
        when (state?.status) {
            null, PartialManager.PartialState.Status.LOADING -> {
                Box(
                    Modifier.fillMaxWidth().padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            PartialManager.PartialState.Status.LOADED -> {
                state.document?.let { doc ->
                    MicronPageContent(
                        document = doc,
                        formFields = formFields,
                        renderingMode = renderingMode,
                        onLinkClick = onLinkClick,
                        onFieldUpdate = onFieldUpdate,
                        partialStates = emptyMap(),
                    )
                }
            }
            PartialManager.PartialState.Status.ERROR -> {
                Text(
                    "Failed to load partial",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
        return
    }

    val fontFamily =
        when (renderingMode) {
            RenderingMode.MONOSPACE_SCROLL, RenderingMode.MONOSPACE_ZOOM -> JetBrainsMonoFamily
            RenderingMode.PROPORTIONAL_WRAP -> FontFamily.Default
        }

    val textAlign =
        when (line.alignment) {
            MicronAlignment.LEFT -> TextAlign.Start
            MicronAlignment.CENTER -> TextAlign.Center
            MicronAlignment.RIGHT -> TextAlign.End
        }

    val baseFontSize =
        when {
            line.isHeading && line.headingLevel == 1 -> HEADING1_SP.sp
            line.isHeading && line.headingLevel == 2 -> HEADING2_SP.sp
            line.isHeading && line.headingLevel == 3 -> HEADING3_SP.sp
            renderingMode == RenderingMode.MONOSPACE_ZOOM -> 10.sp
            else -> 14.sp
        }

    val indentPadding = (line.indentLevel * INDENT_DP).dp

    // In scroll mode (MONOSPACE_SCROLL), use min-width for ALL alignments so wide
    // lines (e.g. ASCII art) can extend beyond the viewport and scroll horizontally.
    // In other modes, centered/right-aligned lines use exact viewport width so text
    // wraps within the viewport (matching NomadNet terminal behavior).
    val isScrollMode = renderingMode == RenderingMode.MONOSPACE_SCROLL
    val widthModifier =
        if (minLineWidth != Dp.Unspecified) {
            if (isScrollMode) {
                Modifier.widthIn(min = minLineWidth)
            } else {
                when (line.alignment) {
                    MicronAlignment.CENTER, MicronAlignment.RIGHT -> Modifier.width(minLineWidth)
                    MicronAlignment.LEFT -> Modifier.widthIn(min = minLineWidth)
                }
            }
        } else {
            Modifier
        }

    // Check if this line contains any form fields
    val hasFormElements =
        line.elements.any {
            it is MicronElement.Field || it is MicronElement.Checkbox || it is MicronElement.Radio
        }

    if (hasFormElements) {
        // Render form elements in a Column (fields need more vertical space)
        Column(
            modifier =
                widthModifier
                    .fillMaxWidth()
                    .padding(start = indentPadding),
        ) {
            for (element in line.elements) {
                when (element) {
                    is MicronElement.Field -> {
                        OutlinedTextField(
                            value = formFields[element.name] ?: element.defaultValue,
                            onValueChange = { onFieldUpdate(element.name, it) },
                            label = { Text(element.name) },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            singleLine = true,
                            visualTransformation =
                                if (element.masked) {
                                    androidx.compose.ui.text.input
                                        .PasswordVisualTransformation()
                                } else {
                                    androidx.compose.ui.text.input.VisualTransformation.None
                                },
                        )
                    }
                    is MicronElement.Checkbox -> {
                        val isChecked =
                            formFields[element.name]
                                ?.split(",")
                                ?.contains(element.value) == true ||
                                (formFields[element.name] == null && element.prechecked)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.defaultMinSize(minHeight = MIN_LINK_HEIGHT_DP.dp),
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val current = formFields[element.name] ?: ""
                                    val values = current.split(",").filter { it.isNotEmpty() }.toMutableList()
                                    if (checked) {
                                        if (element.value !in values) values.add(element.value)
                                    } else {
                                        values.remove(element.value)
                                    }
                                    onFieldUpdate(element.name, values.joinToString(","))
                                },
                            )
                            Text(
                                text = element.label,
                                color = defaultFg,
                                fontFamily = fontFamily,
                            )
                        }
                    }
                    is MicronElement.Radio -> {
                        val isSelected =
                            formFields[element.name] == element.value ||
                                (formFields[element.name] == null && element.prechecked)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.defaultMinSize(minHeight = MIN_LINK_HEIGHT_DP.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onFieldUpdate(element.name, element.value) },
                            )
                            Text(
                                text = element.label,
                                color = defaultFg,
                                fontFamily = fontFamily,
                            )
                        }
                    }
                    is MicronElement.Text -> {
                        if (element.content.isNotEmpty()) {
                            Text(
                                text = element.content,
                                color = element.style.resolveColor(defaultFg),
                                fontFamily = fontFamily,
                                fontWeight = if (element.style.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (element.style.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = if (element.style.underline) TextDecoration.Underline else TextDecoration.None,
                            )
                        }
                    }
                    is MicronElement.Link -> {
                        // Render the link as a clickable Text that carries any form-field
                        // names declared after the destination (e.g. `[Send Message`:/page`a|b]
                        // where a & b name the fields to submit). Without this branch, links
                        // on the same line as form fields were silently dropped — the canonical
                        // "Send Message" affordance on pages like fr33n0w/thechatroom vanished.
                        Text(
                            text = element.label,
                            color = Color(0xFF6699FF),
                            fontFamily = fontFamily,
                            textDecoration = TextDecoration.Underline,
                            modifier =
                                Modifier
                                    .defaultMinSize(minHeight = MIN_LINK_HEIGHT_DP.dp)
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onLinkClick(element.destination, element.fieldNames)
                                    },
                        )
                    }
                    else -> { /* skip dividers/linebreaks/partials in form line */ }
                }
            }
        }
        return
    }

    // Build AnnotatedString for text-only lines (with links)
    val hasLinks = line.elements.any { it is MicronElement.Link }
    val annotatedString = buildMicronAnnotatedString(line.elements, defaultFg)

    val headingBg =
        if (line.isHeading) {
            val firstText = line.elements.firstOrNull() as? MicronElement.Text
            firstText
                ?.style
                ?.background
                ?.toArgb()
                ?.let { Color(it) }
        } else {
            null
        }

    val lineModifier =
        widthModifier
            .then(if (isScrollMode) Modifier else Modifier.fillMaxWidth())
            .padding(start = indentPadding)
            .then(if (headingBg != null) Modifier.background(headingBg) else Modifier)
            .then(
                if (hasLinks && !isScrollMode) {
                    Modifier.defaultMinSize(minHeight = MIN_LINK_HEIGHT_DP.dp)
                } else {
                    Modifier
                },
            )

    // In scroll mode, use tight line metrics for pixel-art rendering:
    // - lineHeight = squareLineHeightSp so ▄ half-blocks are square (height = 2 × charWidth)
    //   and SpanStyle.background fills the full line height (no transparent gaps)
    // - letterSpacing = 0 so all characters have uniform spacing
    // - No font padding so consecutive lines are flush
    val textStyle =
        if (isScrollMode) {
            TextStyle(
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                lineHeight = if (squareLineHeightSp != TextUnit.Unspecified) squareLineHeightSp else baseFontSize,
                letterSpacing = 0.sp,
                textAlign = textAlign,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle =
                    LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
            )
        } else {
            MaterialTheme.typography.bodyMedium.copy(
                fontFamily = fontFamily,
                fontSize = baseFontSize,
                textAlign = textAlign,
            )
        }

    if (hasLinks) {
        ClickableText(
            text = annotatedString,
            modifier = lineModifier,
            softWrap = !isScrollMode,
            style = textStyle,
            onClick = { offset ->
                annotatedString
                    .getStringAnnotations("link", offset, offset)
                    .firstOrNull()
                    ?.let { annotation ->
                        // Parse annotation: "destination\u001Ffield1\u001Ffield2"
                        val parts = annotation.item.split("\u001F")
                        val destination = parts[0]
                        val fieldNames = parts.drop(1)
                        onLinkClick(destination, fieldNames)
                    }
            },
        )
    } else {
        Text(
            text = annotatedString,
            modifier = lineModifier,
            softWrap = !isScrollMode,
            style = textStyle,
        )
    }
}

private fun buildMicronAnnotatedString(
    elements: List<MicronElement>,
    defaultFg: Color,
): AnnotatedString =
    buildAnnotatedString {
        for (element in elements) {
            when (element) {
                is MicronElement.Text -> {
                    if (element.content.isNotEmpty()) {
                        withStyle(element.style.toSpanStyle(defaultFg)) {
                            append(element.content)
                        }
                    }
                }
                is MicronElement.Link -> {
                    val linkColor = Color(0xFF6699FF) // Light blue for links
                    val spanStyle =
                        element.style.toSpanStyle(defaultFg).copy(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        )
                    // Encode destination + field names in annotation
                    val annotation =
                        if (element.fieldNames.isEmpty()) {
                            element.destination
                        } else {
                            element.destination + "\u001F" + element.fieldNames.joinToString("\u001F")
                        }
                    pushStringAnnotation("link", annotation)
                    withStyle(spanStyle) {
                        append(element.label)
                    }
                    pop()
                }
                is MicronElement.Divider,
                is MicronElement.LineBreak,
                is MicronElement.Field,
                is MicronElement.Checkbox,
                is MicronElement.Radio,
                is MicronElement.Partial,
                -> { /* handled separately */ }
            }
        }
    }

private fun MicronStyle.toSpanStyle(defaultFg: Color): SpanStyle =
    SpanStyle(
        color = resolveColor(defaultFg),
        background = background.toArgb()?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
    )

private fun MicronStyle.resolveColor(defaultFg: Color): Color = foreground.toArgb()?.let { Color(it) } ?: defaultFg
