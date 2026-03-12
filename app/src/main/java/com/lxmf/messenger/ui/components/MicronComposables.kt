package com.lxmf.messenger.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmf.messenger.micron.MicronAlignment
import com.lxmf.messenger.micron.MicronDocument
import com.lxmf.messenger.micron.MicronElement
import com.lxmf.messenger.micron.MicronLine
import com.lxmf.messenger.micron.MicronStyle
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.RenderingMode

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
    isDark: Boolean,
    onLinkClick: (destination: String, fieldNames: List<String>) -> Unit,
    onFieldUpdate: (name: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
    minLineWidth: Dp = Dp.Unspecified,
) {
    val defaultFg = MaterialTheme.colorScheme.onSurface
    val pageBg = document.pageBackground?.toArgb()?.let { Color(it) }
    val containerModifier = if (pageBg != null) modifier.background(pageBg) else modifier

    Column(modifier = containerModifier) {
        for (line in document.lines) {
            MicronLineComposable(
                line = line,
                formFields = formFields,
                defaultFg = defaultFg,
                renderingMode = renderingMode,
                onLinkClick = onLinkClick,
                onFieldUpdate = onFieldUpdate,
                minLineWidth = minLineWidth,
            )
        }
    }
}

@Composable
private fun MicronLineComposable(
    line: MicronLine,
    formFields: Map<String, String>,
    defaultFg: Color,
    renderingMode: RenderingMode,
    onLinkClick: (destination: String, fieldNames: List<String>) -> Unit,
    onFieldUpdate: (name: String, value: String) -> Unit,
    minLineWidth: Dp = Dp.Unspecified,
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

    val fontFamily =
        when (renderingMode) {
            RenderingMode.MONOSPACE_SCROLL, RenderingMode.MONOSPACE_ZOOM -> FontFamily.Monospace
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

    // For centered/right-aligned lines, use exact viewport width so text wraps
    // within the viewport (matching NomadNet terminal behavior). For left-aligned
    // lines, use min width so they can extend beyond the viewport for scrolling.
    val widthModifier =
        if (minLineWidth != Dp.Unspecified) {
            when (line.alignment) {
                MicronAlignment.CENTER, MicronAlignment.RIGHT -> Modifier.width(minLineWidth)
                MicronAlignment.LEFT -> Modifier.widthIn(min = minLineWidth)
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
                            formFields[element.name]?.contains(element.value) == true ||
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
                    else -> { /* skip links/dividers/linebreaks in form line */ }
                }
            }
        }
        return
    }

    // Build AnnotatedString for text-only lines (with links)
    val hasLinks = line.elements.any { it is MicronElement.Link }
    val annotatedString = buildMicronAnnotatedString(line.elements, defaultFg, fontFamily)

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
            .fillMaxWidth()
            .padding(start = indentPadding)
            .then(if (headingBg != null) Modifier.background(headingBg) else Modifier)
            .then(
                if (hasLinks) {
                    Modifier.defaultMinSize(minHeight = MIN_LINK_HEIGHT_DP.dp)
                } else {
                    Modifier
                },
            )

    if (hasLinks) {
        ClickableText(
            text = annotatedString,
            modifier = lineModifier,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = fontFamily,
                    fontSize = baseFontSize,
                    textAlign = textAlign,
                ),
            onClick = { offset ->
                annotatedString
                    .getStringAnnotations("link", offset, offset)
                    .firstOrNull()
                    ?.let { annotation ->
                        // Parse annotation: "destination|field1|field2"
                        val parts = annotation.item.split("|")
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
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = fontFamily,
                    fontSize = baseFontSize,
                    textAlign = textAlign,
                ),
        )
    }
}

private fun buildMicronAnnotatedString(
    elements: List<MicronElement>,
    defaultFg: Color,
    fontFamily: FontFamily,
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
                            element.destination + "|" + element.fieldNames.joinToString("|")
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
